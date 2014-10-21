package com.maqdev

import java.io.File
import java.sql.DriverManager

import akka.actor.Actor

case class FetchNext(db: String)

class FetchNewMessages extends Actor {
  override def receive: Receive = {
    case fetchNext: FetchNext ⇒ {

      println("Connecting to " + fetchNext.db)
      val db = DriverManager.getConnection("jdbc:sqlite:" + fetchNext.db)

      try {
        import scala.collection.JavaConversions._

        val stmt = db.createStatement()
        val rs = stmt.executeQuery("select id, convo_id, author, from_dispname, timestamp, body_xml from Messages where id < 50")
        while(rs.next())
        {
          println (rs.getInt("id") + " - " + rs.getString("body_xml"))
        }
      }
      finally {
        db.close()
      }
    }
  }
}
