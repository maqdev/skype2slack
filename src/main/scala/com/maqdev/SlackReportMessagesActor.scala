package com.maqdev

import java.io.File

import akka.actor.Actor
import akka.event.Logging
import com.maqdev.SlackApi.SlackChannel
import com.typesafe.config.ConfigFactory

import scala.collection.mutable
import akka.pattern.pipe

import scala.concurrent.Future


trait SlackCommand
case class AddCmd(from: String, to: String) extends SlackCommand
case class RemoveCmd(name: String) extends SlackCommand
object ListCmd extends SlackCommand

case class ChannelMap(skypeChannelId: Int, slackChannelId: String)


class SlackReportMessagesActor extends Actor {

  val log = Logging(context.system, this)
  val conf = ConfigFactory.load()
  val dbPath = conf.getString("db-path")
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
    case ListCmd ⇒ {
      import context.dispatcher
      channels map {
        case (skypeChats, slackChannels) ⇒
        val skypeChatsMap = skypeChats.map(a ⇒ a.id → a.name).toMap
        val slackChannelsMap = slackChannels.map(a ⇒ a.id → a.name).toMap

        "Here is what i found:\n" +
          channelMap.values.map { c ⇒
            skypeChatsMap.getOrElse(c.skypeChannelId, "@"+c.skypeChannelId) + "-> #" +
              slackChannelsMap.getOrElse(c.slackChannelId, c.slackChannelId)
          }.mkString("\n")
      } pipeTo sender()
    }
    case a: AddCmd ⇒ {
      import context.dispatcher
      channels map {
        case (skypeChats, slackChannels) ⇒
          slackChannels.find(sl ⇒ ("#"+sl.name).compareToIgnoreCase(a.to) == 0).map { slackChannel ⇒
            skypeChats.find(_.name.compareToIgnoreCase(a.from) == 0) map { skypeChat ⇒

              val c = ChannelMap(skypeChat.id, slackChannel.id)
              channelMap.put(c.skypeChannelId, c)
              saveState()

              s"`${a.from}` routed from Skype to #${a.to}"
            } getOrElse {
              s"No such Skype chat: '${a.from}' here is possible values: \n" + skypeChats.map(x ⇒ "`" + x.name + "`").mkString("\n")
            }
          } getOrElse {
            s"No such Slack channel: '${a.to}' here is possible values: \n" + slackChannels.map(x ⇒ "#" + x.name).mkString("\n")
          }
        } recover {
        case error ⇒
          s"Can't add ${a.from} → ${a.to}. Something happen :-("
          log.error(error, " while adding {} to {}", a.from, a.to)
        } pipeTo sender()
    }

    case removeCmd: RemoveCmd ⇒ {
      import context.dispatcher
      channels map {
        case (skypeChats, _) ⇒

          val c = skypeChats.find( sc ⇒ sc.name.compareToIgnoreCase(removeCmd.name) == 0 ||
            ("@" + removeCmd.name) == sc.id.toString)

          c map { chat ⇒
            channelMap.remove(chat.id)
            saveState()
            s"Skype chat `${chat.name}` custom mapping is removed and routed to default channel: #$defaultChannel"
          } getOrElse {
            s"No such Skype chat: '${removeCmd.name}' here is possible values: \n" + skypeChats.map(x ⇒ "`" + x.name + "`").mkString("\n")
          }
      } pipeTo sender()
    }
  }

  def channels: Future[(List[SkypeChat], List[SlackChannel])] = {
    import context.dispatcher
    val f = SlackApi.getChannelsFromSlack
    val db = new SkypeDb(dbPath)
    val skypeChats = try {
      db.getConversations.toList
    }
    finally {
      db.close()
    }
    f map { slackChannels ⇒
      (skypeChats, slackChannels)
    }
  }
}
