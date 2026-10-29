# AGENTS.md

This is the Kotlin/JVM SDK for the Transxact API. Unlike `transxact-node` and `transxact-php`, it is **hand-written, not Fern-generated**. It mirrors the OpenAPI spec in `transxactfj/transxact` (`fern/openapi/openapi.json`).

- `Models.kt`, `Resources.kt`: update by hand when the spec changes (new fields, endpoints).
- `Webhooks.kt`: `Webhooks.verifySignature()`, HMAC-SHA256 over `timestamp.payload`, header `t=<ms>,v1=<hex>`, constant-time compare (ADR-0008, ADR-0010). Keep in sync with `transxact-node/src/webhooks.ts`.
- `Transxact.kt`: transport, auth, retries (408/429/5xx, `Retry-After`), error mapping.
- Enum-like fields are plain strings on purpose, so a new status never breaks older SDKs.
- Money is FJD cents (`Long`).

Server-side only: never add a flow that puts an `sk_` key in a client app.

Build and test: `./gradlew build`.
