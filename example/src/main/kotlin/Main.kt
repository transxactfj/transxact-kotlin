import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.transxact.CheckoutSessionStatus
import io.transxact.CreateCheckoutSessionParams
import io.transxact.Transxact
import io.transxact.TransxactApiException
import io.transxact.Webhooks
import java.util.concurrent.ConcurrentHashMap

// Your own price list. The browser says WHAT is bought; the price is decided here, where the Customer can't edit it.
private val ORDERS = mapOf(
    "order_1001" to 2500L, // FJD 25.00
    "order_1002" to 8000L, // FJD 80.00
)

fun main() {
    val secretKey = System.getenv("TRANSXACT_SECRET_KEY")
        ?: error("Set TRANSXACT_SECRET_KEY to an sk_test_ key (Test mode moves no money).")
    require(secretKey.startsWith("sk_test_")) { "This example only runs in Test mode: use an sk_test_ key." }
    val webhookSecret = System.getenv("TRANSXACT_WEBHOOK_SECRET") // from the dashboard's Webhooks section; optional
    val port = (System.getenv("PORT") ?: "8080").toInt()
    val baseUrl = System.getenv("BASE_URL") ?: "http://localhost:$port"

    val transxact = Transxact(secretKey)
    val handledEvents = ConcurrentHashMap.newKeySet<String>() // delivery is at least once: skip repeats

    embeddedServer(Netty, port = port) {
        routing {
            get("/") { call.respondText(PAGE, ContentType.Text.Html) }

            // POST /api/checkout?orderId=order_1001 -> { id, hostedUrl }
            post("/api/checkout") {
                val orderId = call.request.queryParameters["orderId"].orEmpty()
                val amount = ORDERS[orderId]
                    ?: return@post call.respondText("""{"error":"unknown_order"}""", ContentType.Application.Json, HttpStatusCode.NotFound)
                try {
                    val session = transxact.checkoutSessions.create(
                        CreateCheckoutSessionParams(
                            amount = amount,
                            currency = "FJD",
                            // The API only accepts https return URLs, so locally (plain http) none are sent and the
                            // hosted page keeps the Customer. Use a tunnel and BASE_URL to try the /return page.
                            successUrl = if (baseUrl.startsWith("https://")) "$baseUrl/return" else null,
                            cancelUrl = if (baseUrl.startsWith("https://")) "$baseUrl/return" else null,
                            metadata = mapOf("orderId" to orderId),
                        ),
                        // One key per order and minute: a double click or retry replays the same session.
                        idempotencyKey = "example-$orderId-${System.currentTimeMillis() / 60_000}",
                    )
                    call.respondText("""{"id":"${session.id}","hostedUrl":"${session.hostedUrl}"}""", ContentType.Application.Json)
                } catch (e: TransxactApiException) {
                    println("Transxact error: ${e.message}") // e.body has the raw error JSON
                    call.respondText("""{"error":"${e.code}"}""", ContentType.Application.Json, HttpStatusCode.BadGateway)
                }
            }

            // GET /api/checkout/cs_... -> { status }. Handy for checking any outcome by hand.
            get("/api/checkout/{id}") {
                try {
                    val status = transxact.checkoutSessions.retrieve(call.parameters["id"].orEmpty()).status
                    call.respondText("""{"status":"$status"}""", ContentType.Application.Json)
                } catch (e: TransxactApiException) {
                    call.respondText("""{"error":"${e.code}"}""", ContentType.Application.Json, HttpStatusCode.fromValue(e.statusCode))
                }
            }

            // The Customer lands here after paying. This is a hint, never proof: ask Transxact what really happened.
            get("/return") {
                val id = call.request.queryParameters["session_id"]
                    ?: return@get call.respondText("Missing session_id", status = HttpStatusCode.BadRequest)
                val status = try {
                    transxact.checkoutSessions.retrieve(id).status
                } catch (e: TransxactApiException) {
                    return@get call.respondText("Couldn't check your payment (${e.code}). Try refreshing.", status = HttpStatusCode.BadGateway)
                }
                val message = when (status) {
                    CheckoutSessionStatus.SUCCEEDED -> "Paid. Thank you!"
                    CheckoutSessionStatus.PENDING -> "Still confirming your payment. Refresh in a moment."
                    CheckoutSessionStatus.FAILED -> "The payment didn't go through."
                    else -> "The payment was cancelled."
                }
                call.respondText("<h1>$message</h1><p><a href=\"/\">Back to the shop</a></p>", ContentType.Text.Html)
            }

            // Fulfil here, not on /return. Needs a public https URL (use a tunnel such as cloudflared or ngrok).
            post("/webhooks/transxact") {
                val raw = call.receiveText() // the raw body, exactly as received
                if (webhookSecret == null) return@post call.respond(HttpStatusCode.ServiceUnavailable)
                if (!Webhooks.verifySignature(raw, call.request.header("Transxact-Signature") ?: "", webhookSecret)) {
                    return@post call.respond(HttpStatusCode.BadRequest)
                }
                val event = Webhooks.parseEvent(raw)
                if (handledEvents.add(event.id) && event.type == "checkout_session.succeeded") {
                    val session = event.checkoutSession()
                    println("FULFIL order ${session?.metadata?.get("orderId")} (session ${session?.id})")
                }
                call.respond(HttpStatusCode.OK)
            }
        }
    }.start(wait = true)
}

private val PAGE = """
<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Transxact Kotlin example</title>
<style>body{font:16px/1.5 system-ui;max-width:32rem;margin:3rem auto;padding:0 1rem}button{font:inherit;padding:.6rem 1rem;margin:.3rem 0;display:block}</style>
</head><body>
<h1>Transxact Kotlin example</h1>
<p>Test mode: no real money moves.</p>
<button onclick="pay('order_1001')">Pay FJD 25.00</button>
<button onclick="pay('order_1002')">Pay FJD 80.00</button>
<p id="msg"></p>
<script>
function pay(orderId) {
  document.getElementById('msg').textContent = 'Starting payment...';
  fetch('/api/checkout?orderId=' + orderId, { method: 'POST' })
    .then(function (r) { return r.json(); })
    .then(function (s) {
      if (s.hostedUrl) { window.location = s.hostedUrl; }
      else { document.getElementById('msg').textContent = 'Error: ' + s.error; }
    });
}
</script>
</body></html>
""".trimIndent()
