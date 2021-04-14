package org.apache.spark.graphx.pkgraph.graph

import org.apache.spark.graphx._
import org.apache.spark.graphx.impl.EdgeActiveness
import org.apache.spark.graphx.pkgraph.compression.{K2Tree, K2TreeIndex}
import org.apache.spark.graphx.pkgraph.util.mathx
import org.apache.spark.graphx.util.collection.GraphXPrimitiveKeyOpenHashMap
import org.apache.spark.util.collection.BitSet

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.reflect.ClassTag

/**
  *
  * @param vertexAttrs Maps vertex global identifier to their attribute
  * @param edgeAttrs Stores vertex attributes
  * @param tree K2Tree representing edges
  * @param srcOffset Source identifier offset
  * @param dstOffset Destination identifier offset
  * @param activeSet BitSet to keep track of active vertices
  * @param srcIndex BitSet to keep the identifiers of existing source vertices and whether they are active
  *                 or not. Each position in the BitSet is made up of 2 bits: the first bit keeps track of
  *                 whether the vertex identified by that position exists or not, the second bit keeps track
  *                 if the vertex is active.
  * @param dstIndex BitSet to keep the identifiers of existing destination vertices and whether they are active
  *                 or not. Each position in the BitSet is made up of 2 bits: the first bit keeps track of
  *                 whether the vertex identified by that position exists or not, the second bit keeps track
  *                 if the vertex is active.
  * @tparam V Vertex attribute type
  * @tparam E Edge attribute type
  */
