package io.transxact

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class HardeningTest {
    private val sessionJson = """{"id":"cs_1","status":"pending","mode":"test","amount":5000,"currency":"FJD",
        "hostedUrl":"https://pay.transxact.io/cs_1","expiresAt":1}"""
    private val invoiceJson = """{"id":7,"status":"draft","overdue":false,"mode":"test","customerName":"Acme",
        "items":[{"description":"Widget","quantity":2,"unitPrice":500}],"dueDate":"2026-12-01",
        "subtotal":1000,"tax":0,"total":1000,"url":"https://pay.transxact.io/inv_7","createdAt":1}"""
    private val payoutJson = """{"id":"po_1","amount":900,"rail":"mpaisa","status":"paid","createdAt":1}"""

    private fun client(server: MockWebServer, maxRetries: Int = 2) =
        Transxact("sk_test_x", baseUrl = "http://localhost:${server.port}/", maxRetries = maxRetries)

    // --- transport -----------------------------------------------------------------------

    @Test
    fun idsAreConfinedToOnePathSegment() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(sessionJson))
            client(server).checkoutSessions.retrieve("../merchants/me?x=1")
            val req = server.takeRequest()
            assertEquals("/v1/checkout-sessions/..%2Fmerchants%2Fme%3Fx%3D1", req.path)
        }
        val c = Transxact("sk_test_x")
        assertFailsWith<IllegalArgumentException> { c.checkoutSessions.retrieve("..") }
        assertFailsWith<IllegalArgumentException> { c.checkoutSessions.retrieve(" ") }
    }

    @Test
    fun plainHttpIsOnlyAllowedForLoopback() {
        assertFailsWith<IllegalArgumentException> { Transxact("sk_test_x", baseUrl = "http://api.transxact.io") }
        assertFailsWith<IllegalArgumentException> { Transxact("sk_test_x", baseUrl = "not a url") }
        Transxact("sk_test_x", baseUrl = "https://api.transxact.io")
        Transxact("sk_test_x", baseUrl = "http://localhost:8080")
        Transxact("sk_test_x", baseUrl = "http://127.0.0.1:8080")
        Transxact("sk_test_x", baseUrl = "http://[::1]:8080")
    }

    @Test
    fun userAgentCarriesTheBuildVersion() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(sessionJson))
            client(server).checkoutSessions.retrieve("cs_1")
            val ua = server.takeRequest().getHeader("User-Agent")!!
            assertTrue(ua.startsWith("transxact-kotlin/"))
            assertNotEquals("transxact-kotlin/unknown", ua)
        }
    }

    @Test
    fun cancelIsNotReplayedOn5xx() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setResponseCode(200).setBody(sessionJson))
            val e = assertFailsWith<TransxactApiException> { client(server).checkoutSessions.cancel("cs_1") }
            assertEquals(503, e.statusCode)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun cancelIsRetriedOn429() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "1"))
            server.enqueue(MockResponse().setResponseCode(200).setBody(sessionJson))
            assertEquals("cs_1", client(server).checkoutSessions.cancel("cs_1").id)
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun createWithKeyIsRetriedOn5xxAndReusesTheKey() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(502).setHeader("Retry-After", "1"))
            server.enqueue(MockResponse().setResponseCode(201).setBody(sessionJson))
            client(server).checkoutSessions.create(CreateCheckoutSessionParams(amount = 1, currency = "FJD"), "k9")
            assertEquals("k9", server.takeRequest().getHeader("Idempotency-Key"))
            assertEquals("k9", server.takeRequest().getHeader("Idempotency-Key"))
        }
    }

    @Test
    fun retriesStopAtMaxRetries() = runTest {
        MockWebServer().use { server ->
            repeat(3) { server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "1")) }
            val e = assertFailsWith<TransxactApiException> { client(server, maxRetries = 1).merchants.me() }
            assertEquals(503, e.statusCode)
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun clientErrorsAreNotRetried() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":{"code":"not_found","message":"No such session"}}"""))
            val e = assertFailsWith<TransxactApiException> { client(server).checkoutSessions.retrieve("cs_x") }
            assertEquals("not_found", e.code)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun connectionFailureOnUnkeyedPostIsNotReplayed() = runTest {
        val server = MockWebServer().apply { start() }
        val c = client(server)
        server.shutdown()
        assertFailsWith<TransxactConnectionException> { c.checkoutSessions.cancel("cs_1") }
    }

    @Test
    fun unparseableErrorBodyStaysOutOfTheMessage() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(400).setBody("<html>secret-ish internal detail</html>"))
            val e = assertFailsWith<TransxactApiException> { client(server).merchants.me() }
            assertFalse(e.message!!.contains("internal detail"))
            assertEquals("<html>secret-ish internal detail</html>", e.body)
        }
    }

    // --- resources -----------------------------------------------------------------------

    @Test
    fun checkoutSessionListBuildsQuery() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":[$sessionJson],"nextCursor":"c2"}"""))
            val page = client(server).checkoutSessions.list(limit = 5, status = "pending", from = 10)
            assertEquals("c2", page.nextCursor)
            assertEquals("/v1/checkout-sessions?limit=5&status=pending&from=10", server.takeRequest().path)
        }
    }

    @Test
    fun invoicesCoverCreateListSendVoidAndPdf() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(201).setBody(invoiceJson))
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":[$invoiceJson],"hasMore":false}"""))
            server.enqueue(MockResponse().setResponseCode(200).setBody(invoiceJson))
            server.enqueue(MockResponse().setResponseCode(200).setBody(invoiceJson))
            server.enqueue(MockResponse().setResponseCode(200).setBody(invoiceJson))
            server.enqueue(MockResponse().setResponseCode(200).setBody("%PDF-1.7"))
            val api = client(server).invoices

            val created = api.create(CreateInvoiceParams("Acme", "2026-12-01", listOf(InvoiceItem("Widget", 2, 500))))
            assertEquals(7, created.id)
            assertEquals(1000, created.total)
            assertTrue(server.takeRequest().getHeader("Idempotency-Key")!!.isNotBlank())

            assertEquals(1, api.list(limit = 1, startingAfter = 3).data.size)
            assertEquals("/v1/invoices?limit=1&starting_after=3", server.takeRequest().path)

            api.retrieve(7)
            assertEquals("/v1/invoices/7", server.takeRequest().path)
            api.send(7)
            assertEquals("/v1/invoices/7/send", server.takeRequest().path)
            api.void(7)
            assertEquals("/v1/invoices/7/void", server.takeRequest().path)

            assertContentEquals("%PDF-1.7".toByteArray(), api.retrievePdf(7))
            assertEquals("/v1/invoices/7/pdf", server.takeRequest().path)
        }
    }

    @Test
    fun payoutsListAndRetrieve() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":[$payoutJson],"hasMore":true}"""))
            server.enqueue(MockResponse().setResponseCode(200).setBody(payoutJson))
            val api = client(server).payouts
            assertTrue(api.list(limit = 1).hasMore)
            assertEquals("/v1/payouts?limit=1", server.takeRequest().path)
            assertEquals(900, api.retrieve("po_1").amount)
            assertEquals("/v1/payouts/po_1", server.takeRequest().path)
        }
    }

    // --- webhooks ------------------------------------------------------------------------

    private fun v1(ts: Long, body: String, secret: String): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA256")) }
        return "v1=" + mac.doFinal("$ts.$body".toByteArray()).joinToString("") { "%02x".format(it) }
    }

    @Test
    fun webhookEmptySecretIsRejectedNotThrown() {
        val now = System.currentTimeMillis()
        assertFalse(Webhooks.verifySignature("{}", "t=$now,${v1(now, "{}", "x")}", ""))
    }

    @Test
    fun webhookAcceptsAnyMatchingSignatureDuringRotation() {
        val now = System.currentTimeMillis()
        val body = """{"id":"evt_1"}"""
        val rotated = "t=$now,${v1(now, body, "old")},${v1(now, body, "new")}"
        assertTrue(Webhooks.verifySignature(body, rotated, "old"))
        assertTrue(Webhooks.verifySignature(body, rotated, "new"))
        assertFalse(Webhooks.verifySignature(body, rotated, "third"))
    }

    @Test
    fun webhookRejectsMissingPartsAndNonNumericTimestamp() {
        val now = System.currentTimeMillis()
        val body = "{}"
        assertFalse(Webhooks.verifySignature(body, v1(now, body, "s"), "s"))
        assertFalse(Webhooks.verifySignature(body, "t=$now", "s"))
        assertFalse(Webhooks.verifySignature(body, "t=abc,${v1(now, body, "s")}", "s"))
        assertFalse(Webhooks.verifySignature(body, "", "s"))
    }

    @Test
    fun webhookToleranceAppliesToFutureTimestampsToo() {
        val now = System.currentTimeMillis()
        val body = "{}"
        val future = now + 10 * 60_000
        assertFalse(Webhooks.verifySignature(body, "t=$future,${v1(future, body, "s")}", "s", nowMs = now))
        assertTrue(Webhooks.verifySignature(body, "t=$future,${v1(future, body, "s")}", "s", toleranceMs = 15 * 60_000, nowMs = now))
    }
}
