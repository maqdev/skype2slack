package com.maqdev

/* todo:

* load and save state (json: https://github.com/spray/spray-json)
* schedule gathering http://doc.akka.io/docs/akka/snapshot/scala/howto.html
* send messages to external actor
* external actor posts them to the web
* supervising
 */

import java.io.File
import java.sql.{Connection, DriverManager}

import akka.actor.Actor

case class FetchNext(db: String)

class FetchNewMessagesActor extends Actor {

  var lastId: Option[Int] = None
  var lastEditedTimestamp : Option[Int] = None

  override def preStart() {

  }

  def generateStmt(con: Connection) = {
    val params: List[(String,Int)] = List(
      lastId.map(id ⇒ ("id > ?", id)),
      lastEditedTimestamp.map(ts ⇒ ("edited_timestamp >?", ts))
    ).flatten

    val where = params match {
      case head :: tail ⇒ "where " + head._1 + tail.map(t ⇒ " or " + t._1)
      case _ ⇒ ""
    }
    val p = con.prepareStatement("select id, convo_id, author, from_dispname, timestamp, edited_timestamp, body_xml from Messages " + where)
    params.zipWithIndex.foreach(i ⇒ p.setInt(i._2, i._1._2))
    p
  }

  override def receive: Receive = {
    case fetchNext: FetchNext ⇒ {

      println("Connecting to " + fetchNext.db)
      val db = DriverManager.getConnection("jdbc:sqlite:" + fetchNext.db)

      try {
        import scala.collection.JavaConversions._

        val stmt = generateStmt(db)
        val rs = stmt.executeQuery()
        while(rs.next())
        {
          println (rs.getInt("id") + " - " + rs.getString("body_xml") + " - " + rs.getString("timestamp") + " - " + rs.getString("edited_timestamp"))
        }
      }
      finally {
        db.close()
      }
    }
  }
}
