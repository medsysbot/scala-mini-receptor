import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import java.net.{InetSocketAddress, URLDecoder}
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import scala.util.Try

object Main {
  private val DefaultPort = 8080
  // Drain window in seconds passed to HttpServer.stop(delaySeconds).
  // Non-zero gives in-flight exchanges a chance to complete during termination.
  private val ShutdownDrainSeconds = 2

  private val Page =
    """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Scala Dependency Risk Analyzer</title>
<style>
:root{font-family:Inter,system-ui,sans-serif;color:#f5f7ff;background:#0d1220}*{box-sizing:border-box}
body{margin:0;min-height:100vh;background:linear-gradient(145deg,#0d1220,#25345a);padding:32px}
main{max-width:1000px;margin:auto}.tag{display:inline-block;background:#304775;color:#bdd1ff;padding:7px 11px;border-radius:999px;font-size:12px;font-weight:800;text-transform:uppercase;letter-spacing:.06em}
h1{font-size:clamp(34px,5vw,58px);margin:16px 0 8px}.sub{color:#b4c0d7;max-width:760px;line-height:1.6}
.panel{margin-top:28px;background:#151e34;border:1px solid #42577f;border-radius:20px;padding:22px}
.grid{display:grid;grid-template-columns:repeat(2,1fr);gap:14px}.field{background:#0b1222;border:1px solid #42577f;border-radius:14px;padding:15px}
label{display:flex;justify-content:space-between;color:#c0caDE;font-size:13px;font-weight:700}.field input{width:100%;margin-top:12px}
button{margin-top:16px;border:0;border-radius:12px;padding:12px 18px;background:#b7cbff;color:#0a1120;font-weight:800;cursor:pointer}
.results{display:grid;grid-template-columns:repeat(3,1fr);gap:12px;margin-top:18px}.card{background:#0b1222;border:1px solid #42577f;border-radius:14px;padding:16px}.card b{font-size:28px;display:block}.card span{font-size:12px;color:#96a7c3;text-transform:uppercase}.note{margin-top:14px;color:#9eafc8;font-size:13px}
@media(max-width:700px){body{padding:18px}.grid,.results{grid-template-columns:1fr}}
</style>
</head>
<body><main>
<span class="tag">Scala • GovernEvo target</span>
<h1>Dependency Risk Analyzer</h1>
<p class="sub">Estimate release risk from dependency count, critical libraries, change size and automated test coverage.</p>
<section class="panel">
<div class="grid">
<div class="field"><label>Total dependencies <span id="v1">42</span></label><input id="deps" type="range" min="1" max="250" value="42" oninput="v1.textContent=this.value"></div>
<div class="field"><label>Critical dependencies <span id="v2">5</span></label><input id="critical" type="range" min="0" max="40" value="5" oninput="v2.textContent=this.value"></div>
<div class="field"><label>Change size <span id="v3">3</span>/5</label><input id="change" type="range" min="1" max="5" value="3" oninput="v3.textContent=this.value"></div>
<div class="field"><label>Test coverage <span id="v4">78</span>%</label><input id="coverage" type="range" min="0" max="100" value="78" oninput="v4.textContent=this.value"></div>
</div>
<button onclick="analyze()">Analyze release risk</button>
<div class="results"><div class="card"><b id="score">—</b><span>Risk score</span></div><div class="card"><b id="level">—</b><span>Risk level</span></div><div class="card"><b id="pressure">—</b><span>Dependency pressure</span></div></div>
<div class="note" id="status">Ready.</div>
</section></main>
<script>
async function analyze(){
 const q=new URLSearchParams({deps:deps.value,critical:critical.value,change:change.value,coverage:coverage.value});
 const r=await fetch('/api/risk?'+q); const d=await r.json();
 if(!r.ok){status.textContent=d.error||'Analysis failed';return}
 score.textContent=d.score+'/100';level.textContent=d.level;pressure.textContent=d.dependency_pressure.toFixed(1)+'%';status.textContent=d.message;
}
analyze();
</script></body></html>"""

  private def configuredPort: Int =
    sys.env.get("PORT").flatMap(v => Try(v.toInt).toOption).filter(p => p >= 1 && p <= 65535).getOrElse(DefaultPort)

