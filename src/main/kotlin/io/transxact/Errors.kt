package io.transxact

import kotlinx.serialization.Serializable

/** Thrown for any 4xx/5xx response. Branch on [code]; the message is for logs. */
public class TransxactApiException(
    public val statusCode: Int,
    public val code: String?,
    message: String,
    public val body: String?,
) : RuntimeException("HTTP $statusCode${code?.let { " ($it)" } ?: ""}: $message")

/** Thrown when the request never got an HTTP response (DNS, TLS, timeout). */
public class TransxactConnectionException(cause: Throwable) : RuntimeException(cause.message, cause)

@Serializable
internal data class ErrorEnvelope(val error: ErrorBody)

@Serializable
internal data class ErrorBody(val code: String, val message: String)
