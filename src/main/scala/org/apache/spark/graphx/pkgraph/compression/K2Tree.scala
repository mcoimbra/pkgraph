package org.apache.spark.graphx.pkgraph.compression

import org.apache.spark.graphx.pkgraph.util.collection.PKBitSet
import org.apache.spark.graphx.pkgraph.util.mathx

class K2Tree(
    val k: Int,
    val size: Int,
    val bits: PKBitSet,
    val internalCount: Int,
    val leavesCount: Int
) {

  /**
    * Total number of bits used to represent this K²-Tree.
    *
    * @return number of bits used to represent this K²-Tree
    */
  def length: Int = internalCount + leavesCount

  /**
    * Get the height of this K²-Tree
    *
    * @return height
    */
  def height: Int = math.ceil(mathx.log(k, size)).toInt

  /**
    * Returns whether this tree is empty or not.
    *
    * @return true if tree has no edges, false otherwise
    */
  def isEmpty: Boolean = leavesCount == 0

  /**
    * Collect the edges encoded in this K²-Tree
    *
    * @return sequence of edges encoded in this K²-Tree
    */
  def edges: Array[K2TreeEdge] = iterator.toArray

  /**
    * Collects the direct neighbors of the vertex with the given line.
    *
    * @param line Vertex identifier in the adjacency matrix
    * @return sequence containing column of corresponding neighbors
    */
  def directNeighbors(line: Int): Array[Int] = directNeighborIterator(line).toArray

  /**
    * Collects the reverse neighbors of the vertex with the given column.
    *
    * @param col Vertex identifier in the adjacency matrix
    * @return sequence containing line of corresponding neighbor
    */
  def reverseNeighbors(col: Int): Array[Int] = reverseNeighborIterator(col).toArray

  /**
    * Get this tree's iterator.
    *
    * @return K²-Tree iterator
    */
  def iterator: Iterator[K2TreeEdge] = new K2TreeIterator(this)

  /**
    * Get an iterator that iterates the direct neighbors of the given line.
    *
    * @param line  Line to search direct neighbors of
    * @return direct neighbor iterator
    */
  def directNeighborIterator(line: Int): Iterator[Int] = new K2TreeDirectNeighborIterator(this, line)

  /**
    * Get an iterator that iterates the reverse neighbors of the given line.
    *
    * @param col  Column to search reverse neighbors of
    * @return reverse neighbor iterator
    */
  def reverseNeighborIterator(col: Int): Iterator[Int] = new K2TreeReverseNeighborIterator(this, col)

  /**
    * Returns a new K²-Tree with the given edges added.
    *
    * @param newSize Size of the adjacency matrix of the K²-Tree (i.e maximum line/col index rounded to nearest power of k)
    * @param edges   Edges to append to this K²-Tree
    * @return new K²-Tree from appending the given edges to the existing tree
    */
  def addAll(newSize: Int, edges: Array[(Int, Int)]): K2Tree = {
    val builder = K2TreeBuilder.fromK2Tree(grow(newSize))
    builder.addEdges(edges)
    builder.build
  }

  /**
    * Returns a new K²-Tree with the given edges removed.
    *
    * @param edges Edges to remove from this K²-Tree
    * @return new K²-Tree with the given edges removed
    */
  def removeAll(edges: Array[(Int, Int)]): K2Tree = {
    val builder = K2TreeBuilder.fromK2Tree(this)
    builder.removeEdges(edges)
    builder.build
  }

  /**
    * Grows this K²-Tree to the new given size.
    * All edges are kept.
    *
    * @param newSize New size to grow to
    * @param inverse True if the tree should go left and up, false to grow the tree right and down
    * @return K²-Tree representing a adjacency matrix with the given new size.
    */
  def grow(newSize: Int, inverse: Boolean = false): K2Tree = {
    if (newSize <= size) {
      return this
    }

    val k2 = k * k
    val levelChange = (newSize / size) / k
    val internalOffset = levelChange * k2
    val bitCount = internalOffset + length

    // Prefix the tree with K² bits for every level change
    val tree = new PKBitSet(bitCount)
    for (i <- 0 until levelChange) {
      // Inverse places a bit in the final child node of the given level
      // example: 0001
      //
      // When inverse is false a bit is placed in the first child node of the given level
      // example: 1000
      val index = if (inverse) ((i + 1) * k2) - 1 else i * k2
      tree.set(index)
    }

    // Add the original bits in the K²-Tree
    for (i <- 0 until length) {
      if (bits.get(i)) {
        tree.set(internalOffset + i)
      }
    }

    new K2Tree(k, newSize, tree, internalOffset + internalCount, leavesCount)
  }

  /**
    * Builds a new K²-Tree with the minimum required size to store all edges.
    * It is possible that the size is not changed if it's not possible to reduce the
    * size of the K²-Tree anymore.
    *
    * @return new K²-Tree with the minimum required size
    */
  def trim(): K2Tree = {
    // Cannot reduce a K²-Tree anymore than K
    if (size == k) {
      return this
    }

    val quadrantSize = size / k
    val last = quadrantSize - 1
    var shrink = true

    // Checks the last quadrants at the top level
    // If all are empty, then we can reduce the entire K²-Tree by one level
    var i = 0
    while (shrink && i < quadrantSize) {
      val lineIndex = i * quadrantSize + last
      val colIndex = last * quadrantSize + i

      if (bits.get(lineIndex) || bits.get(colIndex)) {
        shrink = false
      }

      i += 1
    }

    // Cannot shrink anymore
    if (!shrink) {
      return this
    }

    // Remove the first K² bits from the bitset
    val k2 = k * k
    val newBitCount = length - k2
    val tree = new PKBitSet(newBitCount)

    for (i <- 0 until newBitCount) {
      if (bits.get(k2 + i)) {
        tree.set(i)
      }
    }

    // Keep trying to trim the tree
    val newTree = new K2Tree(k, size / k, tree, internalCount - k2, leavesCount)
    newTree.trim()
  }

  def forEachEdge(f: (Int, Int) => Unit): Unit = {
    val k2 = k * k
    def recursiveNavigation(n: Int, line: Int, col: Int, pos: Int): Unit = {
      if (pos >= internalCount) { // Is non-zero leaf node
        if (bits.get(pos)) {
          f(line, col)
        }
      } else if (pos == -1 || bits.get(pos)) { // Is virtual node (-1) or non-zero internal node
        val y = rank(pos) * k2
        val newSize = n / k

        for (i <- 0 until k2) {
          recursiveNavigation(newSize, line * k + i / k, col * k + i % k, y + i)
        }
      }
    }

    recursiveNavigation(size, 0, 0, -1)
  }

  /**
    * Returns a [[K2TreeBuilder]] with the edges from this tree already added.
    *
    * @return [[K2TreeBuilder]] from this tree
    */
  final def toBuilder: K2TreeBuilder = K2TreeBuilder.fromK2Tree(this)

  /**
    * Rank operation of the K²-Tree.
    *
    * Counts the number of bits with value 1 in the tree bits between [0, end].
    *
    * @param end  Inclusive ending position
    * @return number of bits with value 1 between [0, end]
    */
  protected[compression] def rank(end: Int): Int = bits.count(0, end)
}

object K2Tree {

  /**
    * Builds a K²-Tree with the given k from the given edges.
    *
    * @param k     Value of the K²-Tree
    * @param size  Size of the adjacency matrix of the K²-Tree (i.e maximum line/col index rounded to nearest power of k)
    * @param edges Array of edges to build K²-Tree from
    * @return compressed K²-Tree
    */
  def apply(k: Int, size: Int, edges: Array[(Int, Int)]): K2Tree = {
    val builder = K2TreeBuilder(k, size)
    builder.addEdges(edges)
    builder.build
  }
}
