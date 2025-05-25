import org.apache.spark.sql.{SparkSession, DataFrame}
import org.apache.spark.sql.functions._
import scala.util.Random
import scala.collection.mutable

object PersonalizedSGD {
  case class Rating(user: Int, item: Long, rating: Double)

  def main(args: Array[String]): Unit = {
    val spark = SparkSession.builder()
      .appName("PersonalizedBookRecommendationSGD")
      .getOrCreate()

    import spark.implicits._

    // Load dataset
    val raw = spark.read
      .option("header", "true")
      .csv("hdfs://path/to/your/user_book_feedback.csv")
      .select($"user_id".cast("int"), $"isbn_numeric".cast("long"), $"feedback_score".cast("double"))
      .na.drop()
      .as[Rating]
      .cache()

    // Collect distinct users and items
    val users = raw.map(_.user).distinct().collect()
    val items = raw.map(_.item).distinct().collect()

    val numFactors = 10
    val numEpochs = 20
    val learningRate = 0.01
    val reg = 0.1

    // Initialize user and item latent factors
    val userFactors = mutable.Map[Int, Array[Double]]()
    val itemFactors = mutable.Map[Long, Array[Double]]()

    def randomArray(size: Int): Array[Double] =
      Array.fill(size)(Random.nextDouble() * 0.01)

    users.foreach(u => userFactors(u) = randomArray(numFactors))
    items.foreach(i => itemFactors(i) = randomArray(numFactors))

    val ratings = raw.collect()

    // Training loop
    for (epoch <- 1 to numEpochs) {
      Random.shuffle(ratings.toList).foreach { case Rating(user, item, rating) =>
        val userVec = userFactors(user)
        val itemVec = itemFactors(item)

        val prediction = userVec.zip(itemVec).map { case (u, i) => u * i }.sum
        val error = rating - prediction

        // Update vectors
        for (f <- 0 until numFactors) {
          val userGrad = -2 * error * itemVec(f) + 2 * reg * userVec(f)
          val itemGrad = -2 * error * userVec(f) + 2 * reg * itemVec(f)

          userVec(f) -= learningRate * userGrad
          itemVec(f) -= learningRate * itemGrad
        }
      }

      println(s"Epoch $epoch complete.")
    }

    // Predict top-N for each user
    val topN = 5
    val userRecommendations = users.map { user =>
      val scores = items.map { item =>
        val score = userFactors(user).zip(itemFactors(item)).map { case (u, i) => u * i }.sum
        (item, score)
      }
      val topItems = scores.sortBy(-_._2).take(topN)
      (user, topItems)
    }

    // Convert to DataFrame
    val recommendations = userRecommendations.flatMap {
      case (user, items) => items.map { case (item, score) => (user, item, score) }
    }

    val resultDF = spark.createDataFrame(recommendations)
      .toDF("user_id", "isbn_numeric", "predicted_score")

    resultDF.show(20, truncate = false)

    // Save to HDFS if needed
    // resultDF.write.mode("overwrite").parquet("hdfs://path/to/personalized_recommendations")

    spark.stop()
  }
}