private[graph] class PKEdgePartition[V: ClassTag, E: ClassTag](
    val vertexAttrs: GraphXPrimitiveKeyOpenHashMap[VertexId, V],
    val edgeAttrs: Array[E],
    val tree: K2Tree,
    val srcOffset: Long,
    val dstOffset: Long,
    val activeSet: BitSet,
    val srcIndex: BitSet,
    val dstIndex: BitSet
) {

  /**
    * Return a new edge partition without any locally cached vertex attributes.
    *
    * @tparam V2 new type of vertices
    * @return edge partition without cached vertices
    */
  def withoutVertexAttributes[V2: ClassTag](): PKEdgePartition[V2, E] = {
    val vertexAttrs = new GraphXPrimitiveKeyOpenHashMap[VertexId, V2]
    new PKEdgePartition(vertexAttrs, edgeAttrs, tree, srcOffset, dstOffset, activeSet, srcIndex, dstIndex)
  }

  /**
    * Return a new [[PKEdgePartition]] with the specified active set, provided as an iterator.
    *
    * @param iter Active set as iterator
    * @return [[PKEdgePartition]] with given active set
    */
  def withActiveSet(iter: Iterator[VertexId]): PKEdgePartition[V, E] = {
    val activeSet = new BitSet(tree.size)
    val offset = math.min(srcOffset, dstOffset)
    while (iter.hasNext) {
      val vid = iter.next()
      activeSet.set((vid - offset).toInt)
    }
    new PKEdgePartition(vertexAttrs, edgeAttrs, tree, srcOffset, dstOffset, activeSet, srcIndex, dstIndex)
  }

  /**
    * Look up vid in activeSet, throwing an exception if it is None.
    *
    * @param vid Vertex identifier
    * @return true if vertex is active, false otherwise
    */
  def isActive(vid: VertexId): Boolean = {
    val offset = math.min(srcOffset, dstOffset)
    activeSet.get((vid - offset).toInt)
  }

  /**
    * The number of active vertices.
    *
    * @return number of active vertices
    */
  def numActives: Int = activeSet.cardinality()

  /**
    * Returns a new [[PKEdgePartition]] with the given edges added.
    *
    * @param edges Edges to add
    * @return new partition with edges added
    */
  def addEdges(edges: Iterator[Edge[E]]): PKEdgePartition[V, E] = {
    var newSrcOffset = srcOffset
    var newDstOffset = dstOffset
    var newSrcEnd = srcOffset + tree.size
    var newDstEnd = dstOffset + tree.size

    // Traverse edges to check if there are any behind the virtual origin (lineOffset, colOffset) or
    // after the size of the current matrix
    val newEdges = new ArrayBuffer[Edge[E]]
    for (edge <- edges) {
      newSrcOffset = math.min(edge.srcId, srcOffset)
      newDstOffset = math.min(edge.dstId, dstOffset)
      newSrcEnd = math.max(edge.srcId, newSrcEnd)
      newDstEnd = math.max(edge.dstId, newDstEnd)
      newEdges += edge
    }

    var newTree = tree

    // Check if new edges are behind virtual origin
    if (newSrcOffset < srcOffset || newDstOffset < dstOffset) {
      // TODO: Grow K²-Tree to the left and up
      newTree = tree
    }

    // Check if new edges are after the end of the adjacency matrix
    if (newSrcEnd > srcOffset + tree.size || newDstEnd > dstOffset + tree.size) {
      // We need to first grow the tree
      var newSize = math.max(newSrcEnd - newSrcOffset, newDstEnd - newDstOffset).toInt

      // Size is not a power of K, so we need to find the nearest power
      if (newSize % tree.k != 0) {
        val exp = math.ceil(mathx.log(tree.k, newSize))
        newSize = math.pow(tree.k, exp).toInt
      }

      newTree = tree.grow(newSize)
    }

    // Check if the start or end of the adjacency matrix changed, only then do we need to rebuild the indexes
    val buildSrcIndex = newSrcOffset < srcOffset || newTree.size > tree.size
    val buildDstIndex = newDstOffset < dstOffset || newTree.size > tree.size

    val newSrcIndex = if (buildSrcIndex) new BitSet(newTree.size) else srcIndex
    val newDstIndex = if (buildDstIndex) new BitSet(newTree.size) else dstIndex

    // Add existing edges
    var i = 0
    val newEdgeAttrs = new mutable.TreeSet[(Int, E)]()((a, b) => a._1 - b._1)
    for (edge <- newTree.iterator) {
      newEdgeAttrs.add((edge.index, edgeAttrs(i)))

      if (buildSrcIndex) {
        newSrcIndex.set(edge.line)
      }

      if (buildDstIndex) {
        newDstIndex.set(edge.col)
      }

      i += 1
    }

    val builder = newTree.toBuilder

    // Traverse new edges again and add them to the builder
    for (edge <- newEdges) {
      val line = (edge.srcId - newSrcOffset).toInt
      val col = (edge.dstId - newDstOffset).toInt
      builder.addEdge(line, col)

      if(buildSrcIndex) {
        newSrcIndex.set(line)
      }

      if(buildDstIndex) {
        newDstIndex.set(col)
      }

      val index = K2TreeIndex.fromEdge(builder.k, builder.height, line, col)
      newEdgeAttrs.add((index, edge.attr))
    }

    val attrs = newEdgeAttrs.toArray.map(_._2)
    val k2tree = builder.build
    new PKEdgePartition[V, E](
      vertexAttrs,
      attrs,
      k2tree,
      newSrcOffset,
      newDstOffset,
      activeSet,
      newSrcIndex,
      newDstIndex
    )
  }

  /**
    * Returns new [[PKEdgePartition]] with the given edges removed.
    *
    * @param edges Edges to remove
    * @return new partition with edges removed
    */
  def removeEdges(edges: Iterator[(VertexId, VertexId)]): PKEdgePartition[V, E] = {
    // Build edge attribute sorted map to keep track of removed attributes
    val iterator = tree.iterator
    val attrs = new mutable.LinkedHashMap[Int, E]
    var i = 0
    while (i < edgeAttrs.length && iterator.hasNext) {
      val edge = iterator.next()
      attrs(edge.line * tree.size + edge.col) = edgeAttrs(i)
      i += 1
    }

    val builder = tree.toBuilder
    for ((srcId, dstId) <- edges) {
      val line = (srcId - srcOffset).toInt
      val col = (dstId - dstOffset).toInt
      builder.removeEdge(line, col)

      // Update indexes
      srcIndex.unset(line)
      dstIndex.unset(col)

      // Update attributes
      attrs.remove(line * tree.size + col)
    }

    // TODO: I think we don't need to make a copy of the indexes and can mutate them instead, since multiple calls to this method will have the same output
    val newAttrs = attrs.values.toArray
    val k2tree = builder.build
    new PKEdgePartition[V, E](vertexAttrs, newAttrs, k2tree, srcOffset, dstOffset, activeSet, srcIndex, dstIndex)
  }

  /**
    * Updates the vertex attributes in this partition with the ones in the given iterator.
    *
    * @param iter Iterator with vertex attributes
    * @return new [[PKEdgePartition]] with updated vertices
    */
  def updateVertices(iter: Iterator[(VertexId, V)]): PKEdgePartition[V, E] = {
    while (iter.hasNext) {
      val (id, attr) = iter.next()
      vertexAttrs.changeValue(id, attr, _ => attr)
    }
    // TODO: Not sure if we can just give the same reference to `vertexAttrs` or if a copy is needed
    // Should be okay since running the method multiple times will result in the same output
    new PKEdgePartition(vertexAttrs, edgeAttrs, tree, srcOffset, dstOffset, activeSet, srcIndex, dstIndex)
  }

  /**
    * Reverse all the edges in this partition.
    *
    * @return a new edge partition with all edges reversed.
    */
  def reverse: PKEdgePartition[V, E] = {
    new PKEdgePartition(
      vertexAttrs,
      edgeAttrs.reverse,
      tree.reverse,
      srcOffset,
      dstOffset,
      activeSet,
      srcIndex,
      dstIndex
    )
  }

  /**
    * Construct a new edge partition by applying the function f to all
    * edges in this partition.
    *
    * Be careful not to keep references to the objects passed to `f`.
    * To improve GC performance the same object is re-used for each call.
    *
    * @param f a function from an edge to a new attribute
    * @tparam E2 the type of the new attribute
    * @return a new edge partition with the result of the function `f` applied to each edge
    */
  def map[E2: ClassTag](f: Edge[E] => E2): PKEdgePartition[V, E2] = {
    val newData = new Array[E2](edgeAttrs.length)
    var i = 0

    for (edge <- iterator) {
      newData(i) = f(edge)
      i += 1
    }

    withData(newData)
  }

  /**
    * Construct a new edge partition by using the edge attributes
    * contained in the iterator.
    *
    * @note The input iterator should return edge attributes in the
    * order of the edges returned by `EdgePartition.iterator` and
    * should return attributes equal to the number of edges.
    *
    * @param iter an iterator for the new attribute values
    * @tparam E2 the type of the new attribute
    * @return a new edge partition with the attribute values replaced
    */
  def map[E2: ClassTag](iter: Iterator[E2]): PKEdgePartition[V, E2] = {
    // Faster than iter.toArray, because the expected size is known.
    val newData = new Array[E2](edgeAttrs.length)
    var i = 0
    while (iter.hasNext) {
      newData(i) = iter.next()
      i += 1
    }
    assert(newData.length == i)
    this.withData(newData)
  }

  /**
    * Construct a new edge partition containing only the edges matching `epred` and where both
    * vertices match `vpred`.
    *
    * @param epred Edge predicate
    * @param vpred Vertex predicate
    * @return edge partition containing only edges and vertices that match the predicate
    */
  def filter(epred: EdgeTriplet[V, E] => Boolean, vpred: (VertexId, V) => Boolean): PKEdgePartition[V, E] = {
    val builder = PKEdgePartitionBuilder.existing[V, E](tree.k, vertexAttrs)
    for (edge <- iterator) {
      // The user sees the EdgeTriplet, so we can't reuse it and must create one per edge.
      val triplet = new EdgeTriplet[V, E]
      triplet.srcId = edge.srcId
      triplet.dstId = edge.dstId
      triplet.srcAttr = vertexAttrs(edge.srcId)
      triplet.dstAttr = vertexAttrs(edge.dstId)
      triplet.attr = edge.attr

      if (vpred(triplet.srcId, triplet.srcAttr) && vpred(triplet.dstId, triplet.dstAttr) && epred(triplet)) {
        builder.add(triplet.srcId, triplet.dstId, triplet.attr)
      }
    }
    builder.build
  }

  /**
    * Apply the function f to all edges in this partition.
    *
    * @param f an external state mutating user defined function.
    */
  def foreach(f: Edge[E] => Unit): Unit = {
    iterator.foreach(f)
  }

  /**
    * Apply `f` to all edges present in both `this` and `other` and return a new `PKEdgePartition`
    * containing the resulting edges.
    *
    * If there are multiple edges with the same src and dst in `this`, `f` will be invoked once for
    * each edge, but each time it may be invoked on any corresponding edge in `other`.
    *
    * If there are multiple edges with the same src and dst in `other`, `f` will only be invoked
    * once.
    *
    * @param other Other edge partition to join with
    * @param f Function to compute merged edge
    * @return new partition with resulting edges
    */
  def innerJoin[E2: ClassTag, E3: ClassTag](
      other: PKEdgePartition[_, E2]
  )(f: (VertexId, VertexId, E, E2) => E3): PKEdgePartition[V, E3] = {
    val builder = PKEdgePartitionBuilder[V, E3](tree.k)
    val comparator = new PKEdgeComparator[E, E2](this, other)

    val it1 = iteratorWithIndex
    val it2 = other.iteratorWithIndex

    var edge2: PKEdge[E2] = PKEdge()
    while (it1.hasNext && it2.hasNext) {
      val edge1 = it1.next()

      while (it2.hasNext && comparator.compare(edge1, edge2) > 0) {
        edge2 = it2.next()
      }

      if (edge1.line == edge2.line && edge1.col == edge2.col) {
        builder.add(edge1.line, edge1.col, f(edge1.line, edge1.col, edge1.attr, edge2.attr))
      }
    }

    builder.build
  }

  /**
    * The number of edges in this partition
    *
    * @return size of the partition
    */
  val size: Int = edgeAttrs.length

  /**
    * The number of unique source vertices in the partition.
    * @return number of unique source vertices
    */
  def srcIndexSize: Int = srcIndex.cardinality()

  /**
    * The number of unique destination vertices in the partition.
    * @return number of unique destination vertices
    */
  def dstIndexSize: Int = dstIndex.cardinality()

  /**
    * Get an iterator over the edges in this partition.
    * Note: This iterator can be slightly faster than the [[K2TreeIterator]] because it counts the number of edges,
    * avoiding having to search the entire tree.
    *
    * Be careful not to keep references to the objects from this iterator.
    * To improve GC performance the same object is re-used in `next()`.
    *
    * @return an iterator over edges in the partition
    */
  def iterator: Iterator[Edge[E]] =
    new Iterator[Edge[E]] {
      private val iterator = tree.iterator
      private val edge = new Edge[E]
      private var pos = 0

      override def hasNext: Boolean = pos < edgeAttrs.length && iterator.hasNext

      override def next(): Edge[E] = {
        val nextEdge = iterator.next()
        edge.srcId = nextEdge.line + srcOffset
        edge.dstId = nextEdge.col + dstOffset
        edge.attr = edgeAttrs(pos)
        pos += 1
        edge
      }
    }

  /**
    * Get an iterator over the edges in this partition with the edge index included.
    * Note: This iterator can be slightly faster than the [[K2TreeIterator]] because it counts the number of edges,
    * avoiding having to search the entire tree.
    *
    * Be careful not to keep references to the objects from this iterator.
    * To improve GC performance the same object is re-used in `next()`.
    *
    * @return an iterator over edges in the partition
    */
  def iteratorWithIndex: Iterator[PKEdge[E]] =
    new Iterator[PKEdge[E]] {
      private val iterator = tree.iterator
      private val edge = new PKEdge[E]
      private var pos = 0

      override def hasNext: Boolean = pos < edgeAttrs.length && iterator.hasNext

      override def next(): PKEdge[E] = {
        val nextEdge = iterator.next()
        edge.index = nextEdge.index
        edge.line = nextEdge.line + srcOffset
        edge.col = nextEdge.col + dstOffset
        edge.attr = edgeAttrs(pos)
        pos += 1
        edge
      }
    }

  /**
    * Get an iterator over the edge triplets in this partition.
    * Note: This iterator can be slightly faster than the [[K2TreeIterator]] because it counts the number of edges,
    * avoiding having to search the entire tree.
    *
    * It is safe to keep references to the objects from this iterator.
    *
    * @param includeSrc Include source vertex attributes
    * @param includeDst Include destination vertex
    * @return edge triplet iterator
    */
  def tripletIterator(includeSrc: Boolean = true, includeDst: Boolean = true): Iterator[EdgeTriplet[V, E]] =
    new Iterator[EdgeTriplet[V, E]] {
      private val iterator = tree.iterator
      private var pos = 0

      override def hasNext: Boolean = pos < edgeAttrs.length && iterator.hasNext

      override def next(): EdgeTriplet[V, E] = {
        val triplet = new EdgeTriplet[V, E]

        val edge = iterator.next()
        triplet.srcId = edge.line + srcOffset
        triplet.dstId = edge.col + dstOffset
        triplet.attr = edgeAttrs(pos)

        if (includeSrc) {
          triplet.srcAttr = vertexAttrs(edge.line + srcOffset)
        }

        if (includeDst) {
          triplet.dstAttr = vertexAttrs(edge.col + dstOffset)
        }

        pos += 1
        triplet
      }
    }

  /**
    * Send messages along edges and aggregate them at the receiving vertices. Implemented by scanning
    * all edges sequentially.
    *
    * @param sendMsg generates messages to neighboring vertices of an edge
    * @param mergeMsg the combiner applied to messages destined to the same vertex
    * @param tripletFields which triplet fields `sendMsg` uses
    *
    * @return iterator aggregated messages keyed by the receiving vertex id
    */
  def aggregateMessagesEdgeScan[A: ClassTag](
      sendMsg: EdgeContext[V, E, A] => Unit,
      mergeMsg: (A, A) => A,
      tripletFields: TripletFields,
      activeness: EdgeActiveness
  ): Iterator[(VertexId, A)] = {
    val ctx = PKAggregatingEdgeContext[V, E, A](mergeMsg)
    for (edge <- iterator) {
      if (isEdgeActive(edge, activeness)) {
        val srcAttr = if (tripletFields.useSrc) vertexAttrs(edge.srcId) else null.asInstanceOf[V]
        val dstAttr = if (tripletFields.useDst) vertexAttrs(edge.dstId) else null.asInstanceOf[V]
        ctx.set(edge.srcId, edge.dstId, srcAttr, dstAttr, edge.attr)
        sendMsg(ctx)
      }
    }
    ctx.iterator
  }

  /**
    * Send messages along edges and aggregate them at the receiving vertices. Implemented by
    * filtering the source vertex index, then scanning each edge cluster.
    *
    * @param sendMsg generates messages to neighboring vertices of an edge
    * @param mergeMsg the combiner applied to messages destined to the same vertex
    * @param tripletFields which triplet fields `sendMsg` uses
    * @param activeness criteria for filtering edges based on activeness
    *
    * @return iterator aggregated messages keyed by the receiving vertex id
    */
  def aggregateMessagesSrcIndexScan[A: ClassTag](
      sendMsg: EdgeContext[V, E, A] => Unit,
      mergeMsg: (A, A) => A,
      tripletFields: TripletFields,
      activeness: EdgeActiveness
  ): Iterator[(VertexId, A)] = {
    val ctx = PKAggregatingEdgeContext[V, E, A](mergeMsg)
    for (localId <- srcIndex.iterator) {
      val clusterId: Long = localId + srcOffset
      if (isSrcClusterActive(clusterId, activeness)) {
        val srcAttr = if (tripletFields.useSrc) vertexAttrs(clusterId) else null.asInstanceOf[V]
        ctx.setSrcOnly(clusterId, srcAttr)
        // TODO: Iterate neighbors of vertex 'clusterId' and call sendMsg for each active edge
      }
    }
    ctx.iterator
  }

  /**
    * Send messages along edges and aggregate them at the receiving vertices. Implemented by
    * filtering the destination vertex index, then scanning each edge cluster.
    *
    * @param sendMsg generates messages to neighboring vertices of an edge
    * @param mergeMsg the combiner applied to messages destined to the same vertex
    * @param tripletFields which triplet fields `sendMsg` uses
    * @param activeness criteria for filtering edges based on activeness
    *
    * @return iterator aggregated messages keyed by the receiving vertex id
    */
  def aggregateMessagesDstIndexScan[A: ClassTag](
      sendMsg: EdgeContext[V, E, A] => Unit,
      mergeMsg: (A, A) => A,
      tripletFields: TripletFields,
      activeness: EdgeActiveness
  ): Iterator[(VertexId, A)] = {
    val ctx = PKAggregatingEdgeContext[V, E, A](mergeMsg)
    for (localId <- dstIndex.iterator) {
      val clusterId: Long = localId + dstOffset
      if (isDstClusterActive(clusterId, activeness)) {
        val attr = if (tripletFields.useSrc) vertexAttrs(clusterId) else null.asInstanceOf[V]
        ctx.setDstOnly(clusterId, attr)
        // TODO: Iterate reverse neighbors of vertex 'clusterId' and call sendMsg for each active edge
      }
    }
    ctx.iterator
  }

  /**
    * Return a new edge partition with the specified edge data.
    *
    * @param data Edge attributes
    * @tparam E2 new type of edges
    * @return edge partition with given edge data
    */
  private def withData[E2: ClassTag](data: Array[E2]): PKEdgePartition[V, E2] = {
    new PKEdgePartition(vertexAttrs, data, tree, srcOffset, dstOffset, activeSet, srcIndex, dstIndex)
  }

  /**
    * Checks if the given source cluster is active according to the [[EdgeActiveness]] parameter.
    *
    * @param clusterId Source cluster identifier
    * @param activeness [[EdgeActiveness]] parameter
    * @return true if cluster is active, false otherwise
    */
  private def isSrcClusterActive(clusterId: VertexId, activeness: EdgeActiveness): Boolean = {
    activeness match {
      case EdgeActiveness.SrcOnly => isActive(clusterId)
      case EdgeActiveness.DstOnly => true
      case EdgeActiveness.Both    => isActive(clusterId)
      case EdgeActiveness.Either  => true
      case EdgeActiveness.Neither => true
    }
  }

  /**
    * Checks if the given destination cluster is active according to the [[EdgeActiveness]] parameter.
    *
    * @param clusterId Destination cluster identifier
    * @param activeness [[EdgeActiveness]] parameter
    * @return true if cluster is active, false otherwise
    */
  private def isDstClusterActive(clusterId: VertexId, activeness: EdgeActiveness): Boolean = {
    activeness match {
      case EdgeActiveness.SrcOnly => true
      case EdgeActiveness.DstOnly => isActive(clusterId)
      case EdgeActiveness.Both    => isActive(clusterId)
      case EdgeActiveness.Either  => true
      case EdgeActiveness.Neither => true
    }
  }

  /**
    * Checks if the given edge is active according to the [[EdgeActiveness]] parameter.
    *
    * @param edge Edge to check
    * @param activeness [[EdgeActiveness]] parameter
    * @return true if edge is active, false otherwise
    */
  private def isEdgeActive(edge: Edge[_], activeness: EdgeActiveness): Boolean = {
    activeness match {
      case EdgeActiveness.SrcOnly => isActive(edge.srcId)
      case EdgeActiveness.DstOnly => isActive(edge.dstId)
      case EdgeActiveness.Both    => isActive(edge.srcId) && isActive(edge.dstId)
      case EdgeActiveness.Either  => isActive(edge.srcId) || isActive(edge.dstId)
      case EdgeActiveness.Neither => true
    }
  }
}