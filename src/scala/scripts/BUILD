load("//scala:scala.bzl", "scala_binary")

scala_binary(
  name = "generator",
  srcs = ["TwitterScroogeGenerator.scala"],
  main_class = "scripts.ScroogeGenerator",
  deps = [
    "@scrooge_generator//jar",
  ],
  visibility = ["//visibility:public"],
)