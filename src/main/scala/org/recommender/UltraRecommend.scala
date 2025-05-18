package org.recommender

import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.functions._
import org.apache.spark.ml.linalg.{Vector, Vectors}
import org.apache.spark.sql.expressions.UserDefinedFunction

import java.io.{File, PrintWriter}

object UltraRecommend {
  // UDF to compute dot product of two vectors (for logged-in users)
  val dotProduct: UserDefinedFunction = udf { (v1: Vector, v2: Vector) =>
    v1.toArray.zip(v2.toArray).map { case (x, y) => x * y }.sum
  }

  // Utility to capture console output and write to file
  def logToFileAndConsole(message: String, writer: PrintWriter): Unit = {
    println(message)
    writer.println(message)
  }

  def logSchema(df: DataFrame, name: String, writer: PrintWriter): Unit = {
    val schemaStr = s"## Schema of $name\n```\n${df.schema.treeString}\n```"
    logToFileAndConsole(schemaStr, writer)
  }

  def logData(df: DataFrame, description: String, rows: Int, truncate: Boolean, writer: PrintWriter): Unit = {
    val header = df.columns.mkString("|")
    val separator = df.columns.map(_ => "---").mkString("|")
    val rowsStr = df.take(rows).map(_.mkString("|")).mkString("\n")
    val showStr = s"## $description\n\n|$header|\n|$separator|\n$rowsStr\n"
    logToFileAndConsole(showStr, writer)
  }

