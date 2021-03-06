/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package streaming.dsl

import java.util.UUID

import org.apache.spark.sql.streaming.StreamingQuery
import org.apache.spark.sql.{DataFrame, DataFrameWriter, Row, SaveMode}
import streaming.core.datasource.{DataSinkConfig, DataSourceRegistry}
import streaming.core.stream.MLSQLStreamManager
import streaming.dsl.parser.DSLSQLParser._
import streaming.dsl.template.TemplateMerge
import tech.mlsql.job.{JobManager, MLSQLJobType}

import scala.collection.mutable.ArrayBuffer

/**
  * Created by allwefantasy on 27/8/2017.
  */
class SaveAdaptor(scriptSQLExecListener: ScriptSQLExecListener) extends DslAdaptor {

  def evaluate(value: String) = {
    TemplateMerge.merge(value, scriptSQLExecListener.env().toMap)
  }

  override def parse(ctx: SqlContext): Unit = {

    var oldDF: DataFrame = null
    var mode = SaveMode.ErrorIfExists
    var format = ""
    var option = Map[String, String]()
    var tableName = ""
    var partitionByCol = ArrayBuffer[String]()

    val owner = option.get("owner")
    var path = ""

    (0 to ctx.getChildCount() - 1).foreach { tokenIndex =>
      ctx.getChild(tokenIndex) match {
        case s: FormatContext =>
          format = s.getText
          format match {
            case "hive" =>
            case _ =>
              format = s.getText
          }


        case s: PathContext =>
          path = TemplateMerge.merge(cleanStr(s.getText), scriptSQLExecListener.env().toMap)

        case s: TableNameContext =>
          tableName = evaluate(s.getText)
          oldDF = scriptSQLExecListener.sparkSession.table(tableName)
        case s: OverwriteContext =>
          mode = SaveMode.Overwrite
        case s: AppendContext =>
          mode = SaveMode.Append
        case s: ErrorIfExistsContext =>
          mode = SaveMode.ErrorIfExists
        case s: IgnoreContext =>
          mode = SaveMode.Ignore
        case s: ColContext =>
          partitionByCol += cleanStr(s.identifier().getText)
        case s: ColGroupContext =>
          partitionByCol += cleanStr(s.col().identifier().getText)
        case s: ExpressionContext =>
          option += (cleanStr(s.qualifiedName().getText) -> evaluate(getStrOrBlockStr(s)))
        case s: BooleanExpressionContext =>
          option += (cleanStr(s.expression().qualifiedName().getText) -> evaluate(getStrOrBlockStr(s.expression())))
        case _ =>
      }
    }

    def isStream = {
      scriptSQLExecListener.env().contains("stream")
    }

    val spark = oldDF.sparkSession
    import spark.implicits._
    val context = ScriptSQLExec.context()
    var job = JobManager.getJobInfo(context.groupId)


    if (isStream) {
      job = job.copy(jobType = MLSQLJobType.STREAM, jobName = scriptSQLExecListener.env()("streamName"))
      JobManager.addJobManually(job)
    }

    var streamQuery: StreamingQuery = null

    if (option.contains("fileNum")) {
      oldDF = oldDF.repartition(option.getOrElse("fileNum", "").toString.toInt)
    }
    val writer = if (isStream) null else oldDF.write

    val saveRes = DataSourceRegistry.fetch(format, option).map { datasource =>
      val res = datasource.asInstanceOf[ {def save(writer: DataFrameWriter[Row], config: DataSinkConfig): Any}].save(
        writer,
        // here we should change final_path to path in future
        DataSinkConfig(path, option ++ Map("partitionByCol" -> partitionByCol.mkString(",")),
          mode, Option(oldDF)))
      res
    }.getOrElse {

      if (isStream) {
        throw new RuntimeException(s"save is not support with ${format}  in stream mode")
      }
      if (partitionByCol.size != 0) {
        writer.partitionBy(partitionByCol: _*)
      }

      writer.mode(mode)

      if (path == "-" || path.isEmpty) {
        writer.format(option.getOrElse("implClass", format)).save()
      } else {
        writer.format(option.getOrElse("implClass", format)).save(resourceRealPath(context.execListener, owner, path))
      }
    }

    if (isStream) {
      streamQuery = saveRes.asInstanceOf[StreamingQuery]
    }

    job = JobManager.getJobInfo(context.groupId)
    if (streamQuery != null) {
      //here we do not need to clean the original groupId, since the StreamingproJobManager.handleJobDone(job.groupId)
      // will handle this. Also, if this is stream job, so it should be remove by the StreamManager if it fails
      job = job.copy(groupId = streamQuery.id.toString)
      JobManager.addJobManually(job)
      MLSQLStreamManager.addStore(job)
    }

    val tempTable = UUID.randomUUID().toString.replace("-", "")
    val outputTable = spark.createDataset(Seq(job))
    outputTable.createOrReplaceTempView(tempTable)
    scriptSQLExecListener.setLastSelectTable(tempTable)
  }
}
