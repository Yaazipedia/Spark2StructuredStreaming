package com.dataframe.part17.DynamoDBRead.KinesisSaveAsHadoopDataSet

import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder
import com.amazonaws.services.dynamodbv2.document.{DynamoDB, Item}
import com.amazonaws.services.kinesis.clientlibrary.lib.worker.InitialPositionInStream
import com.dataframe.part11.kinesis.consumer.KinesisConsumerDynamoDB.Enums
import org.apache.spark.SparkConf
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions.{from_json, lit}
import org.apache.spark.sql.types.{DoubleType, StringType, StructType, TimestampType}
import org.apache.spark.storage.StorageLevel
import org.apache.spark.streaming.{Duration, Seconds, StreamingContext}
import org.apache.spark.streaming.kinesis.KinesisUtils
import org.apache.hadoop.mapred.JobConf
import com.amazonaws.services.dynamodbv2.model.AttributeValue
import java.util.HashMap
import org.apache.hadoop.dynamodb.DynamoDBItemWritable
import org.apache.hadoop.io.Text
/*
Example Take from here
https://stackoverflow.com/questions/47722648/spark-2-2-0-how-to-write-read-dataframe-to-dynamodb

#Consumer
spark-submit --class com.dataframe.part11.kinesis.consumer.KinesisSaveAsHadoopDataSet.TransactionConsumerDstreamToDynamoDBHadoopDataSet /
./Spark2StructuredStreaming-1.0-SNAPSHOT-jar-with-dependencies.jar "app4" "creditcardTransaction6" "creditcardTransactionDBHadoopDataSet"


*/

object TransactionConsumerDstreamToDynamoDBHadoopDataSet {
  def main(args: Array[String]): Unit = {

    val transactionStructureName = "transaction"

    /* Schema of transaction msgs received from Kafka. Json msg is received from Kafka. Hence evey field is treated as String */
    val kinesisTransactionStructureName = transactionStructureName
    val kinesisTransactionSchema = new StructType()
      .add(Enums.TransactionConsumerKinesis.cc_num, StringType,true)
      .add(Enums.TransactionConsumerKinesis.first, StringType, true)
      .add(Enums.TransactionConsumerKinesis.last, StringType, true)
      .add(Enums.TransactionConsumerKinesis.trans_num, StringType, true)
      .add(Enums.TransactionConsumerKinesis.trans_time, TimestampType, true)
      .add(Enums.TransactionConsumerKinesis.category, StringType, true)
      .add(Enums.TransactionConsumerKinesis.merchant, StringType, true)
      .add(Enums.TransactionConsumerKinesis.amt, StringType, true)
      .add(Enums.TransactionConsumerKinesis.merch_lat, StringType, true)
      .add(Enums.TransactionConsumerKinesis.merch_long, StringType, true)

    val ssc = new StreamingContext("local[*]", "KinesisExampleHadoopExample", Seconds(1))

    val kinesisStream   = KinesisUtils.createStream(
      ssc, args(0), args(1), "kinesis.us-east-1.amazonaws.com",
      "us-east-1", InitialPositionInStream.LATEST, Duration(2000), StorageLevel.MEMORY_AND_DISK_2)

    val lines           = kinesisStream.map(x => new String(x))

    val dynamodDBClinet = AmazonDynamoDBClientBuilder.standard().withRegion("us-east-1").build
    val dynamoDBCon     = new DynamoDB(dynamodDBClinet)
    val dynamoDBTable   = dynamoDBCon.getTable(args(2))

    val conf = new SparkConf()
      .setAppName("Sample")
    val spark = SparkSession.builder.config(conf).getOrCreate()
    import spark.implicits._

    val ddbConf = new JobConf(spark.sparkContext.hadoopConfiguration)
    ddbConf.set("dynamodb.output.tableName", args(2))
    ddbConf.set("dynamodb.throughput.write.percent", "1.5")
    ddbConf.set("dynamodb.endpoint", "dynamodb.us-east-1.amazonaws.com")
    ddbConf.set("dynamodb.regionid", "us-east-1")
    ddbConf.set("dynamodb.servicename", "dynamodb")
    ddbConf.set("mapred.input.format.class", "org.apache.hadoop.dynamodb.read.DynamoDBInputFormat")
    ddbConf.set("mapred.output.format.class", "org.apache.hadoop.dynamodb.write.DynamoDBOutputFormat")

    lines.cache().foreachRDD{

      rdd =>
        if (!rdd.isEmpty()) {

          val kinesisTransactionDF  = rdd.toDF("transaction")
            .withColumn(kinesisTransactionStructureName, // nested structure with our json
              from_json($"transaction", kinesisTransactionSchema)) //From binary to JSON object
            .select("transaction.*")
            .withColumn("amt", lit($"amt") cast (DoubleType))
            .withColumn("merch_lat", lit($"merch_lat") cast (DoubleType))
            .withColumn("merch_long", lit($"merch_long") cast (DoubleType))
            .withColumn("trans_time", lit($"trans_time") cast (TimestampType))

          kinesisTransactionDF.createOrReplaceTempView("DynamoDBTempTable")

          spark.sql("select distinct concat(cast(cc_num as string),':',cast(now() as string)) as cc_num_and_trans_time ,cast(first as string) as first," +
            "cast(last as string) as last,cast(trans_num as string) as trans_num,cast(trans_time as string) as trans_time,cast(category as string) as category," +
            "cast(merchant as string) as merchant,amt,merch_lat,merch_long from DynamoDBTempTable").show(false)

          val dynamoDBDF = spark.sql("select distinct concat(cast(cc_num as string),':',cast(now() as string)) as cc_num_and_trans_time ,cast(first as string) as first," +
            "cast(last as string) as last,cast(trans_num as string) as trans_num,cast(trans_time as string) as trans_time,cast(category as string) as category," +
            "cast(merchant as string) as merchant,amt,merch_lat,merch_long from DynamoDBTempTable")

          val schema_ddb = dynamoDBDF.dtypes

          dynamoDBDF.show(false)

          var ddbInsertFormattedRDD = dynamoDBDF.rdd.map(a => {
            val ddbMap = new HashMap[String, AttributeValue]()

            for (i <- 0 to schema_ddb.length - 1) {
              val value = a.get(i)
              if (value != null) {
                val att = new AttributeValue()
                att.setS(value.toString)
                ddbMap.put(schema_ddb(i)._1, att)

                println(schema_ddb(i)._1, att)
              }
            }

            val item = new DynamoDBItemWritable()
            item.setItem(ddbMap)
            println("Item")
            println(item)
            val r = scala.util.Random



            (new Text(r.nextInt.toString), item)

          }
          )

          ddbInsertFormattedRDD.saveAsHadoopDataset(ddbConf)

        }
    }

    // Kick it off
    ssc.start()
    ssc.awaitTermination()



  }

}