  def main(args: Array[String]): Unit = {
    // Determine mode based on number of arguments
    val isLoggedIn = args.length == 4
    if (args.length != 3 && args.length != 4) {
      println("Usage: Recommend <user_id> <n> <input_path> <output_path> (for logged-in users)")
      println("   or: Recommend <n> <input_path> <output_path> (for non-logged-in users)")
      sys.exit(1)
    }

    // Parse arguments
    val (userId, n, inputPath, outputPath) = if (isLoggedIn) {
      val userId = try {
        args(0).toInt
      } catch {
        case e: NumberFormatException =>
          println(s"Error: user_id '${args(0)}' is not a valid integer")
          sys.exit(1)
      }
      val n = try {
        args(1).toInt
      } catch {
        case e: NumberFormatException =>
          println(s"Error: n '${args(1)}' is not a valid integer")
          sys.exit(1)
      }
      (Some(userId), n, args(2), args(3))
    } else {
      val n = try {
        args(0).toInt
      } catch {
        case e: NumberFormatException =>
          println(s"Error: n '${args(0)}' is not a valid integer")
          sys.exit(1)
      }
      (None, n, args(1), args(2))
    }

    // Set output directory based on mode
    val outputDir = if (isLoggedIn) s"$outputPath/logged_in" else s"$outputPath/non_logged_in"
    val reportFile = new File(s"$outputDir/recommendations/report.txt")
    println(s"Attempting to create report file at: ${reportFile.getAbsolutePath}")

    // Verify and create parent directory
    val parentDir = reportFile.getParentFile
    if (!parentDir.exists() && !parentDir.mkdirs()) {
      println(s"Error: Failed to create directory: ${parentDir.getAbsolutePath}")
      sys.exit(1)
    }

    val writer = try {
      new PrintWriter(reportFile)
    } catch {
      case e: Exception =>
        println(s"Error: Failed to create PrintWriter for report.txt: ${e.getMessage}")
        sys.exit(1)
    }

    try {
      writer.println(if (isLoggedIn) s"Book Recommendation Report for user_id=${userId.get}" else "Book Recommendation Report for Non-Logged-In User")
      writer.println(s"Generated for n=$n\n")
      writer.flush()

      println(s"Arguments: ${if (isLoggedIn) s"userId=${userId.get}, " else ""}n=$n, inputPath=$inputPath, outputPath=$outputPath")

      val spark = SparkSession.builder()
        .appName(if (isLoggedIn) "BookRecommender" else "BookRecommenderNonLoggedIn")
        .config("spark.driver.memory", "4g")
        .config("spark.executor.memory", "4g")
        .config("spark.executor.heartbeatInterval", "30s")
        .config("spark.network.timeout", "300s")
        .getOrCreate()

      import spark.implicits._

      try {
        // Load input data
        val booksDF = spark.read
          .option("header", "true")
          .option("inferSchema", "true")
          .csv(inputPath)
          .cache()

        logSchema(booksDF, "cleaned_books.csv", writer)
        logData(booksDF, "Sample data from cleaned_books.csv", 5, truncate = false, writer)
        writer.flush()

        // Load item factors (and user factors for logged-in mode)
        val itemFactorsLike = spark.read.parquet(s"$outputDir/itemFactorsLike")
        val itemFactorsClick = spark.read.parquet(s"$outputDir/itemFactorsClick")

        // Debug: Print item factor schemas
        logSchema(itemFactorsLike, "itemFactorsLike", writer)
        logData(itemFactorsLike, "Sample itemFactorsLike", 5, truncate = false, writer)
        logSchema(itemFactorsClick, "itemFactorsClick", writer)
        logData(itemFactorsClick, "Sample itemFactorsClick", 5, truncate = false, writer)
        writer.flush()

        val recommendations = if (isLoggedIn) {
          // Logged-in user logic
          val userFactorsLike = spark.read.parquet(s"$outputDir/userFactorsLike")
          val userFactorsClick = spark.read.parquet(s"$outputDir/userFactorsClick")
          val userContentWeights = spark.read.parquet(s"$outputDir/userContentWeights")

          // Debug: Print user factor schemas
          logSchema(userFactorsLike, "userFactorsLike", writer)
          logData(userFactorsLike.filter(col("user_id") === userId.get), s"Sample userFactorsLike for user_id=${userId.get}", 1, truncate = false, writer)
          logSchema(userFactorsClick, "userFactorsClick", writer)
          logData(userFactorsClick.filter(col("user_id") === userId.get), s"Sample userFactorsClick for user_id=${userId.get}", 1, truncate = false, writer)
          logSchema(userContentWeights, "userContentWeights", writer)
          logData(userContentWeights.filter(col("id") === userId.get), s"Sample userContentWeights for user_id=${userId.get}", 1, truncate = false, writer)
          writer.flush()

          // Filter for the specified user
          val userLike = userFactorsLike.filter(col("user_id") === userId.get)
          val userClick = userFactorsClick.filter(col("user_id") === userId.get)

          // Compute like_score
          val likeCrossJoin = userLike.crossJoin(itemFactorsLike)
          logSchema(likeCrossJoin, "likeCrossJoin", writer)
          logData(likeCrossJoin, "Sample likeCrossJoin", 5, truncate = false, writer)

          val likeScores = likeCrossJoin
            .select(
              col("item_id"),
              userLike.col("features").as("user_features"),
              itemFactorsLike.col("features").as("item_features")
            )
            .withColumn("like_score", dotProduct(col("user_features"), col("item_features")).cast("double"))
            .select("item_id", "like_score")

          // Debug: Verify likeScores
          logData(likeScores, "Sample likeScores", 5, truncate = false, writer)
          writer.flush()

          // Compute click_score
          val clickCrossJoin = userClick.crossJoin(itemFactorsClick)
          logSchema(clickCrossJoin, "clickCrossJoin", writer)
          logData(clickCrossJoin, "Sample clickCrossJoin", 5, truncate = false, writer)

          val clickScores = clickCrossJoin
            .select(
              col("item_id"),
              userClick.col("features").as("user_features"),
              itemFactorsClick.col("features").as("item_features")
            )
            .withColumn("click_score", dotProduct(col("user_features"), col("item_features")).cast("double"))
            .select("item_id", "click_score")

          // Debug: Verify clickScores
          logData(clickScores, "Sample clickScores", 5, truncate = false, writer)
          writer.flush()

          // Get user weights
          val weights = userContentWeights.filter(col("id") === userId.get)
            .select(col("weights").getItem(0).as("like_weight"), col("weights").getItem(1).as("click_weight"))

          logData(weights, s"User weights for user_id=${userId.get}", 1, truncate = false, writer)
          writer.flush()

          // Combine scores with weights
          val scores = likeScores.join(clickScores, Seq("item_id"), "inner")
            .crossJoin(weights)
            .withColumn("score", col("like_score") * col("like_weight") + col("click_score") * col("click_weight"))

          // Join with booksDF to get ISBN
          scores.join(booksDF, col("item_id") === col("isbn_numeric"), "inner")
            .select(col("ISBN"), col("score"))
            .orderBy(col("score").desc)
            .limit(n)
        } else {
          // Non-logged-in user logic
          // Compute average item scores (popularity-based)
          val likeScores = itemFactorsLike
            .withColumn("like_score", expr("aggregate(features, 0.0, (acc, x) -> acc + x * x)"))
            .select(col("item_id"), col("like_score"))

          val clickScores = itemFactorsClick
            .withColumn("click_score", expr("aggregate(features, 0.0, (acc, x) -> acc + x * x)"))
            .select(col("item_id"), col("click_score"))

          // Debug: Verify scores
          logData(likeScores, "Sample likeScores", 5, truncate = false, writer)
          logData(clickScores, "Sample clickScores", 5, truncate = false, writer)
          writer.flush()

          // Combine scores with default weights (0.7 for like, 0.3 for click)
          val scores = likeScores.join(clickScores, Seq("item_id"), "inner")
            .withColumn("score", col("like_score") * lit(0.7) + col("click_score") * lit(0.3))

          // Join with booksDF to get ISBN
          scores.join(booksDF, col("item_id") === col("isbn_numeric"), "inner")
            .select(col("ISBN"), col("score"))
            .orderBy(col("score").desc)
            .limit(n)
        }

        // Debug: Print recommendations
        logData(recommendations, s"Top $n recommendations", n, truncate = false, writer)
        writer.flush()

        // Save recommendations
        recommendations.write.mode("overwrite").csv(s"$outputDir/recommendations")

        logToFileAndConsole("Recommendations generated successfully", writer)
        writer.flush()

      } catch {
        case e: Exception =>
          logToFileAndConsole(s"Error in Recommend: ${e.getMessage}", writer)
          e.printStackTrace()
          sys.exit(1)
      } finally {
        spark.stop()
      }
    } finally {
      writer.flush()
      writer.close()
      println(s"Report file closed: ${reportFile.getAbsolutePath}")
    }
  }
}