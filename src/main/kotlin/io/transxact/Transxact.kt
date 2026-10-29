package io.transxact

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID

public const val PRODUCTION_URL: String = "https://api.transxact.io"

/**
 * Entry point. Server-side only: [apiKey] is your `sk_test_...` / `sk_live_...` secret key.
 * Never ship it in a mobile app.
 */
public class Transxact(
    apiKey: String,
    private val baseUrl: String = PRODUCTION_URL,
    private val maxRetries: Int = 2,
    private val http: OkHttpClient = OkHttpClient(),
) {
    private val authHeader: String

    init {
        require(apiKey.isNotBlank()) { "apiKey is required" }
        authHeader = "Bearer $apiKey"
    }

    public val checkoutSessions: CheckoutSessionsApi = CheckoutSessionsApi(this)
    public val invoices: InvoicesApi = InvoicesApi(this)
    public val merchants: MerchantsApi = MerchantsApi(this)
    public val payouts: PayoutsApi = PayoutsApi(this)

    internal val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    internal suspend fun <T> send(
        method: String,
        path: String,
        serializer: KSerializer<T>,
        query: Map<String, Any?> = emptyMap(),
        body: String? = null,
        idempotencyKey: String? = null,
    ): T {
        val text = execute(method, path, query, body, idempotencyKey).decodeToString()
        return json.decodeFromString(serializer, text)
    }

    internal suspend fun sendForBytes(method: String, path: String): ByteArray =
        execute(method, path, emptyMap(), null, null)

    internal fun newKey(): String = UUID.randomUUID().toString()

    private suspend fun execute(
        method: String,
        path: String,
        query: Map<String, Any?>,
        body: String?,
        idempotencyKey: String?,
    ): ByteArray = withContext(Dispatchers.IO) {
        val url = (baseUrl.trimEnd('/') + path).toHttpUrl().newBuilder().apply {
            query.forEach { (k, v) -> if (v != null) addQueryParameter(k, v.toString()) }
        }.build()
        val reqBody: RequestBody? = when {
            body != null -> body.toRequestBody("application/json".toMediaType())
            method == "POST" -> ByteArray(0).toRequestBody(null)
            else -> null
        }
        val request = Request.Builder().url(url).method(method, reqBody)
            .header("Authorization", authHeader)
            .header("Accept", "application/json")
            .header("User-Agent", "transxact-kotlin/$SDK_VERSION")
            .apply { idempotencyKey?.let { header("Idempotency-Key", it) } }
            .build()

        var attempt = 0
        while (true) {
            try {
                http.newCall(request).execute().use { response ->
                    val bytes = response.body?.bytes() ?: ByteArray(0)
                    if (response.isSuccessful) return@withContext bytes
                    if (attempt < maxRetries && isRetryable(response.code)) {
                        delay(retryDelayMs(response.header("Retry-After"), attempt++))
                        return@use
                    }
                    throw toApiException(response.code, bytes.decodeToString())
                }
            } catch (e: IOException) {
                if (attempt >= maxRetries) throw TransxactConnectionException(e)
                delay(retryDelayMs(null, attempt++))
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    private fun isRetryable(code: Int) = code == 408 || code == 429 || code >= 500

    private fun retryDelayMs(retryAfter: String?, attempt: Int): Long {
        retryAfter?.toLongOrNull()?.takeIf { it > 0 }?.let { return minOf(it * 1000, 60_000) }
        return minOf(1000L shl attempt, 60_000)
    }

    private fun toApiException(status: Int, raw: String): TransxactApiException {
        val parsed = runCatching { json.decodeFromString(ErrorEnvelope.serializer(), raw) }.getOrNull()
        return TransxactApiException(status, parsed?.error?.code, parsed?.error?.message ?: raw.ifBlank { "empty response" }, raw)
    }

    private companion object {
        const val SDK_VERSION = "0.1.0"
    }
}
