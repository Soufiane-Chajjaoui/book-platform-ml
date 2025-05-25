package org.recommender

import org.apache.spark.sql.{SparkSession, DataFrame}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types._
import org.apache.spark.ml.linalg.{Vector, Vectors}
import scala.util.Random

object TrainSGD {
  // UDF to generate random latent factors
  val randomVectorUDF = udf { () =>
    val size = 10 // rank
    val values = Array.fill(size)(Random.nextGaussian() * 0.01)
    Vectors.dense(values)
  }

  // UDF to compute prediction error
  val computeErrorUDF = udf { (userVec: Vector, itemVec: Vector, rating: Float) =>
    val prediction = userVec.toArray.zip(itemVec.toArray).map { case (u, i) => u * i }.sum
    rating - prediction.toFloat
  }

  // UDF to update user/item vector
  val updateVectorUDF = udf { (vec: Vector, otherVec: Vector, error: Float, learningRate: Double, regParam: Double) =>
    val updated = vec.toArray.zip(otherVec.toArray).map { case (v, o) =>
      v + learningRate * (error * o - regParam * v)
    }
    Vectors.dense(updated)
  }

  def main(args: Array[String]): Unit = {
    if (args.length != 2) {
      println("Usage: TrainSGD <input_path> <output_path>")
      sys.exit(1)
    }

    val inputPath = args(0)
    val outputPath = args(1)

    val spark = SparkSession.builder()
      .appName("BookSGD-Trainer")
      .config("spark.driver.memory", "4g")
      .config("spark.executor.memory", "4g")
      .config("spark.executor.heartbeatInterval", "30s")
      .config("spark.network.timeout", "300s")
      .getOrCreate()

    import spark.implicits._

    try {
      // Load cleaned data
      val booksDF = spark.read
        .option("header", "true")
        .option("inferSchema", "true")
        .csv(inputPath)
        .cache()

      println("Schema of cleaned_books.csv:")
      booksDF.printSchema()
      println("Sample data from cleaned_books.csv:")
      booksDF.show(5, false)

      // Prepare rating DataFrames
      val likeDF = booksDF.select(
        col("user_id").cast("int"),
        col("isbn_numeric").cast("long").as("item_id"),
        col("LIKE").cast("float").as("rating")
      ).na.drop()

      val clickDF = booksDF.select(
        col("user_id").cast("int"),
        col("isbn_numeric").cast("long").as("item_id"),
        col("Click").cast("float").as("rating")
      ).na.drop()

      println("Sample likeDF:")
      likeDF.show(5, false)
      println("Sample clickDF:")
      clickDF.show(5, false)

      // SGD parameters
      val rank = 10
      val numIterations = 5
      val learningRate = 0.01
      val regParam = 0.1

      // Initialize user and item factors
      val users = likeDF.select("user_id").union(clickDF.select("user_id")).distinct()
      val items = likeDF.select("item_id").union(clickDF.select("item_id")).distinct()

      var userFactorsLike = users.withColumn("features", randomVectorUDF())
      var itemFactorsLike = items.withColumn("features", randomVectorUDF())
      var userFactorsClick = users.withColumn("features", randomVectorUDF())
      var itemFactorsClick = items.withColumn("features", randomVectorUDF())

      // SGD training loop for LIKE
      println("Training SGD for LIKE")
      for (iter <- 1 to numIterations) {
        // Shuffle ratings
        val shuffledLikeDF = likeDF.orderBy(rand())

        // Join ratings with user and item factors, renaming features to avoid ambiguity
        val trainingDF = shuffledLikeDF
          .join(userFactorsLike.select(col("user_id"), col("features").as("user_features")), Seq("user_id"), "inner")
          .join(itemFactorsLike.select(col("item_id"), col("features").as("item_features")), Seq("item_id"), "inner")
          .withColumn("error", computeErrorUDF(col("user_features"), col("item_features"), col("rating")))

        // Update user factors
        userFactorsLike = trainingDF.groupBy("user_id")
          .agg(
            first("user_features").as("user_features"),
            first("item_features").as("item_features"),
            first("error").as("error")
          )
          .withColumn(
            "features",
            updateVectorUDF(col("user_features"), col("item_features"), col("error"), lit(learningRate), lit(regParam))
          )
          .select("user_id", "features")

        // Update item factors
        itemFactorsLike = trainingDF.groupBy("item_id")
          .agg(
            first("item_features").as("item_features"),
            first("user_features").as("user_features"),
            first("error").as("error")
          )
          .withColumn(
            "features",
            updateVectorUDF(col("item_features"), col("user_features"), col("error"), lit(learningRate), lit(regParam))
          )
          .select("item_id", "features")

        println(s"Iteration $iter completed for LIKE")
      }

      // SGD training loop for Click
      println("Training SGD for Click")
      for (iter <- 1 to numIterations) {
        // Shuffle ratings
        val shuffledClickDF = clickDF.orderBy(rand())

        // Join ratings with user and item factors, renaming features to avoid ambiguity
        val trainingDF = shuffledClickDF
          .join(userFactorsClick.select(col("user_id"), col("features").as("user_features")), Seq("user_id"), "inner")
          .join(itemFactorsClick.select(col("item_id"), col("features").as("item_features")), Seq("item_id"), "inner")
          .withColumn("error", computeErrorUDF(col("user_features"), col("item_features"), col("rating")))

        // Update user factors
        userFactorsClick = trainingDF.groupBy("user_id")
          .agg(
            first("user_features").as("user_features"),
            first("item_features").as("item_features"),
            first("error").as("error")
          )
          .withColumn(
            "features",
            updateVectorUDF(col("user_features"), col("item_features"), col("error"), lit(learningRate), lit(regParam))
          )
          .select("user_id", "features")

        // Update item factors
        itemFactorsClick = trainingDF.groupBy("item_id")
          .agg(
            first("item_features").as("item_features"),
            first("user_features").as("user_features"),
            first("error").as("error")
          )
          .withColumn(
            "features",
            updateVectorUDF(col("item_features"), col("user_features"), col("error"), lit(learningRate), lit(regParam))
          )
          .select("item_id", "features")

        println(s"Iteration $iter completed for Click")
      }

      // Save factors
      println("Saving factors")
      userFactorsLike.write.mode("overwrite").parquet(s"$outputPath/userFactorsLike")
      itemFactorsLike.write.mode("overwrite").parquet(s"$outputPath/itemFactorsLike")
      userFactorsClick.write.mode("overwrite").parquet(s"$outputPath/userFactorsClick")
      itemFactorsClick.write.mode("overwrite").parquet(s"$outputPath/itemFactorsClick")

      // Save userContentWeights
      println("Generating userContentWeights")
      val userContentWeights = likeDF.select("user_id").distinct()
        .withColumn("weights", array(lit(0.7), lit(0.3)))
        .withColumnRenamed("user_id", "id")
      userContentWeights.write.mode("overwrite").parquet(s"$outputPath/userContentWeights")

      println("Training completed successfully")

    } catch {
      case e: Exception =>
        println(s"Error in TrainSGD: ${e.getMessage}")
        e.printStackTrace()
        sys.exit(1)
    } finally {
      spark.stop()
    }
  }
}