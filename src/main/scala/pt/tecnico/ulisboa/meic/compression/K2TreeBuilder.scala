package pt.tecnico.ulisboa.meic.compression

import org.apache.spark.util.collection.BitSet
import pt.tecnico.ulisboa.meic.compression.K2TreeBuilder.calculateLevelOffsets
import pt.tecnico.ulisboa.meic.util.collection.BitSetExtensions
import pt.tecnico.ulisboa.meic.util.{hash, mathx}

import scala.collection.mutable

private class K2TreeBuilder(val k: Int, val size: Int, val height: Int, val bits: BitSet, val length: Int) {
  private val k2 = k * k

  /**
   * Creates a new builder with the given edges added.
   *
   * @param edges Sequence of edges to add
   * @return builder of a K²-Tree with edges added
   */
  def addEdges(edges: Seq[(Int, Int)]): K2TreeBuilder = {
    navigateFromEdges(edges, (levelOffset, levelIndex) => {
      // Index relative to the beginning of the tree
      val index = levelOffset + levelIndex

      // Check if path is already built, if true we can move on to next edge
      val done = bits.get(index)

      bits.set(index)
      done
    })

    new K2TreeBuilder(k, size, height, bits, length)
  }

  /**
   * Creates a new builder with the given edges removed.
   *
   * @param edges Sequence of edges to remove
   * @return builder of a K²-Tree with edges removed
   */
  def removeEdges(edges: Seq[(Int, Int)]): K2TreeBuilder = {
    navigateFromEdges(edges, (levelOffset, levelIndex) => {
      // Index relative to the beginning of the tree
      val index = levelOffset + levelIndex
      bits.unset(index)

      // Start of the sequence of K² bits that this edge belongs to
      val chunkStart = levelOffset + (levelIndex / k2) * k2

      // We are done updating if there any other bits with value 1 in the same chunk
      bits.count(chunkStart, chunkStart + k2) > 0
    })

    new K2TreeBuilder(k, size, height, bits, length)
  }

  /**
   * Builds a compressed K²-Tree representation from this builder.
   *
   * @return K²-Tree representation
   */
  def build(): K2Tree = {
    // Count the number of bits need for the compressed version
    val (internalCount, leavesCount) = calculateCompressedSize()

    // Bitset to store both internal and leaf bits (T:L)
    val tree = new BitSet(internalCount + leavesCount)

    // Index for the tree bitset
    var t = 0

    for (i <- 0 until length / k2) {
      var include = false
      val buffer = new BitSet(k2)

      for (j <- 0 until k2) {
        if (bits.get(i * k2 + j)) {
          include = true
          buffer.set(j)
        }
      }

      if (include) {
        for (j <- 0 until k2) {
          if (buffer.get(j)) {
            tree.set(t)
          }
          t += 1
        }
      }
    }

    new K2Tree(k, size, tree, internalCount, leavesCount)
  }

  /**
   * Navigate from the given edges to the virtual root of this K²-Tree.
   * For every node found in the way to the root the function f is called with the current
   * level offset and level index.
   *
   * @param edges Sequence of edges to iterate
   * @param f     Function to execute at every node
   */
  private def navigateFromEdges(edges: Seq[(Int, Int)], f: (Int, Int) => Boolean): Unit = {
    val offsets = calculateLevelOffsets(k, height)

    def iterate(line: Int, col: Int): Unit = {
      var currLine = line
      var currCol = col
      var currSize = size

      for (h <- height until 0 by -1) {
        val levelOffset = offsets(h)

        // Index relative to the current level
        val levelIndex = hash.mortonCode(currLine, currCol)

        // Execute provided function, returns whether we are done iterating this edge or not
        val done = f(levelOffset, levelIndex)
        if (done) {
          return
        }

        currLine /= k
        currCol /= k
        currSize /= k
      }
    }

    for ((line, col) <- edges) {
      iterate(line, col)
    }
  }

  /**
   * Calculates the minimum size required to store all bits in the compressed representation.
   *
   * @return minimum number of bits required for the internal bits and leaves bits of the compressed representation
   */
  private def calculateCompressedSize(): (Int, Int) = {
    val leavesStart = (1 until height).map(h => math.pow(k2, h).toInt).sum / k2

    var i = 0
    var internalCount = 0
    var leavesCount = 0

    while (i < length / k2) {
      var j = 0
      var found = false

      while (!found && j < k2) {
        if (bits.get(i * k2 + j)) {
          found = true
        }
        j += 1
      }

      if (found) {
        if (i >= leavesStart) {
          leavesCount += k2
        } else {
          internalCount += k2
        }
      }

      i += 1
    }

    (internalCount, leavesCount)
  }
}

private object K2TreeBuilder {
  /**
   * Builds an empty K2TreeBuilder
   *
   * @param k    value of the K²-Tree
   * @param size Size of the adjacency matrix of the K²-Tree (i.e maximum line/col index rounded to nearest power of k)
   * @return empty builder for a compressed K²-Tree
   */
  def apply(k: Int, size: Int): K2TreeBuilder = {
    val height = math.ceil(mathx.log(k, size)).toInt
    val length = (0 to height).reduce((acc, i) => acc + math.pow(k * k, i).toInt)
    new K2TreeBuilder(k, size, height, new BitSet(length), length)
  }

  /**
   * Constructs a K²-Tree Builder from the given K²-Tree.
   *
   * The resulting builder will have the same size and same edges of the given K²-Tree.
   *
   * @param tree K²-Tree to construct builder from
   * @return K²-Tree builder with initial data from the given K²-Tree
   */
  def fromK2Tree(tree: K2Tree): K2TreeBuilder = {
    val k = tree.k
    val k2 = tree.k * tree.k
    val builder = K2TreeBuilder(tree.k, tree.size)
    val offsets = calculateLevelOffsets(tree.k, builder.height)

    def buildRecursive(height: Int, currSize: Int, line: Int, col: Int, pos: Int, index: Int): Unit = {
      if (pos >= tree.internalCount) { // Is leaf node
        if (tree.bits.get(pos)) {
          builder.bits.set(index)
          return
        }
      } else {
        if (pos == -1 || tree.bits.get(pos)) {
          if (pos != -1) {
            builder.bits.set(index)
          }

          val levelOffset = hash.mortonCode(line, col) * k2
          val globalOffset = offsets(height + 1)
          val offset = globalOffset + levelOffset

          val y = tree.bits.count(0, pos) * k2
          val newSize = currSize / k

          for (i <- 0 until k2) {
            buildRecursive(height + 1, newSize, line * k + i / k, col * k + i % k, y + i, offset + i)
          }
        }
      }
    }

    buildRecursive(0, builder.size, 0, 0, -1, -1)
    builder
  }

  /**
   * Calculates the starting offsets for each level of a K²-Tree.
   *
   * @param k      Value of the K²-Tree
   * @param height Height of the K²-Tree
   * @return mapping of each level (1..height) and their corresponding starting offset.
   */
  private def calculateLevelOffsets(k: Int, height: Int): mutable.Map[Int, Int] = {
    val k2 = k * k
    val offsets = mutable.HashMap[Int, Int]()

    // Level 1 always has an offset of 0
    offsets(1) = 0

    for (i <- 2 to height) {
      offsets.put(i, offsets(i - 1) + math.pow(k2, i - 1).toInt)
    }

    offsets
  }
}
