package com.maqdev

import akka.util.Timeout
import com.typesafe.config.Config
import spray.http
import spray.routing._
import spray.http._
import MediaTypes._
import akka.actor.Actor
import akka.actor.Props
import scala.concurrent.{Promise, Future}
import scala.concurrent.duration._
import akka.pattern.ask

// we don't implement our route structure directly in the service actor because
// we want to be able to test it independently, without having to spin up an actor
class MyServiceActor extends Actor with HttpService {
  import scala.concurrent.ExecutionContext.Implicits.global

  import com.typesafe.config._
  val conf = ConfigFactory.load()
  val dbPath = conf.getString("db-path")

  val reporter = context.system.actorOf(Props[SlackReportMessagesActor])
  val fetcher = context.system.actorOf(Props(classOf[FetchNewMessagesActor], reporter))

  // the HttpService trait defines only one abstract member, which
  // connects the services environment to the enclosing actor or test
  def actorRefFactory = context

  // this actor only runs our route, but you could add
  // other things here, like request stream processing
  // or timeout handling
  def receive = runRoute(myRoute)

  val myRoute =
    path("") {
      get {
        respondWithMediaType(`text/html`) {
          complete {
            <html>Yo!</html>
          }
        }
      }
    } ~
      path("command") {
        post {
          entity(as[FormData]) {
            data ⇒
              val map = data.fields.toMap
              val token = map.getOrElse("token", "")
              if (token != conf.getString("slack-incoming-token")) {
                complete {
                  HttpResponse(StatusCodes.Unauthorized, HttpEntity(ContentType(`text/html`), "Unauthorized"))
                }
              }
              else {
                val text = map.getOrElse("text", "")
                implicit val timeout = Timeout(60 seconds)
                val response: Future[Any] = text match {
                  case "list" ⇒ reporter ? ListCmd
                  case add@r"""add (.+)$from->(.+)$to""" ⇒ reporter ? AddCmd(from.trim, to.trim)
                  case remove@r"""remove (.+)$channel""" ⇒ reporter ? RemoveCmd(channel.trim)
                  case _ ⇒ Promise.successful("Please use one of the following commands:\n - list'\n - 'add {from}->{to}'\n - 'remove {skype-channel}'").future
                }

                detach() {
                  complete {
                    response map {
                      result =>
                        HttpResponse(StatusCodes.OK, HttpEntity(ContentType(`text/plain`), result.toString))
                    }
                  }
                }
              }
          }
        }
      }

  implicit class Regex(sc: StringContext) {
    def r = new util.matching.Regex(sc.parts.mkString, sc.parts.tail.map(_ => "x"): _*)
  }
}
