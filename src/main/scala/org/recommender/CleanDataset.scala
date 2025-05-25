package org.recommender

import org.apache.spark.sql.{SparkSession, DataFrame}
import org.apache.spark.sql.functions._

object CleanDataset {
  def main(args: Array[String]): Unit = {
    if (args.length != 2) {
      println("Usage: CleanDataset <input_path> <output_path>")
      sys.exit(1)
    }

    val inputPath = args(0)
    val outputPath = args(1)

    val spark = SparkSession.builder()
      .appName("BookDatasetCleaner")
      .config("spark.driver.memory", "4g")
      .config("spark.executor.memory", "4g")
      .config("spark.executor.heartbeatInterval", "30s")
      .config("spark.network.timeout", "300s")
      .getOrCreate()

    import spark.implicits._

    try {
      // Load raw data
      val rawDF = spark.read
        .option("header", "true")
        .option("inferSchema", "true")
        .csv(inputPath)

      // Debug: Print schema and columns
      println("Schema of new_books.csv:")
      rawDF.printSchema()
      println("Columns: " + rawDF.columns.mkString(", "))
      val rawCount = rawDF.count()
      println(s"Raw row count: $rawCount")

      // Debug: Sample raw data
      println("Sample data from new_books.csv:")
      rawDF.show(5, false)

      // Filter non-null ISBN and user_id
      val filteredDF = rawDF
        .filter(col("ISBN").isNotNull && col("user_id").isNotNull)

      // Debug: Count after filtering
      val filteredCount = filteredDF.count()
      println(s"Row count after ISBN and user_id filter: $filteredCount")

      // Process data
      val cleanedDF = filteredDF
        .withColumn("isbn_numeric", regexp_replace(col("ISBN"), "[^0-9]", "").cast("long"))
        .withColumn("LIKE", when(col("LIKE").isNotNull, col("LIKE").cast("float")).otherwise(0.0))
        .withColumn("Click", when(col("Click").isNotNull, col("Click").cast("float")).otherwise(0.0))
        .select("ISBN", "user_id", "isbn_numeric", "LIKE", "Click")

      // Debug: Check isbn_numeric nulls
      val nullISBNCount = cleanedDF.filter(col("isbn_numeric").isNull).count()
      println(s"Rows with null isbn_numeric: $nullISBNCount")

      // Drop rows with null isbn_numeric
      val finalDF = cleanedDF.filter(col("isbn_numeric").isNotNull)

      // Debug: Print schema, sample data, and row count
      println("Schema of cleanedDF:")
      finalDF.printSchema()
      println("Sample data from cleanedDF:")
      finalDF.show(5, false)
      val finalCount = finalDF.count()
      println(s"Final row count: $finalCount")

      // Save cleaned data with headers
      finalDF.write
        .option("header", "true")
        .mode("overwrite")
        .csv(outputPath)

      println("Dataset cleaned and saved successfully")

    } catch {
      case e: Exception =>
        println(s"Error in CleanDataset: ${e.getMessage}")
        e.printStackTrace()
        sys.exit(1)
    } finally {
      spark.stop()
    }
  }
}