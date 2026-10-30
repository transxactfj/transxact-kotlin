package io.transxact

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.util.Properties
import java.util.UUID
import kotlin.random.Random

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
        val base = requireNotNull(baseUrl.toHttpUrlOrNull()) { "baseUrl is not a valid URL" }
        require(base.isHttps || isLoopback(base.host)) {
            "baseUrl must use https; plain http is only allowed for localhost, so the API key is never sent in cleartext"
        }
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

        // A request that may have been processed is only replayed when doing so is harmless.
        val replaySafe = method == "GET" || idempotencyKey != null

        var attempt = 0
        while (true) {
            try {
                http.newCall(request).execute().use { response ->
                    val bytes = response.body?.bytes() ?: ByteArray(0)
                    if (response.isSuccessful) return@withContext bytes
                    if (attempt < maxRetries && isRetryable(response.code, replaySafe)) {
                        delay(retryDelayMs(response.header("Retry-After"), attempt++))
                        return@use
                    }
                    throw toApiException(response.code, bytes.decodeToString())
                }
            } catch (e: IOException) {
                if (!replaySafe || attempt >= maxRetries) throw TransxactConnectionException(e)
                delay(retryDelayMs(null, attempt++))
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    // 429 means the request was rejected unprocessed, so it is always safe to retry.
    private fun isRetryable(code: Int, replaySafe: Boolean) =
        code == 429 || (replaySafe && (code == 408 || code >= 500))

    private fun retryDelayMs(retryAfter: String?, attempt: Int): Long {
        retryAfter?.toLongOrNull()?.takeIf { it > 0 }?.let { return minOf(it * 1000, 60_000) }
        val backoff = minOf(1000L shl attempt, 60_000)
        return (backoff * (0.5 + Random.nextDouble() * 0.5)).toLong()
    }

    private fun toApiException(status: Int, raw: String): TransxactApiException {
        val parsed = runCatching { json.decodeFromString(ErrorEnvelope.serializer(), raw) }.getOrNull()
        // An unparseable body stays on `body` but is kept out of the message, which tends to end up in logs.
        val message = parsed?.error?.message ?: if (raw.isBlank()) "empty response" else "unexpected response body"
        return TransxactApiException(status, parsed?.error?.code, message, raw)
    }

    private companion object {
        val SDK_VERSION: String = Transxact::class.java.getResourceAsStream("/transxact-sdk.properties")
            ?.use { Properties().apply { load(it) }.getProperty("version") } ?: "unknown"
    }
}

private fun isLoopback(host: String): Boolean =
    host == "localhost" || host.endsWith(".localhost") || host == "127.0.0.1" || host == "::1" || host == "[::1]"

/** Percent-encodes a caller-supplied id so it can only ever be one path segment. */
internal fun pathSegment(id: Any): String {
    val s = id.toString()
    require(s.isNotBlank() && s != "." && s != "..") { "invalid id" }
    return URLEncoder.encode(s, Charsets.UTF_8.name()).replace("+", "%20")
}