  private def respond(exchange: HttpExchange, status: Int, contentType: String, body: String): Unit = {
    val bytes = body.getBytes(StandardCharsets.UTF_8)
    val headers = exchange.getResponseHeaders
    headers.set("Content-Type", contentType)
    headers.set("Cache-Control", "no-store")
    headers.set("X-Content-Type-Options", "nosniff")
    exchange.sendResponseHeaders(status, bytes.length.toLong)
    val output = exchange.getResponseBody
    try output.write(bytes) finally output.close()
  }

  private def query(exchange: HttpExchange): Map[String, String] =
    Option(exchange.getRequestURI.getRawQuery).map { raw =>
      raw.split("&").flatMap { part =>
        part.split("=", 2) match {
          case Array(k, v) =>
            Some(URLDecoder.decode(k, "UTF-8") -> URLDecoder.decode(v, "UTF-8"))
          case Array(k) => Some(URLDecoder.decode(k, "UTF-8") -> "")
          case _ => None
        }
      }.toMap
    }.getOrElse(Map.empty)

  private def intParam(params: Map[String, String], key: String, min: Int, max: Int): Option[Int] =
    params.get(key).flatMap(v => Try(v.toInt).toOption).filter(v => v >= min && v <= max)

  def main(args: Array[String]): Unit = {
    val server = HttpServer.create(new InetSocketAddress("0.0.0.0", configuredPort), 0)

    // Ensure clean termination on SIGTERM/CTRL-C: stop accepting new connections and
    // allow a short drain window for in-flight requests.
    val stopping = new AtomicBoolean(false)
    Runtime.getRuntime.addShutdownHook(new Thread(() => {
      if (stopping.compareAndSet(false, true)) {
        try server.stop(ShutdownDrainSeconds)
        catch {
          // Best-effort shutdown; avoid blocking JVM termination if stop throws.
          case _: Throwable => ()
        }
      }
    }, "shutdown-hook"))

    server.createContext("/", new HttpHandler {
      override def handle(exchange: HttpExchange): Unit = {
        val path = exchange.getRequestURI.getPath
        val method = exchange.getRequestMethod

        if (method != "GET") {
          respond(exchange, 405, "application/json; charset=utf-8", """{"error":"method_not_allowed"}""")
        } else if (path == "/") {
          respond(exchange, 200, "text/html; charset=utf-8", Page)
        } else if (path == "/healthz") {
          respond(exchange, 200, "application/json; charset=utf-8", """{"status":"ok","service":"scala-dependency-risk"}""")
        } else if (path == "/api/risk") {
          val p = query(exchange)
          val deps = intParam(p, "deps", 1, 10000)
          val critical = intParam(p, "critical", 0, 10000)
          val change = intParam(p, "change", 1, 5)
          val coverage = intParam(p, "coverage", 0, 100)

          (deps, critical, change, coverage) match {
            case (Some(d), Some(c), Some(ch), Some(cov)) if c <= d =>
              val dependencyPressure = c.toDouble / d.toDouble * 100.0
              val raw = dependencyPressure * 0.35 + ch.toDouble / 5.0 * 100.0 * 0.35 + (100.0 - cov) * 0.30
              val score = math.max(0, math.min(100, math.round(raw).toInt))
              val level =
                if (score >= 75) "Critical"
                else if (score >= 55) "High"
                else if (score >= 35) "Moderate"
                else "Low"
              val message =
                if (score >= 75) "Reduce change scope or strengthen test evidence before release."
                else if (score >= 55) "Review critical dependencies and validate rollback readiness."
                else if (score >= 35) "Risk is manageable with staged validation."
                else "Dependency and test profile is comparatively controlled."
              val body = f"""{"score":$score%d,"level":"$level","dependency_pressure":$dependencyPressure%.4f,"message":"$message"}"""
              respond(exchange, 200, "application/json; charset=utf-8", body)
            case _ =>
              respond(exchange, 400, "application/json; charset=utf-8", """{"error":"invalid_risk_inputs"}""")
          }
        } else {
          respond(exchange, 404, "application/json; charset=utf-8", """{"error":"not_found"}""")
        }
      }
    })

    server.start()
  }
}
