package org.apache.spark.graphx.pkgraph.graph

import org.apache.spark.graphx.{VertexId, VertexSet}
import org.apache.spark.graphx.pkgraph.compression.K2TreeBuilder
import org.apache.spark.graphx.pkgraph.util.collection.PKBitSet
import org.apache.spark.graphx.util.collection.GraphXPrimitiveKeyOpenHashMap
import org.apache.spark.util.collection.PrimitiveVector

import scala.reflect.ClassTag

private[graph] class PKExistingEdgePartitionBuilder[V: ClassTag, @specialized(Long, Int, Double) E: ClassTag](
    vertexAttrs: Array[V],
    global2local: GraphXPrimitiveKeyOpenHashMap[VertexId, Int],
    edges: PrimitiveVector[PKEdge[E]],
    builder: K2TreeBuilder,
    srcOffset: Long,
    dstOffset: Long,
    activeSet: Option[VertexSet]
) {
  private val srcVertices = new PKBitSet(builder.size)
  private val dstVertices = new PKBitSet(builder.size)

  def addEdge(src: VertexId, dst: VertexId, attr: E): Unit = {
    val line = (src - srcOffset).toInt
    val col = (dst - dstOffset).toInt
    val index = builder.addEdge(line, col)

    srcVertices.set(line)
    dstVertices.set(col)
    edges += PKEdge(index, line, col, attr)
  }

  def removeEdge(src: VertexId, dst: VertexId): Unit = {
    val line = (src - srcOffset).toInt
    val col = (dst - dstOffset).toInt

    builder.removeEdge(line, col)
    srcVertices.unset(line)
    dstVertices.unset(col)
  }

  def build(recomputeVertices: Boolean = false): PKEdgePartition[V, E] = {
    var newVertexAttrs = vertexAttrs
    val sortedEdges = edges.toArray
      .filter { e => srcVertices.get(e.line) && dstVertices.get(e.col) }
      .sortWith((a, b) => a.index < b.index)

    if (recomputeVertices) {
      // Compute new mappings
      var currLocalId = vertexAttrs.length - 1
      for (edge <- sortedEdges) {
        global2local.changeValue(edge.line + srcOffset, { currLocalId += 1; currLocalId }, identity)
        global2local.changeValue(edge.col + dstOffset, { currLocalId += 1; currLocalId }, identity)
      }

      // Copy existing vertex attributes
      newVertexAttrs = new Array[V](currLocalId + 1)
      System.arraycopy(vertexAttrs, 0, newVertexAttrs, 0, vertexAttrs.length)
    }

    val edgeAttrs = sortedEdges.map(_.attr)
    new PKEdgePartition[V, E](newVertexAttrs, global2local, edgeAttrs, builder.build, srcOffset, dstOffset, activeSet)
  }
}

object PKExistingEdgePartitionBuilder {

  /**
    * Partition builder from an existing empty partition.
    * The edge attributes are not reused.
    *
    * @param vertexAttrs     Array of vertex attributes
    * @param global2local    Mapping of global to local vertices
    * @param activeSet       Set of active vertices
    * @param treeBuilder     Non-empty tree builder
    * @param srcOffset       Existing source offset
    * @param dstOffset       Existing destination offset
    * @tparam V              Type of vertex attributes
    * @tparam E              Type of edge attributes
    * @return existing partition builder
    */
  def apply[V: ClassTag, E: ClassTag](
      vertexAttrs: Array[V],
      global2local: GraphXPrimitiveKeyOpenHashMap[VertexId, Int],
      activeSet: Option[VertexSet],
      treeBuilder: K2TreeBuilder,
      srcOffset: Long,
      dstOffset: Long
  ): PKExistingEdgePartitionBuilder[V, E] = {
    new PKExistingEdgePartitionBuilder[V, E](
      vertexAttrs,
      global2local,
      new PrimitiveVector[PKEdge[E]](64),
      treeBuilder,
      srcOffset,
      dstOffset,
      activeSet
    )
  }

  /**
    * Creates a new builder from an existing partition but with no edge attributes.
    *
    * @param partition        Partition to build from
    * @tparam V               Type of vertex attributes
    * @tparam E               Type of edge attributes
    * @return existing partition builder
    */
  def empty[V: ClassTag, E: ClassTag](partition: PKEdgePartition[V, _]): PKExistingEdgePartitionBuilder[V, E] = {
    val treeBuilder = K2TreeBuilder(partition.tree.k, partition.tree.size)
    new PKExistingEdgePartitionBuilder[V, E](
      partition.vertexAttrs,
      partition.global2local,
      new PrimitiveVector[PKEdge[E]](64),
      treeBuilder,
      partition.srcOffset,
      partition.dstOffset,
      partition.activeSet
    )
  }

  /**
    * Partition builder from an existing partition.
    * The edge attributes are reused.
    *
    * @param partition       Existing partition
    * @param treeBuilder     Non-empty tree builder
    * @param newSrcOffset    New source offset
    * @param newDstOffset    New destination offset
    * @tparam V              Type of vertex attributes
    * @tparam E              Type of edge attributes
    * @return existing partition builder
    */
  def existing[V: ClassTag, E: ClassTag](
      partition: PKEdgePartition[V, E],
      treeBuilder: K2TreeBuilder,
      newSrcOffset: Long,
      newDstOffset: Long
  ): PKExistingEdgePartitionBuilder[V, E] = {
    val builder = new PKExistingEdgePartitionBuilder[V, E](
      partition.vertexAttrs,
      partition.global2local,
      new PrimitiveVector[PKEdge[E]](partition.edgeAttrs.length),
      treeBuilder,
      newSrcOffset,
      newDstOffset,
      partition.activeSet
    )

    // Copy existing edges to builder
    partition.foreach(edge => builder.addEdge(edge.srcId, edge.dstId, edge.attr))

    builder
  }

  /**
    * Partition builder from an existing partition.
    * The same offsets are kept.
    * The edge attributes are reused.
    *
    * @param partition       Existing partition
    * @param treeBuilder     Non-empty tree builder
    * @tparam V              Type of vertex attributes
    * @tparam E              Type of edge attributes
    * @return existing partition builder
    */
  def existing[V: ClassTag, E: ClassTag](
      partition: PKEdgePartition[V, E],
      treeBuilder: K2TreeBuilder
  ): PKExistingEdgePartitionBuilder[V, E] = existing(partition, treeBuilder, partition.srcOffset, partition.dstOffset)
}
