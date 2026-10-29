package io.transxact

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// Enum-like values are plain strings so a new status from the API never breaks an older SDK.
public object CheckoutSessionStatus {
    public const val PENDING: String = "pending"
    public const val SUCCEEDED: String = "succeeded"
    public const val FAILED: String = "failed"
    public const val CANCELLED: String = "cancelled"
}

@Serializable
public data class Quote(
    val amount: Long,
    val currency: String,
    val rate: String? = null,
    val asOf: Long? = null,
)

@Serializable
public data class CheckoutSession(
    val id: String,
    val status: String,
    val mode: String,
    val amount: Long,
    val currency: String,
    val hostedUrl: String,
    val metadata: Map<String, String> = emptyMap(),
    val expiresAt: Long,
    val invoiceNumber: String? = null,
    val quote: Quote? = null,
)

@Serializable
public data class CheckoutSessionList(val data: List<CheckoutSession>, val nextCursor: String? = null)

@Serializable
public data class QuoteInput(val amount: Long, val currency: String)

/** Send [amount] + [currency] ("FJD"), or [quote], never both. */
@Serializable
public data class CreateCheckoutSessionParams(
    val amount: Long? = null,
    val currency: String? = null,
    val quote: QuoteInput? = null,
    val successUrl: String? = null,
    val cancelUrl: String? = null,
    val metadata: Map<String, String>? = null,
    val customerEmail: String? = null,
    val expiresAt: Long? = null,
)

@Serializable
public data class InvoiceItem(val description: String, val quantity: Int, val unitPrice: Long)

@Serializable
public data class Invoice(
    val id: Long,
    val number: String? = null,
    val status: String,
    val overdue: Boolean,
    val mode: String,
    val customerName: String,
    val customerEmail: String? = null,
    val items: List<InvoiceItem>,
    val taxLabel: String? = null,
    val taxPercent: Double = 0.0,
    val vatNumber: String? = null,
    val dueDate: String,
    val subtotal: Long,
    val tax: Long,
    val total: Long,
    val url: String,
    val pdfUrl: String? = null,
    val createdAt: Long,
    val sentAt: Long? = null,
    val paidAt: Long? = null,
)

@Serializable
public data class InvoiceList(val data: List<Invoice>, val hasMore: Boolean)

@Serializable
public data class CreateInvoiceParams(
    val customerName: String,
    val dueDate: String,
    val items: List<InvoiceItem>,
    val customerEmail: String? = null,
    val taxLabel: String? = null,
    val taxPercent: Double? = null,
    val vatNumber: String? = null,
)

@Serializable
public data class Merchant(
    val tier: String,
    val mode: String,
    val balance: Long,
    val payoutSchedule: String,
)

@Serializable
public data class Payout(
    val id: String,
    val amount: Long,
    val rail: String,
    val status: String,
    val paidAt: Long? = null,
    val failureReason: String? = null,
    val createdAt: Long,
)

@Serializable
public data class PayoutList(val data: List<Payout>, val hasMore: Boolean)

/** Webhook payload. [data] is left raw; decode it for the event types you handle. */
@Serializable
public data class WebhookEvent(
    val id: String,
    val type: String,
    val data: JsonElement? = null,
)
