package scala.test.twitter_scrooge

import scala.test.twitter_scrooge.thrift.thrift2.Struct2B
import scala.test.twitter_scrooge.thrift.thrift2.thrift3.Struct3

object JustScrooge2b {
  val classes = Seq(classOf[Struct2B], classOf[Struct3])

  def main(args: Array[String]) {
    classes foreach println
  }
}