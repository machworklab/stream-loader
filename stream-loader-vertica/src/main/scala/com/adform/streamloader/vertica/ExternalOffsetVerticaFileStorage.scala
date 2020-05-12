/*
 * Copyright (c) 2020 Adform
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.adform.streamloader.vertica

import java.sql.{Connection, SQLDataException, Timestamp => SqlTimestamp}

import com.adform.streamloader.file.RecordRangeFile
import com.adform.streamloader.file.storage.InDataOffsetFileStorage
import com.adform.streamloader.model.{StreamPosition, Timestamp}
import com.adform.streamloader.util.Logging
import javax.sql.DataSource
import org.apache.kafka.common.TopicPartition

import scala.collection.concurrent.TrieMap
import scala.util.Using

/**
  * A Vertica file storage implementation that loads data to some table and commits offsets to a separate dedicated offset table.
  * The commit happens in a single transaction, offsets and data can be joined using a file ID that is generated before
  * starting new files and is stored in both the data table and the offset table. The offset table contains only ranges of offsets
  * from each topic partition contained in the file. Its structure should look as follows (all names can be customized):
  *
  * {{{
  *   CREATE TABLE file_offsets (
  *   _file_id INT NOT NULL,
  *   _table VARCHAR(128) NOT NULL,
  *   _topic VARCHAR(128) NOT NULL,
  *   _partition INT NOT NULL,
  *   _start_offset INT NOT NULL,
  *   _start_watermark TIMESTAMP NOT NULL,
  *   _end_offset INT NOT NULL,
  *   _end_watermark TIMESTAMP NOT NULL
  * );
  * }}}
  *
  * Additionally a `SEQUENCE` is required for generating the `_file_id` foreign key values, create it as follows:
  *
  * {{{
  *   CREATE SEQUENCE file_id_sequence;
  * }}}
  *
  * Compared to the [[InRowOffsetVerticaFileStorage]] this implementation does not preserve individual row offsets,
  * however it is much less expensive in terms of data usage in the licensing scheme, as the license is calculated based
  * on the size of the data as it occupies converted to strings, ignoring compression and encoding.
  * Thus while it does not cost much to store the topic name, partition and offset next to each row physically (this data
  * compresses very well), it can be significant when auditing data usage for licensing.
  */
class ExternalOffsetVerticaFileStorage private (
    dbDataSource: DataSource,
    table: String,
    offsetTable: String,
    fileIdSequence: String,
    copyStatementProvider: VerticaCopyStatementProvider,
    fileIdColumnName: String,
    tableColumnName: String,
    topicColumnName: String,
    partitionColumnName: String,
    startOffsetColumnName: String,
    startWatermarkColumnName: String,
    endOffsetColumnName: String,
    endWatermarkColumnName: String,
) extends InDataOffsetFileStorage[Long]
    with Logging {

  override def startNewFile(): Long = {
    Using.resource(dbDataSource.getConnection) { connection =>
      val query = s"SELECT NEXTVAL('$fileIdSequence')"
      log.info(s"Running stream position query: $query")
      Using.resource(connection.prepareStatement(query)) { statement =>
        Using.resource(statement.executeQuery()) { result =>
          result.next()
          result.getLong(1)
        }
      }
    }
  }

  def committedPositions(connection: Connection): TrieMap[TopicPartition, StreamPosition] = {
    val query =
      s"SELECT $topicColumnName, $partitionColumnName, MAX($endOffsetColumnName) + 1, MAX($endWatermarkColumnName) " +
        s"FROM $offsetTable " +
        s"WHERE $tableColumnName = ? " +
        s"GROUP BY $topicColumnName, $partitionColumnName"
    log.info(s"Running stream position query: $query")
    Using.resource(connection.prepareStatement(query)) { statement =>
      statement.setString(1, table)
      Using.resource(statement.executeQuery()) { result =>
        val positions: TrieMap[TopicPartition, StreamPosition] = TrieMap.empty
        while (result.next()) {
          if (!result.wasNull()) {
            val topicPartition = new TopicPartition(result.getString(1), result.getInt(2))
            val position = StreamPosition(result.getLong(3), Timestamp(result.getTimestamp(4).getTime))
            positions.put(topicPartition, position)
          }
        }
        positions
      }
    }
  }

  override def committedPositions(topicPartitions: Set[TopicPartition]): Map[TopicPartition, Option[StreamPosition]] = {
    Using.resource(dbDataSource.getConnection) { connection =>
      val positions = committedPositions(connection)
      topicPartitions.map(tp => (tp, positions.get(tp))).toMap
    }
  }

  override def storeFile(file: RecordRangeFile[Long]): Unit = {
    Using.resource(dbDataSource.getConnection) { connection =>
      connection.setAutoCommit(false)
      val copyQuery = copyStatementProvider.copyStatement(table, file.file)
      try {
        val inserts = file.recordRanges.map { batch =>
          val batchInsertQuery =
            s"INSERT INTO $offsetTable " +
              s"($fileIdColumnName, $tableColumnName, $topicColumnName, $partitionColumnName, $startOffsetColumnName, $startWatermarkColumnName, $endOffsetColumnName, $endWatermarkColumnName) " +
              s"VALUES " +
              s"(?, ?, ?, ?, ?, ?, ?, ?)"
          val batchInsertStatement = connection.prepareStatement(batchInsertQuery)
          batchInsertStatement.setLong(1, file.fileId)
          batchInsertStatement.setString(2, table)
          batchInsertStatement.setString(3, batch.topic)
          batchInsertStatement.setInt(4, batch.partition)
          batchInsertStatement.setLong(5, batch.start.offset)
          batchInsertStatement.setTimestamp(6, new SqlTimestamp(batch.start.watermark.millis))
          batchInsertStatement.setLong(7, batch.end.offset)
          batchInsertStatement.setTimestamp(8, new SqlTimestamp(batch.end.watermark.millis))

          log.debug(s"Running statement: $batchInsertQuery")
          val insertResult = batchInsertStatement.executeUpdate()

          log.debug(s"Successfully inserted $insertResult batch metadata record(s)")
          insertResult
        }

        Using.resource(connection.prepareStatement(copyQuery)) { copyStatement =>
          log.info(s"Running statement: $copyQuery")
          val copyResult = copyStatement.executeUpdate()
          log.info(s"Successfully committed $copyResult record(s) and updated ${inserts.sum} batch metadata record(s)")
        }

        connection.commit()

      } catch {
        case e: SQLDataException =>
          log.error(e)("Failed inserting data, rolling back the transaction")
          connection.rollback()
          throw e
      }
    }
  }
}

