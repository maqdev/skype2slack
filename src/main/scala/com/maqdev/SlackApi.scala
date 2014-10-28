package com.maqdev

import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory
import play.api.libs.json.{JsValue, Json, Reads}
import spray.client.pipelining._
import spray.http.Uri

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.util.matching._
import java.util.concurrent.ConcurrentHashMap

object SlackApi {
  import scala.concurrent.ExecutionContext.Implicits.global
  import akka.event.Logging
  implicit val system = Boot.system
  val log = Logging.getLogger(system, this)

  val conf = ConfigFactory.load()
  val slackAuthToken = conf.getString("slack-auth-token")
  val slackIncomingToken = conf.getString("slack-incoming-token")
  val botName = conf.getString("bot-name")

  val htmlEntities = Map("lt" → "<", "gt" → ">", "amp" → "&", "quot" → "\"", "apos" → "'") map { case (s, c) ⇒ c → ("&%s;" format s) }

  var slackChannels = new ConcurrentHashMap[String, (String, String)]();

  def postMessageToSlack(m: SkypeMessage, slackChannelId: String) = {
    val strippedMessage = """<[^< ][^>]+?>""".r replaceAllIn (m.message replaceAllLiterally ("<quote", "> <quote"), "")
    val sanitizedMessage = htmlEntities.foldLeft(strippedMessage) { case (result, (chr, ent)) ⇒ result replaceAllLiterally (ent, chr) }

    if (slackChannels.get(slackChannelId) != null) {
      slackRequest[SlackChannelsInfoResult]("channels.info", Map(
        "channel" → slackChannelId
      )) map { result =>
        slackChannels.put(slackChannelId, (result.channel.purpose.value, ""))
      }
    }

    slackRequest[SlackChatResult]("chat.postMessage", Map(
      "channel" → slackChannelId,
      "username" → s"${m.authorName} @ Skype",
      "icon_url" → s"http://api.skype.com/users/${m.author}/profile/avatar",
      "parse" → s"full",
      "link_names" → s"1",
      "unfurl_links" → s"true",
      "unfurl_media" → s"true",
      "text" → sanitizedMessage
    )) map { response ⇒
      Option(slackChannels.get(response.channel)) foreach { case (uri, ts) ⇒
        if (ts.nonEmpty) {
          slackRequest[SlackChatResult]("chat.delete", Map(
            "channel" → response.channel,
            "ts" → ts
          ))
        }

        if (uri.nonEmpty) {
          slackRequest[SlackChatResult]("chat.postMessage", Map(
            "channel" → response.channel,
            "username" → "SkypeBot",
            "icon_url" → "http://en.gravatar.com/userimage/6208733/1129353ced07ece051aefd7e63d8b2f8.jpg",
            "text" → uri
          )) map { result =>
            slackChannels.put(slackChannelId, (uri, result.ts))
          }
        }
      }
    }
  }

  def getChannelsFromSlack: Future[List[SlackChannelInfo]] = {
    slackRequest[SlackChannelsListResult]("channels.list") map (_.channels)
  }

  def getChannelsFromSlackSync: List[SlackChannelInfo] = {
    // todo: remove sync version
    Await.result(getChannelsFromSlack, 30 seconds)
  }

  class SlackException(json: JsValue) extends Exception("Slack call failed: " + json.toString())

  trait SlackRequestResult {
    val ok: Boolean = false
  }

  case class SlackChannelPurpose(value: String)
  case class SlackChannelInfo(id: String, name: String, purpose: SlackChannelPurpose)

  case class SlackChannelsListResult(channels: List[SlackChannelInfo]) extends SlackRequestResult
  case class SlackChannelsInfoResult(channel: SlackChannelInfo) extends SlackRequestResult
  case class SlackChatResult(channel: String, ts: String) extends SlackRequestResult

  implicit val slackChannelPurposeReads = Json.reads[SlackChannelPurpose]
  implicit val slackChannelInfo = Json.reads[SlackChannelInfo]

  implicit val slackChannelsListResultReads = Json.reads[SlackChannelsListResult]
  implicit val slackChannelsInfoResultReads = Json.reads[SlackChannelsInfoResult]
  implicit val slackChatResultReads = Json.reads[SlackChatResult]

  def slackRequest[T <: SlackRequestResult : Reads](method: String, data: Map[String,String] = Map()): Future[T] = {

    val uri = Uri(s"https://slack.com/api/$method").withQuery(
      Map("token" → slackAuthToken) ++ data
    )

    log.debug("--> slack: {}", uri)

    val pipeline = sendReceive ~> unmarshal[String]

    pipeline {
      Get(uri)
    } map { s ⇒
      log.debug("<-- slack: {} ", s)

      val json = Json.parse(s)
      val r = Json.fromJson[T](json).get

      if (r.ok) {
        r
      }
      else {
        throw new SlackException(json)
      }
    }
  }
}
