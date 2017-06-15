package io.iguaz.tcp2igz

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}
import scala.io.StdIn
import scala.util.Try

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpHeader.ParsingResult.Ok
import akka.http.scaladsl.model._
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Tcp.OutgoingConnection
import akka.stream.scaladsl.{Flow, Sink, Source, Tcp}
import akka.util.ByteString
import akka.{Done, NotUsed}
import com.typesafe.config.{ConfigFactory, ConfigList}
import play.api.libs.json.{JsObject, JsString, Json}

object Main {

  def main(args: Array[String]): Unit = {
    implicit val system = ActorSystem()
    implicit val materializer = ActorMaterializer()
    implicit val executionContext = system.dispatcher
    implicit val config = ConfigFactory.load()

    val sourceHost = config.getString("source.host")
    val sourcePort = config.getInt("source.port")

    val targetHost = config.getString("target.host")
    val targetPort = config.getInt("target.port")
    val targetContainer = config.getString("target.container")
    val targetTable = config.getString("target.table")

    val uri = Uri(s"http://$targetHost:$targetPort/$targetContainer/$targetTable/")
    val headers = List(HttpHeader.parse("X-v3io-function", "PutItem").asInstanceOf[Ok].header)

    val printPeriod = config.getInt("print-period")

    val fields = config.getList("fields").asScala.toList.map { configValue =>
      val list = configValue.asInstanceOf[ConfigList].asScala.map(_.unwrapped().toString)
      list(0) -> list(1)
    }

    println(s"${DateTime.now}: App started")

    val clientFlow: Flow[HttpRequest, (Try[HttpResponse], Long), NotUsed] = Flow[HttpRequest]
      .zipWithIndex
      .via(Http().cachedHostConnectionPool[Long](targetHost, targetPort))

    val printSink: Sink[(Try[HttpResponse], Long), Future[Done]] = Sink.foreach { case (response, i) =>
      if (i % printPeriod == 0) {
        println(s"${DateTime.now}: $i | $response")
      }
    }

    val conversionFlow: Flow[ByteString, HttpRequest, NotUsed] = Flow[ByteString]
      .statefulMapConcat(() => conversionFunction)
      .map { values =>
        val body = Json.obj(
          "Key" -> Json.obj("id" -> Json.obj("S" -> JsString(values.head))),
          "Item" -> JsObject(values.zip(fields).map { case (value, (name, igzType)) =>
            name -> Json.obj(igzType -> JsString(value))
          })
        ).toString

        HttpRequest(
          method = HttpMethods.PUT,
          uri = uri,
          headers = headers,
          entity = HttpEntity(ContentTypes.`application/json`, body)
        )
      }

    val bindingFuture: Future[OutgoingConnection] = Source.fromIterator(() => Iterator.continually(ByteString()))
      .viaMat(Tcp().outgoingConnection(host = sourceHost, port = sourcePort)) { case (_, mat2) => mat2 }
      .via(conversionFlow)
      .via(clientFlow)
      .toMat(printSink) { case (mat1, _) => mat1 }
      .run()

    bindingFuture.failed.foreach(_ => system.terminate())

    Await.result(bindingFuture, Duration.Inf)

    println(s"Reading TCP data from $sourceHost:$sourcePort\nType q and then RETURN to stop...")
    while (StdIn.readLine() != "q") {} // let it run until user exits
    system.terminate()
  }

  def conversionFunction: ByteString => List[List[String]] = {
    val b = new StringBuilder
    val acc = mutable.ListBuffer.empty[String]

    { byteString =>
      val bb = byteString.asByteBuffer
      var res = mutable.ListBuffer.empty[List[String]]
      while (bb.hasRemaining) {
        val c = bb.get.toChar
        if (c == '\n') {
          acc += b.toString
          b.clear()
          res += acc.toList
          acc.clear()
        } else if (c == ',') {
          acc += b.toString
          b.clear()
        } else {
          b.append(c)
        }
      }
      res.toList
    }
  }
}