object ExternalOffsetVerticaFileStorage {

  case class Builder(
      private val _dbDataSource: DataSource,
      private val _table: String,
      private val _offsetTable: String,
      private val _fileIdSequence: String,
      private val _copyStatementProvider: VerticaCopyStatementProvider,
      private val _fileIdColumnName: String = "_file_id",
      private val _tableColumnName: String = "_table",
      private val _topicColumnName: String = "_topic",
      private val _partitionColumnName: String = "_partition",
      private val _startOffsetColumnName: String = "_start_offset",
      private val _startWatermarkColumnName: String = "_start_watermark",
      private val _endOffsetColumnName: String = "_end_offset",
      private val _endWatermarkColumnName: String = "_end_watermark",
  ) {

    /**
      * Sets a data source for Vertica JDBC connections.
      */
    def dbDataSource(source: DataSource): Builder = copy(_dbDataSource = source)

    /**
      * Sets the table to load data to.
      */
    def table(name: String): Builder = copy(_table = name)

    /**
      * Sets a COPY statement provider to use for loading files.
      */
    def copyStatementProvider(provider: VerticaCopyStatementProvider): Builder = copy(_copyStatementProvider = provider)

    /**
      * Sets the name of the table used for storing offsets.
      */
    def offsetTable(name: String): Builder = copy(_offsetTable = name)

    /**
      * Sets the name of the sequence used for generating file IDs.
      */
    def fileIdSequence(name: String): Builder = copy(_fileIdSequence = name)

    /**
      * Sets the names of the columns in the offset table.
      * Used for querying the the offset during initialization to look up committed stream positions.
      */
    def offsetTableColumnNames(
        fileId: String = "_file_id",
        table: String = "_table",
        topic: String = "_topic",
        partition: String = "_partition",
        startOffset: String = "_start_offset",
        startWatermark: String = "_start_watermark",
        endOffset: String = "_end_offset",
        endWatermark: String = "_end_watermark"
    ): Builder =
      copy(
        _fileIdColumnName = fileId,
        _tableColumnName = table,
        _topicColumnName = topic,
        _partitionColumnName = partition,
        _startOffsetColumnName = startOffset,
        _startWatermarkColumnName = startWatermark,
        _endOffsetColumnName = endOffset,
        _endWatermarkColumnName = endWatermark
      )

    def build(): ExternalOffsetVerticaFileStorage = {
      if (_dbDataSource == null) throw new IllegalStateException("Must provide a Vertica data source")
      if (_table == null) throw new IllegalStateException("Must provide a valid table name")
      if (_offsetTable == null) throw new IllegalStateException("Must provide a valid offset table name")
      if (_fileIdSequence == null) throw new IllegalStateException("Must provide a valid file ID sequence name")
      if (_copyStatementProvider == null) throw new IllegalArgumentException("Must provide a COPY statement provider")

      new ExternalOffsetVerticaFileStorage(
        _dbDataSource,
        _table,
        _offsetTable,
        _fileIdSequence,
        _copyStatementProvider,
        _fileIdColumnName,
        _tableColumnName,
        _topicColumnName,
        _partitionColumnName,
        _startOffsetColumnName,
        _startWatermarkColumnName,
        _endOffsetColumnName,
        _endWatermarkColumnName
      )
    }
  }

  def builder(): Builder = Builder(null, null, null, null, null)
}