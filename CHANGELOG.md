# Changelog

## Unreleased

### Security
- Reject a non-HTTPS `baseUrl` unless it points at localhost, so the API key is never sent in cleartext.
- Percent-encode ids in request paths; `.`/`..`/blank ids are rejected.
- Webhook verification accepts multiple `v1` signatures (secret rotation), returns `false` for an empty secret.
- Unparseable error bodies are no longer included in exception messages (still available on `body`).

### Changed
- Requests that may already have been processed (unkeyed `POST`s such as `cancel`) are no longer retried on 5xx or
  connection errors; 429 is still retried. Retry backoff now has jitter.
- `User-Agent` version is taken from the build version.
