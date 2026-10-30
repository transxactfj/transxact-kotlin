# Transxact Kotlin example

A small Ktor server that shows the whole flow with `transxact-kotlin`: a Pay page, a Merchant backend route that creates a Checkout Session (the price comes from the server, never the browser), a return page that asks Transxact for the real status, and a verified webhook.

It is a separate Gradle build that uses the SDK from the parent folder. It runs in **Test mode only**, so no real money moves.

## Run it

You need an `sk_test_…` key from the dashboard. From the repo root:

```bash
TRANSXACT_SECRET_KEY="sk_test_..." ./gradlew -p example run
```

Open http://localhost:8080, press **Pay**, and complete the practice payment on the hosted page.

Transxact only accepts `https://` return URLs, so on plain `http://localhost` the example sends none and you stay on the hosted page. To be sent back to the example's `/return` page (which shows the real status), run a tunnel and set `BASE_URL` to its https address, as in the next section.

## Try the webhook

Webhook endpoints must be public `https://` URLs, so run a tunnel, for example `cloudflared tunnel --url http://localhost:8080`. In the dashboard's **Webhooks** section add `https://<tunnel>/webhooks/transxact` as an endpoint, copy its signing secret, and restart with:

```bash
TRANSXACT_SECRET_KEY="sk_test_..." TRANSXACT_WEBHOOK_SECRET="..." BASE_URL="https://<tunnel>" ./gradlew -p example run
```

Press **Send test** in the dashboard, then take a test payment: the console prints `FULFIL order …` once per event.

## Environment

| Variable | Meaning |
| --- | --- |
| `TRANSXACT_SECRET_KEY` | Required. An `sk_test_` key |
| `TRANSXACT_WEBHOOK_SECRET` | Optional. Enables `/webhooks/transxact` |
| `BASE_URL` | Public URL used for the success and cancel links (default `http://localhost:$PORT`) |
| `PORT` | Default 8080 |
