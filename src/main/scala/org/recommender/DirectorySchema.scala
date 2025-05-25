package org.recommender

import java.io.File

object DirectorySchema {
  def printDirectoryStructure(directoryPath: String, indent: String = ""): Unit = {
    val directory = new File(directoryPath)
    if (directory.exists() && directory.isDirectory) {
      println(s"$indent${directory.getName}/")
      val files = directory.listFiles().sorted
      files.foreach { file =>
        if (file.isDirectory) {
          printDirectoryStructure(file.getAbsolutePath, indent + "  ")
        } else {
          println(s"$indent  ${file.getName}")
        }
      }
    } else {
      println(s"Error: Directory '$directoryPath' does not exist or is not a directory.")
    }
  }

  def main(args: Array[String]): Unit = {
    if (args.length != 1) {
      println("Usage: scala DirectorySchema <parent_directory_path>")
    } else {
      val parentDirectory = args(0)
      println(s"Schema for directory: $parentDirectory")
      printDirectoryStructure(parentDirectory)
    }
  }
}