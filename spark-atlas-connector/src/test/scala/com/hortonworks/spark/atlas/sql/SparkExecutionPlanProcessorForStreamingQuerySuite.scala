/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hortonworks.spark.atlas.sql

import java.nio.file.Files

import com.hortonworks.spark.atlas.sql.testhelper.{AtlasQueryExecutionListener, CreateEntitiesTrackingAtlasClient, DirectProcessSparkExecutionPlanProcessor}
import com.hortonworks.spark.atlas.types.external.KAFKA_TOPIC_STRING
import com.hortonworks.spark.atlas.types.metadata
import com.hortonworks.spark.atlas.utils.SparkUtils
import com.hortonworks.spark.atlas.AtlasClientConf
import org.apache.atlas.model.instance.AtlasEntity
import org.apache.spark.sql.kafka010.KafkaTestUtils
import org.apache.spark.sql.kafka010.atlas.KafkaTopicInformation
import org.apache.spark.sql.streaming.{StreamTest, StreamingQuery}

class SparkExecutionPlanProcessorForStreamingQuerySuite extends StreamTest {
  import com.hortonworks.spark.atlas.sql.testhelper.AtlasEntityReadHelper._

  val brokerProps: Map[String, Object] = Map[String, Object]()
  var testUtils: KafkaTestUtils = _

  val atlasClientConf: AtlasClientConf = new AtlasClientConf()
    .set(AtlasClientConf.CHECK_MODEL_IN_START.key, "false")
  var atlasClient: CreateEntitiesTrackingAtlasClient = _
  val testHelperQueryListener = new AtlasQueryExecutionListener()

  override def beforeAll(): Unit = {
    super.beforeAll()
    testUtils = new KafkaTestUtils(brokerProps)
    testUtils.setup()
    atlasClient = new CreateEntitiesTrackingAtlasClient()
    testHelperQueryListener.clear()
    spark.listenerManager.register(testHelperQueryListener)
  }

  override def afterAll(): Unit = {
    if (testUtils != null) {
      testUtils.teardown()
      testUtils = null
    }
    atlasClient = null
    spark.listenerManager.unregister(testHelperQueryListener)
    super.afterAll()
  }

  test("Kafka source(s) to kafka sink - micro-batch query") {
    val planProcessor = new DirectProcessSparkExecutionPlanProcessor(atlasClient, atlasClientConf)

    val topicsToRead1 = Seq("sparkread1", "sparkread2", "sparkread3")
    val topicsToRead2 = Seq("sparkread3", "sparkread4")
    val topicToWrite = "sparkwrite"

    val topics = topicsToRead1 ++ topicsToRead2 ++ Seq(topicToWrite)

    val brokerAddress = testUtils.brokerAddress

    topics.toSet[String].foreach { ti =>
      testUtils.createTopic(ti, 10, overwrite = true)
    }

    val tempDir = Files.createTempDirectory("spark-atlas-kafka-harvester")

    // remove temporary directory in shutdown
    org.apache.hadoop.util.ShutdownHookManager.get().addShutdownHook(
      new Runnable {
        override def run(): Unit = {
          Files.deleteIfExists(tempDir)
        }
      }, 10)

    val df = spark.readStream
      .format("kafka")
      .option("kafka.bootstrap.servers", brokerAddress)
      .option("subscribe", topicsToRead1.mkString(","))
      .option("startingOffsets", "earliest")
      .load()

    val customClusterName = "customCluster"
    val df2 = spark.readStream
      .format("kafka")
      .option("kafka.bootstrap.servers", brokerAddress)
      .option("kafka." + AtlasClientConf.CLUSTER_NAME.key, customClusterName)
      .option("subscribe", topicsToRead2.mkString(","))
      .option("startingOffsets", "earliest")
      .load()

    val query = df.union(df2).writeStream
      .format("kafka")
      .option("kafka.bootstrap.servers", brokerAddress)
      .option("kafka." + AtlasClientConf.CLUSTER_NAME.key, customClusterName)
      .option("topic", topicToWrite)
      .option("checkpointLocation", tempDir.toAbsolutePath.toString)
      .start()

    try {
      sendMessages(topicsToRead1)
      sendMessages(topicsToRead2)
      waitForBatchCompleted(query, testHelperQueryListener)

      import org.scalatest.time.SpanSugar._
      var queryDetails: Seq[QueryDetail] = null
      var entitySet: Set[AtlasEntity] = null
      eventually(timeout(10.seconds)) {
        queryDetails = testHelperQueryListener.queryDetails
        queryDetails.foreach(planProcessor.process)

        val createdEntities = atlasClient.createdEntities
        logInfo(s"Count of created entities (with duplication): ${createdEntities.size}")
        entitySet = getUniqueEntities(createdEntities)
        logInfo(s"Count of created entities after deduplication: ${entitySet.size}")

        // spark_process, topic to write, topics to read group 1 and 2
        assert(entitySet.size == topicsToRead1.size + topicsToRead2.size + 2)
      }

      val topicsToRead1WithClusterInfo = topicsToRead1.map { tp =>
        KafkaTopicInformation(tp, None)
      }
      val topicsToRead2WithClusterInfo = topicsToRead2.map { tp =>
        KafkaTopicInformation(tp, Some(customClusterName))
      }
      val topicToWriteWithClusterInfo = KafkaTopicInformation(topicToWrite, Some(customClusterName))

      val topicsToReadWithClusterInfo = topicsToRead1WithClusterInfo ++ topicsToRead2WithClusterInfo
      val topicsWithClusterInfo = topicsToReadWithClusterInfo ++ Seq(topicToWriteWithClusterInfo)

      assertEntitiesKafkaTopicType(topicsWithClusterInfo, entitySet)
      assertEntitySparkProcessType(topicsToReadWithClusterInfo, topicToWriteWithClusterInfo,
        entitySet, queryDetails)
    } finally {
      query.stop()
    }
  }

