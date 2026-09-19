import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import scala.util.Try

object Main {
  def main(args: Array[String]): Unit = {
    val port = sys.env.get("PORT").flatMap(value => Try(value.toInt).toOption).getOrElse(8080)
    val server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0)

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
