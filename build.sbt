name := "BookSGD"
version := "0.1"
scalaVersion := "2.12.20"
ThisBuild / organization := "com.sysrd"
libraryDependencies ++= Seq(
  "org.apache.spark" %% "spark-core" % "3.5.5" % Provided,
  "org.apache.spark" %% "spark-sql" % "3.5.5" % Provided,
  "org.apache.spark" %% "spark-mllib" % "3.5.5" % Provided
)
excludeDependencies ++= Seq(
  ExclusionRule("org.apache.hadoop")
)
assembly / assemblyMergeStrategy := {
  case PathList("META-INF", xs @ _*) => MergeStrategy.discard
  case PathList("org", "apache", "spark", "unused", _*) => MergeStrategy.first
  case "reference.conf" => MergeStrategy.concat
  case "application.conf" => MergeStrategy.concat
  case _ => MergeStrategy.first
}