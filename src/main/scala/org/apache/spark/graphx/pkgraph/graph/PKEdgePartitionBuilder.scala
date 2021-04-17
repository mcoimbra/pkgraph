package org.apache.spark.graphx.pkgraph.graph

import org.apache.spark.graphx.pkgraph.compression.K2TreeBuilder
import org.apache.spark.graphx.pkgraph.util.collection.OrderedHashMap
import org.apache.spark.graphx.util.collection.GraphXPrimitiveKeyOpenHashMap
import org.apache.spark.graphx.{Edge, VertexId}
import org.apache.spark.util.collection.{BitSet, PrimitiveVector}

import scala.collection.mutable
import scala.reflect.ClassTag

private[graph] class PKEdgePartitionBuilder[V: ClassTag, E: ClassTag] private (
    k: Int,
    vertexAttrs: GraphXPrimitiveKeyOpenHashMap[VertexId, V]
) {
  private val edges = new PrimitiveVector[Edge[E]]

  private var startX: Long = 0
  private var startY: Long = 0

  // Start at -1 so that if no edges are added the builder will build a K²-Tree with size 0
  private var endX: Long = 0
  private var endY: Long = 0

  /**
    * Adds the edge with the given vertices and attribute to this builder.
    *
    * @param src Source vertex identifier
    * @param dst Destination vertex identifier
    * @param attr Edge attribute
    */
  def add(src: VertexId, dst: VertexId, attr: E): Unit = {
    edges += Edge(src, dst, attr)
    startX = math.min(startX, src)
    startY = math.min(startY, dst)
    endX = math.max(endX, src)
    endY = math.max(endY, dst)
  }

  def build: PKEdgePartition[V, E] = {
    val edgeArray = edges.trim().array
    val treeBuilder = K2TreeBuilder(k, math.max(endX - startX + 1, endY - startY + 1).toInt)
    val attrs = mutable.TreeSet[(Int, E)]()((a, b) => a._1 - b._1)
    val srcIndex = new BitSet(treeBuilder.size)
    val dstIndex = new BitSet(treeBuilder.size)

    for (edge <- edgeArray) {
      val localSrcId = (edge.srcId - startX).toInt
      val localDstId = (edge.dstId - startY).toInt

      srcIndex.set(localSrcId)
      dstIndex.set(localDstId)

      val index = treeBuilder.addEdge(localSrcId, localDstId)

      // Our solution does not support multi-graphs, so we ignore repeated edges
      attrs.add((index, edge.attr))
    }

    val edgeIndices = new GraphXPrimitiveKeyOpenHashMap[Int, Int]
    var pos = 0
    for((index, _) <- attrs) {
      edgeIndices(index) = pos
      pos += 1
    }

    val activeSet = new BitSet(0)
    val attrValues = attrs.toArray.map(_._2)
    val edgeAttrs = new OrderedHashMap[Int, E](edgeIndices, attrValues)
    new PKEdgePartition[V, E](
      vertexAttrs,
      edgeAttrs,
      treeBuilder.build,
      startX,
      startY,
      activeSet,
      srcIndex,
      dstIndex
    )
  }
}

object PKEdgePartitionBuilder {
  def apply[V: ClassTag, E: ClassTag](k: Int): PKEdgePartitionBuilder[V, E] = {
    new PKEdgePartitionBuilder[V, E](k, new GraphXPrimitiveKeyOpenHashMap[VertexId, V])
  }
}
