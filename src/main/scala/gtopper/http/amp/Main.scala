package gtopper.http.amp

import scala.io.StdIn

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.stream.scaladsl.{Broadcast, Flow, GraphDSL, Sink}
import akka.stream.{ActorMaterializer, FlowShape}
import com.typesafe.config.ConfigFactory

object Main {

  def main(args: Array[String]): Unit = {
    implicit val system = ActorSystem()
    implicit val materializer = ActorMaterializer()
    implicit val executionContext = system.dispatcher
    implicit val config = ConfigFactory.load()

    val targetHost = config.getString("target.host")
    val targetPort = config.getInt("target.port")
    val printPeriod = config.getInt("print-period")

    val startTime = System.currentTimeMillis()

    val clientFlow = Flow[HttpRequest]
      .map { req => req.copy(headers = req.headers.filterNot(_.name() == "Timeout-Access")) }
      .zipWithIndex
      .via(Http().cachedHostConnectionPool[Long](targetHost, targetPort))
      .to(Sink.foreach { case (response, i) =>
        if (i % printPeriod == 0) {
          val runtime = (System.currentTimeMillis() - startTime) / 1000
          println(s"$i / $runtime seconds | $response")
        }
      })

    val requestHandler: Flow[HttpRequest, HttpResponse, Any] =
      Flow.fromGraph(GraphDSL.create() { implicit builder =>
        import GraphDSL.Implicits._
        val bcast = builder.add(Broadcast[HttpRequest](2))

        bcast.out(0) ~> clientFlow
        FlowShape(bcast.in, bcast.out(1))
      }).map(_ => HttpResponse(StatusCodes.OK))

    val bindingFuture = Http().bindAndHandle(requestHandler, "localhost", 8080)

    println(s"Server online at http://localhost:8080/\nType q and then RETURN to stop...")
    while (StdIn.readLine() != "q") {} // let it run until user exits
    bindingFuture
      .flatMap(_.unbind()) // trigger unbinding from the port
      .onComplete(_ => system.terminate()) // and shutdown when done
  }
}
