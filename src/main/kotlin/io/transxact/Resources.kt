package io.transxact

public class CheckoutSessionsApi internal constructor(private val c: Transxact) {
    /** [idempotencyKey] defaults to a random UUID; pass your own (e.g. the order id) to make retries safe across restarts. */
    public suspend fun create(
        params: CreateCheckoutSessionParams,
        idempotencyKey: String = c.newKey(),
    ): CheckoutSession = c.send(
        "POST", "/v1/checkout-sessions", CheckoutSession.serializer(),
        body = c.json.encodeToString(CreateCheckoutSessionParams.serializer(), params),
        idempotencyKey = idempotencyKey,
    )

    public suspend fun list(
        limit: Int? = null,
        cursor: String? = null,
        status: String? = null,
        from: Long? = null,
        to: Long? = null,
    ): CheckoutSessionList = c.send(
        "GET", "/v1/checkout-sessions", CheckoutSessionList.serializer(),
        query = mapOf("limit" to limit, "cursor" to cursor, "status" to status, "from" to from, "to" to to),
    )

    public suspend fun retrieve(id: String): CheckoutSession =
        c.send("GET", "/v1/checkout-sessions/${pathSegment(id)}", CheckoutSession.serializer())

    public suspend fun cancel(id: String): CheckoutSession =
        c.send("POST", "/v1/checkout-sessions/${pathSegment(id)}/cancel", CheckoutSession.serializer())
}

public class InvoicesApi internal constructor(private val c: Transxact) {
    public suspend fun create(params: CreateInvoiceParams, idempotencyKey: String = c.newKey()): Invoice = c.send(
        "POST", "/v1/invoices", Invoice.serializer(),
        body = c.json.encodeToString(CreateInvoiceParams.serializer(), params),
        idempotencyKey = idempotencyKey,
    )

    public suspend fun list(limit: Int? = null, startingAfter: Long? = null): InvoiceList =
        c.send("GET", "/v1/invoices", InvoiceList.serializer(), query = mapOf("limit" to limit, "starting_after" to startingAfter))

    public suspend fun retrieve(id: Long): Invoice = c.send("GET", "/v1/invoices/${pathSegment(id)}", Invoice.serializer())

    public suspend fun send(id: Long, idempotencyKey: String = c.newKey()): Invoice =
        c.send("POST", "/v1/invoices/${pathSegment(id)}/send", Invoice.serializer(), idempotencyKey = idempotencyKey)

    public suspend fun void(id: Long, idempotencyKey: String = c.newKey()): Invoice =
        c.send("POST", "/v1/invoices/${pathSegment(id)}/void", Invoice.serializer(), idempotencyKey = idempotencyKey)

    public suspend fun retrievePdf(id: Long): ByteArray = c.sendForBytes("GET", "/v1/invoices/${pathSegment(id)}/pdf")
}

public class MerchantsApi internal constructor(private val c: Transxact) {
    public suspend fun me(): Merchant = c.send("GET", "/v1/merchants/me", Merchant.serializer())
}

public class PayoutsApi internal constructor(private val c: Transxact) {
    public suspend fun list(limit: Int? = null, startingAfter: String? = null): PayoutList =
        c.send("GET", "/v1/payouts", PayoutList.serializer(), query = mapOf("limit" to limit, "starting_after" to startingAfter))

    public suspend fun retrieve(id: String): Payout = c.send("GET", "/v1/payouts/${pathSegment(id)}", Payout.serializer())
}
