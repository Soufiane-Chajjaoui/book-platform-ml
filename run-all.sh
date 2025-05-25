sbt clean
cbt assembly
spark-submit   --class org.recommender.TrainSGD   --master local[*]   ~/IdeaProjects/sysrd-projet/target/scala-2.12/BookSGD-assembly-0.1.jar   file:///home/youcef/IdeaProjects/sysrd-projet/datasets/cleaned_books.csv   file:///home/youcef/IdeaProjects/sysrd-projet/output
spark-submit   --class org.recommender.Recommend   --master local[*]   ~/IdeaProjects/sysrd-projet/target/scala-2.12/BookSGD-assembly-0.1.jar   1 5 file:///home/youcef/IdeaProjects/sysrd-projet/datasets/cleaned_books.csv   file:///home/youcef/IdeaProjects/sysrd-projet/output

