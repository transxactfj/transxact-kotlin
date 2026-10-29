package io.transxact

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TransxactTest {
    private val sessionJson = """{"id":"cs_1","status":"pending","mode":"test","amount":5000,"currency":"FJD",
        "hostedUrl":"https://pay.transxact.io/cs_1","metadata":{"orderId":"9912"},"expiresAt":1,"newField":1}"""

    @Test
    fun createSendsAuthIdempotencyAndBody() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(201).setBody(sessionJson))
            val client = Transxact("sk_test_x", baseUrl = server.url("/").toString())
            val s = client.checkoutSessions.create(
                CreateCheckoutSessionParams(amount = 5000, currency = "FJD", metadata = mapOf("orderId" to "9912")),
                idempotencyKey = "k1",
            )
            assertEquals("cs_1", s.id)
            val req = server.takeRequest()
            assertEquals("Bearer sk_test_x", req.getHeader("Authorization"))
            assertEquals("k1", req.getHeader("Idempotency-Key"))
            assertEquals("""{"amount":5000,"currency":"FJD","metadata":{"orderId":"9912"}}""", req.body.readUtf8())
        }
    }

    @Test
    fun errorEnvelopeBecomesException() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"code":"unauthorized","message":"Invalid API key"}}"""))
            val client = Transxact("sk_test_x", baseUrl = server.url("/").toString())
            val e = assertFailsWith<TransxactApiException> { client.merchants.me() }
            assertEquals(401, e.statusCode)
            assertEquals("unauthorized", e.code)
        }
    }

    @Test
    fun retriesOn503() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "1"))
            server.enqueue(MockResponse().setResponseCode(200).setBody(sessionJson))
            val client = Transxact("sk_test_x", baseUrl = server.url("/").toString())
            assertEquals("cs_1", client.checkoutSessions.retrieve("cs_1").id)
            assertEquals(2, server.requestCount)
        }
    }

    private fun sign(ts: Long, body: String, secret: String): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA256")) }
        return "t=$ts,v1=" + mac.doFinal("$ts.$body".toByteArray()).joinToString("") { "%02x".format(it) }
    }

    @Test
    fun webhookVerification() {
        val now = System.currentTimeMillis()
        val body = """{"id":"evt_1"}"""
        assertTrue(Webhooks.verifySignature(body, sign(now, body, "whsec"), "whsec"))
        assertFalse(Webhooks.verifySignature(body, sign(now, body, "other"), "whsec"))
        assertFalse(Webhooks.verifySignature("$body ", sign(now, body, "whsec"), "whsec"))
        assertFalse(Webhooks.verifySignature(body, sign(now - 10 * 60_000, body, "whsec"), "whsec"))
        assertFalse(Webhooks.verifySignature(body, "garbage", "whsec"))
    }
}
