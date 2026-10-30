package io.transxact

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Runs against the real API in Test mode. Skipped unless TRANSXACT_SECRET_KEY is an `sk_test_` key. */
class SmokeTest {
    private fun client(): Transxact {
        val key = System.getenv("TRANSXACT_SECRET_KEY")
        assumeTrue(key != null && key.startsWith("sk_test_"), "set TRANSXACT_SECRET_KEY=sk_test_... to run")
        return Transxact(key)
    }

    @Test
    fun merchantIsInTestMode() = runBlocking {
        assertEquals("test", client().merchants.me().mode)
    }

    @Test
    fun createRetrieveListCancel() = runBlocking {
        val tx = client()
        val key = "kotlin-smoke-${System.currentTimeMillis()}"
        val params = CreateCheckoutSessionParams(
            amount = 5000, currency = "FJD",
            successUrl = "https://example.com/return", cancelUrl = "https://example.com/return",
            metadata = mapOf("orderId" to "smoke"),
        )
        val created = tx.checkoutSessions.create(params, idempotencyKey = key)
        println("hostedUrl: ${created.hostedUrl}")
        assertEquals(CheckoutSessionStatus.PENDING, created.status)
        assertEquals(5000, created.amount)
        assertEquals("smoke", created.metadata["orderId"])

        // Same idempotency key returns the original session.
        assertEquals(created.id, tx.checkoutSessions.create(params, idempotencyKey = key).id)

        assertEquals(created.id, tx.checkoutSessions.retrieve(created.id).id)
        assertTrue(tx.checkoutSessions.list(limit = 5).data.isNotEmpty())

        assertEquals(CheckoutSessionStatus.CANCELLED, tx.checkoutSessions.cancel(created.id).status)
    }

    // Test mode only, and not in the OpenAPI spec, so the SDK doesn't wrap it: called with OkHttp directly.
    private fun testTransition(id: String, status: String, provider: String?) {
        val body = if (provider != null) """{"status":"$status","provider":"$provider"}""" else """{"status":"$status"}"""
        val request = Request.Builder()
            .url("https://api.transxact.io/v1/checkout-sessions/$id/test-transition")
            .header("Authorization", "Bearer ${System.getenv("TRANSXACT_SECRET_KEY")}")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        OkHttpClient().newCall(request).execute().use { check(it.isSuccessful) { "test-transition returned ${it.code}" } }
    }

    @Test
    fun failedAndSucceededOutcomesAreReported() = runBlocking {
        val tx = client()
        for ((status, provider) in listOf("failed" to null, "succeeded" to "mpaisa")) {
            val created = tx.checkoutSessions.create(CreateCheckoutSessionParams(amount = 5000, currency = "FJD"))
            testTransition(created.id, status, provider)
            assertEquals(status, tx.checkoutSessions.retrieve(created.id).status)
        }
    }

    @Test
    fun listsDontFail() = runBlocking {
        val tx = client()
        tx.invoices.list(limit = 1)
        tx.payouts.list(limit = 1)
        Unit
    }
}
