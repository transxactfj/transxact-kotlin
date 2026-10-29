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

    @JvmStatic
    @JvmOverloads
    public fun verifySignature(
        rawPayload: String,
        header: String,
        secret: String,
        toleranceMs: Long = DEFAULT_TOLERANCE_MS,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        val parts = header.split(",").mapNotNull {
            val i = it.indexOf('=')
            if (i < 0) null else it.substring(0, i).trim() to it.substring(i + 1).trim()
        }.toMap()
        val timestamp = parts["t"] ?: return false
        val signature = parts["v1"] ?: return false
        val ts = timestamp.toLongOrNull() ?: return false
        if (abs(nowMs - ts) > toleranceMs) return false

        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        }
        val expected = mac.doFinal("$timestamp.$rawPayload".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return MessageDigest.isEqual(signature.toByteArray(), expected.toByteArray())
    }
}
