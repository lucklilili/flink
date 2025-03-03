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
package org.apache.flink.table.planner.runtime.batch.sql

import org.apache.flink.configuration.MemorySize
import org.apache.flink.core.testutils.FlinkMatchers
import org.apache.flink.streaming.api.operators.collect.CollectSinkOperatorFactory
import org.apache.flink.table.planner.factories.TestValuesTableFactory
import org.apache.flink.table.planner.runtime.utils.BatchAbstractTestBase.createTempFolder
import org.apache.flink.table.planner.runtime.utils.BatchTestBase
import org.apache.flink.table.planner.runtime.utils.BatchTestBase.row
import org.apache.flink.table.planner.runtime.utils.TestData.smallData3
import org.apache.flink.table.planner.utils.TableTestUtil

import org.assertj.core.api.Assertions
import org.hamcrest.MatcherAssert
import org.junit.{Assert, Test}

class TableSinkITCase extends BatchTestBase {

  @Test
  def testTableHints(): Unit = {
    val dataId = TestValuesTableFactory.registerData(smallData3)
    tEnv.executeSql(s"""
                       |CREATE TABLE MyTable (
                       |  `a` INT,
                       |  `b` BIGINT,
                       |  `c` STRING
                       |) WITH (
                       |  'connector' = 'values',
                       |  'bounded' = 'true',
                       |  'data-id' = '$dataId'
                       |)
       """.stripMargin)

    val resultPath = createTempFolder().getAbsolutePath
    tEnv.executeSql(s"""
                       |CREATE TABLE MySink (
                       |  `a` INT,
                       |  `b` BIGINT,
                       |  `c` STRING
                       |) WITH (
                       |  'connector' = 'filesystem',
                       |  'format' = 'testcsv',
                       |  'path' = '$resultPath'
                       |)
       """.stripMargin)
    val stmtSet = tEnv.createStatementSet()
    val newPath1 = createTempFolder().getAbsolutePath
    stmtSet.addInsertSql(
      s"insert into MySink /*+ OPTIONS('path' = '$newPath1') */ select * from MyTable")
    val newPath2 = createTempFolder().getAbsolutePath
    stmtSet.addInsertSql(
      s"insert into MySink /*+ OPTIONS('path' = '$newPath2') */ select * from MyTable")
    stmtSet.execute().await()

    Assert.assertTrue(TableTestUtil.readFromFile(resultPath).isEmpty)
    val expected = Seq("1,1,Hi", "2,2,Hello", "3,2,Hello world")
    val result1 = TableTestUtil.readFromFile(newPath1)
    Assert.assertEquals(expected.sorted, result1.sorted)
    val result2 = TableTestUtil.readFromFile(newPath2)
    Assert.assertEquals(expected.sorted, result2.sorted)
  }

  @Test
  def testCollectSinkConfiguration(): Unit = {
    tEnv.getConfig.set(CollectSinkOperatorFactory.MAX_BATCH_SIZE, MemorySize.parse("1b"))
    try {
      checkResult("SELECT 1", Seq(row(1)))
      Assert.fail("Expecting exception thrown from collect sink")
    } catch {
      case e: Exception =>
        MatcherAssert.assertThat(
          e,
          FlinkMatchers.containsMessage(
            "Please consider increasing max bytes per batch value " +
              "by setting collect-sink.batch-size.max"))
    }

    tEnv.getConfig.set(CollectSinkOperatorFactory.MAX_BATCH_SIZE, MemorySize.parse("1kb"))
    checkResult("SELECT 1", Seq(row(1)))
  }

  @Test
  def testCreateTableAsSelect(): Unit = {
    val dataId = TestValuesTableFactory.registerData(smallData3)
    tEnv.executeSql(s"""
                       |CREATE TABLE MyTable (
                       |  `a` INT,
                       |  `b` BIGINT,
                       |  `c` STRING
                       |) WITH (
                       |  'connector' = 'values',
                       |  'bounded' = 'true',
                       |  'data-id' = '$dataId'
                       |)
       """.stripMargin)

    val resultPath = createTempFolder().getAbsolutePath
    tEnv
      .executeSql(s"""
                     |CREATE TABLE MyCtasTable
                     | WITH (
                     |  'connector' = 'filesystem',
                     |  'format' = 'testcsv',
                     |  'path' = '$resultPath'
                     |) AS
                     | SELECT * FROM MyTable
       """.stripMargin)
      .await()
    val expected = Seq("1,1,Hi", "2,2,Hello", "3,2,Hello world")
    val result = TableTestUtil.readFromFile(resultPath)
    Assertions.assertThat(result.sorted).isEqualTo(expected.sorted)

    // test statement set
    val statementSet = tEnv.createStatementSet()
    val useStatementResultPath =
      createTempFolder().getAbsolutePath
    statementSet.addInsertSql(s"""
                                 |CREATE TABLE MyCtasTableUseStatement
                                 | WITH (
                                 |  'connector' = 'filesystem',
                                 |  'format' = 'testcsv',
                                 |  'path' = '$useStatementResultPath'
                                 |) AS
                                 | SELECT * FROM MyTable
                                 |""".stripMargin)
    statementSet.execute().await()
    val useStatementResult = TableTestUtil.readFromFile(useStatementResultPath)
    Assertions.assertThat(useStatementResult.sorted).isEqualTo(expected.sorted)
  }

  @Test
  def testCreateTableAsSelectWithoutOptions(): Unit = {
    // TODO CTAS supports ManagedTable
    val dataId = TestValuesTableFactory.registerData(smallData3)
    tEnv.executeSql(s"""
                       |CREATE TABLE MyTable (
                       |  `a` INT,
                       |  `b` BIGINT,
                       |  `c` STRING
                       |) WITH (
                       |  'connector' = 'values',
                       |  'bounded' = 'true',
                       |  'data-id' = '$dataId'
                       |)
       """.stripMargin)

    Assertions
      .assertThatThrownBy(
        () =>
          tEnv
            .executeSql("""
                          |CREATE TABLE MyCtasTable
                          | AS
                          | SELECT * FROM MyTable
                          |""".stripMargin)
            .await())
      .hasRootCauseMessage("\nExpecting actual not to be null")
  }
}
