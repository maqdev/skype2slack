package com.maqdev

import java.io.File
import java.util.Date

import akka.actor.Actor
import akka.event.Logging
import com.maqdev.SlackApi.SlackChannel
import com.typesafe.config.ConfigFactory

import scala.collection.mutable

case class SkypeMessage(id: Int, author: String, authorName: String, channelName: String, channelId: Int, message: String, sent: Date, edited: Date)

trait SlackCommand
case class AddCmd(map: ChannelMap) extends SlackCommand
case class RemoveCmd(skypeChannelId: Int) extends SlackCommand
object ListCmd extends SlackCommand

case class ChannelMap(skypeChannelId: Int, slackChannelId: String)


class SlackReportMessagesActor extends Actor {

  val log = Logging(context.system, this)
  val conf = ConfigFactory.load()
  val statePath = conf.getString("state-path") + "/channels.json"
  val botName = conf.getString("bot-name")
  val defaultChannel = conf.getString("default-channel")
  var defaultChannelId: Option[String] = None
  val channelMap = new mutable.HashMap[Int, ChannelMap]

  import play.api.libs.json._
  implicit val stateReads = Json.reads[ChannelMap]
  implicit val stateWrites = Json.writes[ChannelMap]

  override def preStart() {
    val file = new File(statePath)
    if (file.exists()) {
      val source = io.Source.fromFile(file)
      val channelsJson = try {
        source.getLines().mkString
      } finally {
        source.close()
      }
      val list = Json.fromJson[List[ChannelMap]](Json.parse(channelsJson)).get
      channelMap ++= list.map{c ⇒ c.skypeChannelId → c}
    }

    defaultChannelId = SlackApi.getChannelsFromSlackSync.collect {
      case x: SlackChannel if x.name.compareToIgnoreCase(defaultChannel) == 0 ⇒ x.id
    }.headOption
  }

  def saveState() {
    val file = new File(statePath)
    val json = Json.toJson[List[ChannelMap]](channelMap.values.toList)
    val p = new java.io.PrintWriter(file)
    try {
      p.write(json.toString())
    }
    finally {
      p.close()
    }
  }

  override def receive: Receive = {
    case m: SkypeMessage ⇒ {
      val c: Option[String] = channelMap.get(m.channelId).map(_.slackChannelId).orElse(defaultChannelId)
      c.map {
        channel ⇒ SlackApi.postMessageToSlack(m, channel)
      }.getOrElse {
        log.error("Slack channel not found, message is lost: {}", m)
      }
    }
    case ListCmd ⇒ sender() ! channelMap.values.toList
    case a: AddCmd ⇒ {
      channelMap += a.map.skypeChannelId → a.map
      saveState()
    }
    case r: RemoveCmd ⇒ {
      this.channelMap.remove(r.skypeChannelId)
      saveState()
    }
  }
}
