# Connector: WebHook

The WebHook connector publishes outbound mappings as plain HTTP requests to a
configured target URL — the reverse direction of the [HTTP connector](connector-http.md),
which only receives. Implemented by
[`WebHook`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/connector/webhook/WebHook.java),
using Spring's reactive `WebClient` (WebFlux), and extends `AConnectorClient` — see
[connector-framework.md](connector-framework.md) for the shared abstraction. Requests
are built reactively but `.block()`ed synchronously in `publishMEAO()`, so despite the
reactive API it behaves as a blocking client.

## Direction: outbound only

```java
public List<Direction> supportedDirections() {
    return Collections.singletonList(Direction.OUTBOUND);
}
```

`subscribe()`/`unsubscribe()` both throw `NotSupportedException("WebHook does not
support inbound mappings")`, and `monitorSubscriptions()` is an explicit no-op — "no
subscriptions to monitor."

## Configuration (`ConnectorSpecification`)

Built via `ConnectorSpecificationBuilder.create("Webhook", ConnectorType.WEB_HOOK)`:

| Property | Type | Required | Default | Notes |
|---|---|---|---|---|
| `cumulocityInternal` | boolean | no | `false` | toggles the "internal" mode (see [connector-mqtt-service.md](connector-mqtt-service.md)-style auto-wiring, below) |
| `baseUrl` | string | no | — | shown when `cumulocityInternal=false` |
| `authentication` | option | no | — | `Basic`/`Bearer`, shown when `cumulocityInternal=false` |
| `user` / `password` | string / sensitive | no | — | shown when `authentication=Basic` |
| `token` | string | no | — | shown when `authentication=Bearer` |
| `headerAccept` | string | no | `application/json` | shown when `cumulocityInternal=false` |
| `baseUrlHealthEndpoint` | string | no | — | health-check GET target, shown when `cumulocityInternal=false` |
| `headers` | map | no | `{}` | arbitrary custom headers |
| `supportsWildcardInTopicInbound` | boolean, readonly | no | `false` | |
| `supportsWildcardInTopicOutbound` | boolean, readonly | no | `true` | |

Supports POST/PUT/PATCH/DELETE; the mapping's publish topic is appended to `baseUrl`
(auto-inserting a `/` separator as needed).

## Publish (`publishMEAO`)

For each request in the batch:

- **Method**: taken from the request, dispatched to PUT/DELETE/PATCH, defaulting to
  POST.
- **URL**: `baseUrl` + the mapping's own publish topic (or the resolved default),
  joined via `buildFullPath()`, which special-cases a `baseUrl` that already carries
  query parameters by splitting on `?` and reinserting the path before them — a fix
  from issue #450 (commit `0300442fa`).
- **Headers**: `Accept: <headerAccept>` plus any custom `headers`, set once on the
  `WebClient` at connect time (not per request).
- **Auth**: Basic (base64 `user:password`) or Bearer (`token`), also set globally on
  the `WebClient` at connect time — no per-request auth logic.
- **Body**: the raw JSON payload, sent with `Content-Type: application/json`.
- **PATCH**: emulated as GET + deep-merge + PUT (since Cumulocity's REST API doesn't
  support arbitrary partial PATCH), stripping read-only fields
  (`self, id, lastUpdated, creationTime, owner, childDevices, childAssets,
  childAdditions, deviceParents, assetParents, additionParents`) before the PUT.
- **No retry/backoff**: a failed HTTP call is caught once, logged, and recorded on the
  request/context — there is no automatic retry loop for outbound publish, unlike some
  other connectors.

## `cumulocityInternal` mode

When `cumulocityInternal=true`, `configureCumulocityInternal()` auto-wires the
connector to point at Cumulocity's own internal API rather than an external URL:
authenticates as `<tenant>/<microservice-username>` using the microservice's own
bootstrap OAuth credentials (`configurationRegistry.getMicroserviceCredential(tenant)`),
hardcodes `authentication=Basic` and `baseUrl=http://cumulocity:8111` (the internal
service-to-service hostname, not publicly reachable), and derives the request's API
path from the publish topic via `APITopicUtil.deriveAPIFromTopic()` instead of a
literal URL append.

### `WebHookInternal` — a thin, more-locked-down subclass

[`WebHookInternal`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/connector/webhook/WebHookInternal.java)
extends `WebHook` and does not override `publishMEAO`/`connect`/`subscribe` — it only:

- Sets `connectorType = WEB_HOOK_INTERNAL`.
- Overrides the spec to expose a minimal, mostly `readonly(true).hidden(true)`
  property set (`cumulocityInternal` fixed to `true`, wildcard flags fixed to `true`
  both ways) — the user sees no configurable connection fields at all.
- Renames the spec's display name/description to "Cumulocity API."

All the actual "talk to Cumulocity's own API" logic lives in the shared parent class's
`configureCumulocityInternal()` — `WebHookInternal` just narrows what's user-visible.

## Gotchas

- No automatic retry/backoff on any outbound HTTP call — a transient failure is a
  single-attempt failure.
- A fixed-ordering bug is explicitly documented in code: `setConnected(false)` must be
  called *before* `updateStatusWithError(e)` in the `connect()` error path, otherwise a
  blank DISCONNECTED status event (fired by `setConnected`'s transition detection)
  would overwrite the FAILED status event carrying the real error message.
- The `baseUrl`-with-query-params handling (`buildFullPath()`) is a string-splitting
  patch (`baseUrl.split("\\?")`) — fragile if `baseUrl` ever contains an
  already-escaped `?`.
- `isConfigValid()` re-validates by iterating the connector spec's properties, which
  for `cumulocityInternal=true` have already been mutated in place by
  `configureCumulocityInternal()` — validity is effectively stateful/config-time-mutated
  rather than purely declarative.
- Git history shows URL/path construction bugs (query params, publishTopic-vs-API
  derivation, PATCH emulation) as the most repeatedly patched area of this connector —
  double-check the exact `WebHook.java` version in use if debugging a URL-construction
  issue rather than trusting older bug reports.
