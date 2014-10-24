package com.maqdev

/* todo:
* external actor posts them to the web
* supervising
 */

import java.io.File
import java.sql.{ResultSet, Connection, DriverManager}
import java.util.Date
import java.util.concurrent.TimeUnit

import akka.actor.{ActorRef, Actor}
import akka.event.Logging
import com.typesafe.config._

case class FetcherState(lastId: Option[Int])

class FetchNewMessagesActor(report: ActorRef) extends Actor {

  val log = Logging(context.system, this)
  var state = FetcherState(None)
  val conf = ConfigFactory.load()
  val dbPath = conf.getString("db-path")
  val statePath = conf.getString("state-path") + "/offset.json"

  import play.api.libs.json._
  import play.api.libs.functional.syntax._
  implicit val stateReads = Json.reads[FetcherState]
  implicit val stateWrites = Json.writes[FetcherState]

  override def preStart() {
    val file = new File(statePath)
    if (file.exists()) {
      val source = io.Source.fromFile(file)
      val stateJsonStr = try {
        source.getLines().mkString
      } finally {
        source.close()
      }
      state = Json.fromJson[FetcherState](Json.parse(stateJsonStr)).get
    }

    schedule()
  }

  def saveState() {
    val file = new File(statePath)
    val json = Json.toJson[FetcherState](state)
    val p = new java.io.PrintWriter(file)
    try {
      p.write(json.toString())
    }
    finally {
      p.close()
    }
  }

  override def receive: Receive = {
    case "read" ⇒ {
      try {
        val prevState = state
        val db = new SkypeDb(dbPath)
        try {
          db.fetchMessages(state.lastId).foreach { message ⇒
            report ! message
            state = FetcherState(Some(message.id))
          }
        }
        finally {
          db.close()
        }
        if (state != prevState)
          saveState()
      }
      finally {
        schedule()
      }
    }
  }

  def schedule() = {
    import context.dispatcher
    import scala.concurrent.duration._
    val seconds = conf.getDuration("read-interval", TimeUnit.SECONDS)
    context.system.scheduler.scheduleOnce(FiniteDuration(seconds, TimeUnit.SECONDS), self, "read")
  }
}
