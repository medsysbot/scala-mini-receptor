import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import scala.util.Try

object Main {
  private val DefaultPort = 8080
  private val MinimumPort = 0
  private val MaximumPort = 65535

  private def configuredPort: Int = {
    sys.env.get("PORT") match {
      case None => DefaultPort
      case Some(value) =>
        Try(value.toInt).toOption match {
          case Some(port) if port >= MinimumPort && port <= MaximumPort => port
          case _ =>
            throw new IllegalArgumentException(
              s"Invalid PORT value '$value': expected an integer between $MinimumPort and $MaximumPort"
            )
        }
    }
  }

  def main(args: Array[String]): Unit = {
    val server = HttpServer.create(new InetSocketAddress("0.0.0.0", configuredPort), 0)

    server.createContext("/", new HttpHandler {
      override def handle(exchange: HttpExchange): Unit = {
        val body = "Scala Mini Receptor\n".getBytes(StandardCharsets.UTF_8)
        exchange.getResponseHeaders.add("Content-Type", "text/plain; charset=utf-8")
        exchange.sendResponseHeaders(200, body.length.toLong)
        val output = exchange.getResponseBody
        try output.write(body)
        finally output.close()
      }
    })

    server.start()
  }
}