  private def sendMessages(topicsToRead: Seq[String]): Unit = {
    topicsToRead.foreach { topic =>
      testUtils.sendMessages(topic, Array("1", "2", "3", "4", "5"))
    }
  }

  private def waitForBatchCompleted(query: StreamingQuery, listener: AtlasQueryExecutionListener)
  : Unit = {
    import org.scalatest.time.SpanSugar._
    eventually(timeout(10.seconds)) {
      query.processAllAvailable()
      assert(listener.queryDetails.nonEmpty)
    }
  }

  private def assertEntitiesKafkaTopicType(topics: Seq[KafkaTopicInformation],
                                           entities: Set[AtlasEntity]): Unit = {
    val kafkaTopicEntities = listAtlasEntitiesAsType(entities.toSeq, KAFKA_TOPIC_STRING)
    assert(kafkaTopicEntities.size === topics.size)

    val expectedTopicNames = topics.map(_.topicName).toSet
    val expectedClusterNames = topics.map(_.clusterName.getOrElse("primary")).toSet
    val expectedQualifiedNames = topics.map { ti =>
      KafkaTopicInformation.getQualifiedName(ti, "primary")
    }.toSet

    assert(kafkaTopicEntities.map(_.getAttribute("name").toString()).toSet === expectedTopicNames)
    assert(kafkaTopicEntities.map(_.getAttribute("topic").toString()).toSet ===
      expectedTopicNames)
    assert(kafkaTopicEntities.map(getStringAttribute(_, "uri")).toSet === expectedTopicNames)
    assert(kafkaTopicEntities.map(getStringAttribute(_, "clusterName")).toSet ===
      expectedClusterNames)
    assert(kafkaTopicEntities.map(getStringAttribute(_, "qualifiedName")).toSet ===
      expectedQualifiedNames)
  }

  private def assertEntitySparkProcessType(
      topicsToRead: Seq[KafkaTopicInformation],
      topicToWrite: KafkaTopicInformation,
      entities: Set[AtlasEntity],
      queryDetails: Seq[QueryDetail])
    : Unit = {
    val processEntity = getOnlyOneEntity(entities.toSeq, metadata.PROCESS_TYPE_STRING)

    val inputs = getSeqAtlasEntityAttribute(processEntity, "inputs")
    val outputs = getSeqAtlasEntityAttribute(processEntity, "outputs")

    assert(!inputs.exists(_.getTypeName != KAFKA_TOPIC_STRING))
    assert(!outputs.exists(_.getTypeName != KAFKA_TOPIC_STRING))

    // unfortunately each batch recognizes topics which topics are having records to process
    // so there's no guarantee that all topics are recognized as 'inputs' for 'spark_process'
    assert(inputs.map(getStringAttribute(_, "qualifiedName")).toSet.subsetOf(
      topicsToRead.map(KafkaTopicInformation.getQualifiedName(_, "primary")).toSet))

    assert(outputs.map(getStringAttribute(_, "qualifiedName")).toSet ===
      Seq(topicToWrite).map(KafkaTopicInformation.getQualifiedName(_, "primary")).toSet)

    // verify others
    // it is OK if there's a matching query detail: since only one is exactly
    // matched to 'spark_process' entity
    val anyMatchingFound = queryDetails.exists { queryDetail =>
      val expectedMap = Map(
        "executionId" -> queryDetail.executionId.toString,
        "remoteUser" -> SparkUtils.currSessionUser(queryDetail.qe),
        "executionTime" -> queryDetail.executionTime.toString,
        "details" -> queryDetail.qe.toString()
      )

      expectedMap.forall { case (key, value) =>
        processEntity.getAttribute(key) == value
      }
    }

    assert(anyMatchingFound)
  }

  private def getUniqueEntities(entities: Seq[AtlasEntity]): Set[AtlasEntity] = {
    // same entities must be taken only once, and it is not likely to be done with equals
    // because pseudo guid is generated per each creation and 'equals' checks this value
    // so we take 'typeName' and 'qualifiedName' as a unique qualifier

    // (type, qualifier) -> AtlasEntity first occurred
    val entitiesMap = new scala.collection.mutable.HashMap[(String, String), AtlasEntity]()

    entities.foreach { entity =>
      val typeName = entity.getTypeName
      val qualifiedName = getStringAttribute(entity, "qualifiedName")
      val mapKey = (typeName, qualifiedName)
      if (!entitiesMap.contains(mapKey)) {
        entitiesMap.put(mapKey, entity)
      }
    }

    entitiesMap.values.toSet
  }
}