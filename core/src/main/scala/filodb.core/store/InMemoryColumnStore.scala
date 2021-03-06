package filodb.core.store

import com.typesafe.scalalogging.slf4j.StrictLogging
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentSkipListMap
import javax.xml.bind.DatatypeConverter
import scala.collection.mutable.HashMap
import scala.concurrent.{ExecutionContext, Future}
import spray.caching._

import filodb.core._
import filodb.core.metadata.{Column, Projection}

/**
 * A ColumnStore implementation which is entirely in memory for speed.
 * Good for testing or performance.
 *
 * NOTE: This implementation effectively only works on a single node.
 * We would need, for example, a Spark-specific implementation which can
 * know how to distribute data, or at least keep track of different nodes,
 * TODO: use thread-safe structures
 */
class InMemoryColumnStore(implicit val ec: ExecutionContext)
extends CachedMergingColumnStore with StrictLogging {
  import Types._
  import collection.JavaConversions._

  logger.info("Starting InMemoryColumnStore...")

  val segmentCache = LruCache[Segment[_]](100)

  val mergingStrategy = new AppendingChunkMergingStrategy(this)

  type ChunkKey = (ColumnId, SegmentId, ChunkID)
  type ChunkTree = ConcurrentSkipListMap[ChunkKey, ByteBuffer]
  type RowMapTree = ConcurrentSkipListMap[SegmentId, (ByteBuffer, ByteBuffer, Int)]

  val EmptyChunkTree = new ChunkTree(Ordering[ChunkKey])
  val EmptyRowMapTree = new RowMapTree(Ordering[SegmentId])

  val chunkDb = new HashMap[(TableName, PartitionKey, Int), ChunkTree]
  val rowMaps = new HashMap[(TableName, PartitionKey, Int), RowMapTree]

  def initializeProjection(projection: Projection): Future[Response] = Future.successful(Success)

  def clearProjectionDataInner(projection: Projection): Future[Response] = Future {
    chunkDb.keys.collect { case key @ (ds, _, _) if ds == projection.dataset => chunkDb remove key }
    rowMaps.keys.collect { case key @ (ds, _, _) if ds == projection.dataset => rowMaps remove key }
    Success
  }

  def writeChunks(dataset: TableName,
                  partition: PartitionKey,
                  version: Int,
                  segmentId: SegmentId,
                  chunks: Iterator[(ColumnId, ChunkID, ByteBuffer)]): Future[Response] = Future {
    val chunkTree = chunkDb.synchronized {
      chunkDb.getOrElseUpdate((dataset, partition, version), new ChunkTree(Ordering[ChunkKey]))
    }
    chunks.foreach { case (colId, chunkId, bytes) =>
      chunkTree.put((colId, segmentId, chunkId), bytes)
    }
    Success
  }

  def writeChunkRowMap(dataset: TableName,
                       partition: PartitionKey,
                       version: Int,
                       segmentId: SegmentId,
                       chunkRowMap: ChunkRowMap): Future[Response] = Future {
    val rowMapTree = rowMaps.synchronized {
      rowMaps.getOrElseUpdate((dataset, partition, version), new RowMapTree(Ordering[SegmentId]))
    }
    val (chunkIds, rowNums) = chunkRowMap.serialize()
    rowMapTree.put(segmentId, (chunkIds, rowNums, chunkRowMap.nextChunkId))
    Success
  }

  def readChunks[K](columns: Set[ColumnId],
                    keyRange: KeyRange[K],
                    version: Int): Future[Seq[ChunkedData]] = {
    val chunkTree = chunkDb.getOrElse((keyRange.dataset, keyRange.partition, version),
                                      EmptyChunkTree)
    logger.debug(s"Reading chunks from columns $columns, keyRange $keyRange, version $version")
    val chunks = for { column <- columns.toSeq } yield {
      val startKey = (column, keyRange.binaryStart, 0)
      val endKey   = if (keyRange.endExclusive) { (column, keyRange.binaryEnd, 0) }
                     else                       { (column, keyRange.binaryEnd, Int.MaxValue) }
      val it = chunkTree.subMap(startKey, true, endKey, !keyRange.endExclusive).entrySet.iterator
      val chunkList = it.toSeq.map { entry =>
        val (colId, segmentId, chunkId) = entry.getKey
        (segmentId, chunkId, entry.getValue)
      }
      ChunkedData(column, chunkList)
    }
    Future.successful(chunks)
  }

  def readChunkRowMaps[K](keyRange: KeyRange[K], version: Int):
      Future[Seq[(SegmentId, BinaryChunkRowMap)]] = Future {
    val rowMapTree = rowMaps.getOrElse((keyRange.dataset, keyRange.partition, version), EmptyRowMapTree)
    val it = rowMapTree.subMap(keyRange.binaryStart, true,
                               keyRange.binaryEnd, !keyRange.endExclusive).entrySet.iterator
    it.toSeq.map { entry =>
      val (chunkIds, rowNums, nextChunkId) = entry.getValue
      (entry.getKey, new BinaryChunkRowMap(chunkIds, rowNums, nextChunkId))
    }
  }

  def scanChunkRowMaps(dataset: TableName,
                       version: Int,
                       partitionFilter: (PartitionKey => Boolean),
                       params: Map[String, String]): Future[Iterator[ChunkMapInfo]] = Future {
    val parts = rowMaps.keysIterator.filter { case (ds, partition, ver) =>
      ds == dataset && ver == version && partitionFilter(partition) }
    val maps = parts.flatMap { case key @ (_, partition, _) =>
                 rowMaps(key).entrySet.iterator.map { entry =>
                   val segId = entry.getKey
                   val (chunkIds, rowNums, nextChunkId) = entry.getValue
                   (partition, segId, new BinaryChunkRowMap(chunkIds, rowNums, nextChunkId))
                 }
               }
    maps.toIterator
  }

  // Add an efficient scanSegments implementation here, which can avoid much of the async
  // cruft unnecessary for in-memory stuff
  override def scanSegments[K: SortKeyHelper](columns: Seq[Column],
                                              dataset: TableName,
                                              version: Int,
                                              partitionFilter: (PartitionKey => Boolean),
                                              params: Map[String, String]): Future[Iterator[Segment[K]]] = {
    val helper = implicitly[SortKeyHelper[K]]
    for { chunkmapsIt <- scanChunkRowMaps(dataset, version, partitionFilter, params) }
    yield {
      chunkmapsIt.map { case (partition, segId, binChunkRowMap) =>
        val decodedSegId = helper.fromBytes(segId)
        val keyRange = KeyRange(dataset, partition, decodedSegId, decodedSegId)
        val segment = new RowReaderSegment(keyRange, binChunkRowMap, columns)
        for { column <- columns } {
          val colName = column.name
          val chunkTree = chunkDb.getOrElse((dataset, partition, version), EmptyChunkTree)
          chunkTree.subMap((colName, segId, 0), true, (colName, segId, Int.MaxValue), true)
                   .entrySet.iterator.foreach { entry =>
            val (_, _, chunkId) = entry.getKey
            segment.addChunk(chunkId, colName, entry.getValue)
          }
        }
        segment
      }
    }
  }

  def shutdown(): Unit = {}

  // InMemoryColumnStore is just on one node, so return no splits for now.
  // TODO: achieve parallelism by splitting on a range of partitions.
  def getScanSplits(dataset: TableName,
                    params: Map[String, String]): Seq[Map[String, String]] = Seq(Map.empty)

  def bbToHex(bb: ByteBuffer): String = DatatypeConverter.printHexBinary(bb.array)
}