package org.recommender

import org.apache.spark.sql.SparkSession
import scala.util.Random

object BookSGD {

  case class Rating(user: Int, item: Int, rating: Double)

  def parseRating(line: String): Rating = {
    val fields = line.split(",").map(_.trim.replaceAll("'", ""))
    if (fields.length != 3 || fields.exists(_.isEmpty)) {
      throw new IllegalArgumentException(s"Invalid line: $line")
    }

    val userId = fields(0).toInt
    val itemId = fields(1).toLong
    val rating = fields(2).toDouble

    Rating(userId, itemId.toInt, rating)
  }

  def main(args: Array[String]): Unit = {
    val spark = SparkSession.builder()
      .appName("Book Recommendation using SGD")
      .getOrCreate()

    val sc = spark.sparkContext

    val ratingsPath = "hdfs://localhost:9000/user/hduser/datasets/ratings.csv"
    val data = sc.textFile(ratingsPath)

    val ratings = data
      .filter(line => line.trim.nonEmpty && !line.startsWith("user"))
      .map(parseRating)
      .cache()

    val rank = 10
    val numIterations = 20
    val learningRate = 0.01
    val lambda = 0.1

    var userFactors = ratings.map(_.user).distinct().map(id => (id, Array.fill(rank)(Random.nextDouble())))
    var itemFactors = ratings.map(_.item).distinct().map(id => (id, Array.fill(rank)(Random.nextDouble())))

    for (iter <- 1 to numIterations) {
      val userMap = sc.broadcast(userFactors.collectAsMap())
      val itemMap = sc.broadcast(itemFactors.collectAsMap())

      val updates = ratings.map { case Rating(user, item, rating) =>
        val userVec = userMap.value(user)
        val itemVec = itemMap.value(item)

        val prediction = userVec.zip(itemVec).map { case (u, i) => u * i }.sum
        val err = rating - prediction

        val updatedUserVec = userVec.zip(itemVec).map { case (u, i) =>
          u + learningRate * (err * i - lambda * u)
        }

        val updatedItemVec = itemVec.zip(userVec).map { case (i, u) =>
          i + learningRate * (err * u - lambda * i)
        }

        ((user, updatedUserVec), (item, updatedItemVec))
      }

      userFactors = updates.map(_._1).reduceByKey((a, b) => a.zip(b).map { case (x, y) => (x + y) / 2 })
      itemFactors = updates.map(_._2).reduceByKey((a, b) => a.zip(b).map { case (x, y) => (x + y) / 2 })

      println(s"Iteration $iter completed")
    }

    // Save to HDFS in readable format
    userFactors.map { case (id, vec) => s"$id,${vec.mkString(",")}" }
      .saveAsTextFile("hdfs://localhost:9000/user/hduser/outputs/user_factors")

    itemFactors.map { case (id, vec) => s"$id,${vec.mkString(",")}" }
      .saveAsTextFile("hdfs://localhost:9000/user/hduser/outputs/item_factors")

    spark.stop()
  }
}

