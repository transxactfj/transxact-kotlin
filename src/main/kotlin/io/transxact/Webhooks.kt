package io.transxact

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

/**
 * Verifies a `Transxact-Signature` header: `t=<ms-timestamp>,v1=<hex-hmac>`,
 * HMAC-SHA256 over `"$timestamp.$rawPayload"`, constant-time compare.
 * Pass the raw request body exactly as received, not re-serialized JSON.
 */
public object Webhooks {
    public const val DEFAULT_TOLERANCE_MS: Long = 5 * 60 * 1000

    /** Parses a webhook body. Call this only after [verifySignature] returned true. */
    @JvmStatic
    public fun parseEvent(rawPayload: String): WebhookEvent =
        webhookJson.decodeFromString(WebhookEvent.serializer(), rawPayload)

    @JvmStatic
    @JvmOverloads
    public fun verifySignature(
        rawPayload: String,
        header: String,
        secret: String,
        toleranceMs: Long = DEFAULT_TOLERANCE_MS,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        if (secret.isEmpty()) return false
        val parts = header.split(",").mapNotNull {
            val i = it.indexOf('=')
            if (i < 0) null else it.substring(0, i).trim() to it.substring(i + 1).trim()
        }
        val timestamp = parts.firstOrNull { it.first == "t" }?.second ?: return false
        // During secret rotation the header carries one v1 per active secret; any match is valid.
        val signatures = parts.filter { it.first == "v1" }.map { it.second }
        if (signatures.isEmpty()) return false
        val ts = timestamp.toLongOrNull() ?: return false
        if (abs(nowMs - ts) > toleranceMs) return false

        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        }
        val expected = mac.doFinal("$timestamp.$rawPayload".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .toByteArray()
        // No short-circuit: every candidate is compared so timing doesn't reveal which one matched.
        return signatures.fold(false) { ok, sig -> MessageDigest.isEqual(sig.toByteArray(), expected) or ok }
    }
}
