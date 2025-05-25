import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types._

object Recommender {
  def main(args: Array[String]): Unit = {
    if (args.length != 2) {
      println("Usage: Recommender <user_id> <top_n>")
      sys.exit(1)
    }

    val userId = args(0).toInt
    val topN = args(1).toInt

    val spark = SparkSession.builder()
      .appName("Book Recommender")
      .getOrCreate()

    import spark.implicits._

    // Load item factors
    val itemDF = spark.read
      .option("header", "true")
      .csv("/user/hduser/outputs/item_factors")
      .withColumn("factors", split(col("factors"), ",").cast("array<float>"))
      .withColumn("id", col("id").cast("long"))

    // Load user factors
    val userDF = spark.read
      .option("header", "true")
      .csv("/user/hduser/outputs/user_factors")
      .withColumn("factors", split(col("factors"), ",").cast("array<float>"))
      .withColumn("id", col("id").cast("long"))
      .filter(col("id") === userId)

    // Get the user's factor vector
    val userVec = userDF.select("factors").as[Array[Float]].head()

    // Broadcast user vector
    val userVecBroadcast = spark.sparkContext.broadcast(userVec)

    // UDF to compute dot product
    val dotProductUDF = udf((itemVec: Seq[Float]) => {
      val userVec = userVecBroadcast.value
      itemVec.zip(userVec).map { case (i, u) => i * u }.sum
    })

    // Compute scores
    val scoredItems = itemDF.withColumn("score", dotProductUDF(col("factors")))

    // Show top N items
    println(s"\nTop $topN recommended books for user $userId:")
    scoredItems.orderBy(desc("score")).select("id", "score").show(topN, truncate = false)

    spark.stop()
  }
}

