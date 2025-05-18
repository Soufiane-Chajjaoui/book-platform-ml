package org.recommender

import java.time.LocalDateTime
import org.apache.spark.sql.{SparkSession, DataFrame}
import org.apache.spark.sql.functions._
import org.apache.spark.ml.linalg.{Vector, Vectors}
import java.io.{File, PrintWriter}

object Recommend {
  // UDF to compute dot product
  val dotProduct = udf { (v1: Vector, v2: Vector) =>
    v1.toArray.zip(v2.toArray).map { case (x, y) => x * y }.sum
  }

  def logToFileAndConsole(message: String, writer: PrintWriter): Unit = {
    println(message)
    writer.write(message + "\n")
    writer.flush()
  }

  def logData(df: DataFrame, description: String, rows: Int = 100, truncate: Boolean = false, writer: PrintWriter): Unit = {
    logToFileAndConsole(s"\n=== $description Schema ===", writer)
    writer.write(df.schema.treeString + "\n")

    val rowCount = df.count()
    logToFileAndConsole(s"Total rows in $description: $rowCount", writer)

    val outputStream = new java.io.ByteArrayOutputStream()
    Console.withOut(outputStream) {
      df.show(rows, truncate)
    }
    writer.write(s"\n=== $description Content (First $rows rows) ===\n")
    writer.write(outputStream.toString("UTF-8") + "\n")
    writer.flush()
  }

  def main(args: Array[String]): Unit = {
    if (args.length != 4) {
      println("Usage: Recommend <user_id> <n> <input_path> <output_path>")
      sys.exit(1)
    }

    val userId = args(0).toInt
    val n = args(1).toInt
    val inputPath = args(2)
    val outputPath = args(3)

    val writer = new PrintWriter(new File("report.txt"))

    try {
      val spark = SparkSession.builder()
        .appName("BookRecommender")
        .config("spark.driver.memory", "4g")
        .config("spark.executor.memory", "4g")
        .config("spark.executor.heartbeatInterval", "30s")
        .config("spark.network.timeout", "300s")
        .getOrCreate()

      import spark.implicits._

      try {
        logToFileAndConsole(s"`${LocalDateTime.now()}`\nGenerating recommendation report...", writer)

        val booksDF = spark.read.option("header", "true").option("inferSchema", "true").csv(inputPath)
        logData(booksDF, "Books Dataset", 10, truncate = false, writer)

        val userFactorsLike = spark.read.parquet(s"$outputPath/userFactorsLike")
        val itemFactorsLike = spark.read.parquet(s"$outputPath/itemFactorsLike")
        val userFactorsClick = spark.read.parquet(s"$outputPath/userFactorsClick")
        val itemFactorsClick = spark.read.parquet(s"$outputPath/itemFactorsClick")
        val userContentWeights = spark.read.parquet(s"$outputPath/userContentWeights")

        val userLike = userFactorsLike.filter(col("user_id") === userId)
        val userClick = userFactorsClick.filter(col("user_id") === userId)

        val likeScores = userLike.crossJoin(itemFactorsLike)
          .select(
            col("item_id"),
            userLike.col("features").as("user_features"),
            itemFactorsLike.col("features").as("item_features")
          )
          .withColumn("like_score", dotProduct(col("user_features"), col("item_features")).cast("double"))
          .select("item_id", "like_score")

        logData(likeScores, "Like Scores", 10, truncate = false, writer)

        val clickScores = userClick.crossJoin(itemFactorsClick)
          .select(
            col("item_id"),
            userClick.col("features").as("user_features"),
            itemFactorsClick.col("features").as("item_features")
          )
          .withColumn("click_score", dotProduct(col("user_features"), col("item_features")).cast("double"))
          .select("item_id", "click_score")

        logData(clickScores, "Click Scores", 10, truncate = false, writer)

        val weights = userContentWeights.filter(col("id") === userId)
          .select(
            col("weights").getItem(0).as("like_weight"),
            col("weights").getItem(1).as("click_weight")
          )

        val scores = likeScores.join(clickScores, Seq("item_id"))
          .crossJoin(weights)
          .withColumn("score", col("like_score") * col("like_weight") + col("click_score") * col("click_weight"))

        logData(scores, "Composite Scores", 10, truncate = false, writer)

        val recommendations = scores.join(booksDF, col("item_id") === col("isbn_numeric"))
          .select(col("ISBN"), col("score"))
          .orderBy(col("score").desc)
          .limit(n)

        logData(recommendations, "Final Top Recommendations", n, truncate = false, writer)

        recommendations.write.mode("overwrite").csv(s"$outputPath/recommendations")
        logToFileAndConsole("Recommendations generated successfully", writer)

      } catch {
        case e: Exception =>
          logToFileAndConsole(s"Error in Recommend: ${e.getMessage}", writer)
          e.printStackTrace()
      } finally {
        spark.stop()
      }
    } finally {
      writer.close()
    }
  }
}
