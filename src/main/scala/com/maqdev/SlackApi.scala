package com.maqdev

import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory
import play.api.libs.json.{JsValue, Json}
import spray.client.pipelining._
import spray.http.Uri

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.util.matching._

object SlackApi {
  import scala.concurrent.ExecutionContext.Implicits.global
  import akka.event.Logging
  implicit val system = Boot.system
  val log = Logging.getLogger(system, this)

  val conf = ConfigFactory.load()
  val slackAuthToken = conf.getString("slack-auth-token")
  val slackIncomingToken = conf.getString("slack-incoming-token")
  val botName = conf.getString("bot-name")

  def postMessageToSlack(m: SkypeMessage, slackChannelId: String) = {
    val sanitizedMessage = """<[^< ][^>]+?>""".r replaceAllIn (m.message replaceAll ("<quote", "> <quote"), "")
    
    slackRequest("chat.postMessage", Map(
      "channel" → slackChannelId,
      "username" → s"${m.authorName} @ Skype",
      "icon_url" → s"http://api.skype.com/users/${m.author}/profile/avatar",
      "parse" → s"full",
      "link_names" → s"1", 
      "unfurl_links" → s"true", 
      "unfurl_media" → s"true",
      "text" → sanitizedMessage
    ))
  }

  case class SlackChannel(id: String, name: String)
  def getChannelsFromSlack: Future[List[SlackChannel]] = {
    implicit val slackChannelReads = Json.reads[SlackChannel]

    slackRequest("channels.list") map {
      json ⇒
        val channelsJson = json \ "channels"
        Json.fromJson[List[SlackChannel]](channelsJson).get
    }
  }

  def getChannelsFromSlackSync: List[SlackChannel] = {
    // todo: remove sync version
    Await.result(getChannelsFromSlack, 30 seconds)
  }

  class SlackException(json: JsValue) extends Exception("Slack call failed: " + json.toString())
  case class SlackRequestResult(ok: Boolean)

  def slackRequest(method: String, data: Map[String,String] = Map()): Future[JsValue] = {
    implicit val slackRequestResultReads = Json.reads[SlackRequestResult]

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
      val r = Json.fromJson[SlackRequestResult](json).get
      if (r.ok) {
        json
      }
      else {
        throw new SlackException(json)
      }
    }
  }
}
