# Connector: HTTP

The HTTP connector lets external systems POST payloads directly to a Cumulocity
microservice endpoint, which are then routed into inbound mappings by URL sub-path.
Implemented by
[`HttpClient`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/connector/http/HttpClient.java),
which extends `AConnectorClient` — see [connector-framework.md](connector-framework.md)
for the shared abstraction. Despite the class name, this is **not an HTTP client
library wrapper** — it has no `java.net.http`/Apache HttpClient/OkHttp imports at all.
It is a passive inbound receiver: the actual HTTP request handling is done by a
separate Spring MVC controller (`HttpConnectorController`), which calls
`HttpClient.onMessage()` after receiving a POST.

## Direction: inbound only

```java
public List<Direction> supportedDirections() {
    return Collections.singletonList(Direction.INBOUND);
}
```

`publishMEAO()` is a no-op that only logs a warning ("HTTP connector does not support
outbound publishing") — a mapping mistakenly configured for outbound-over-HTTP produces
only a warning log, not a visible mapping error. This is worth checking first if an
outbound HTTP mapping silently does nothing.

## Configuration (`ConnectorSpecification`)

Built via `ConnectorSpecificationBuilder.create("HTTP Endpoint", ConnectorType.HTTP)`:

| Property | Type | Required | Default | Notes |
|---|---|---|---|---|
| `path` | string, readonly | no | `/service/dynamic-mapper-service/httpConnector` | the fixed receiving endpoint |
| `supportsWildcardInTopicInbound` | boolean, readonly | no | `true` | |
| `supportsWildcardInTopicOutbound` | boolean, readonly | no | `false` | |
| `cutOffLeadingSlash` | boolean | no | `true` | user-configurable |

`singleton = true` — only one HTTP connector instance is allowed per tenant. There are
**no authentication-related properties at all** (no basic auth, bearer token, API key,
custom headers) — `isConfigValid()` simply returns `configuration != null`, since every
property has a default. Inbound auth is presumably handled at the Cumulocity
tenant/microservice level, not by this connector.

## Path → topic mapping

A POST to `.../httpConnector/temp/berlin_01` maps to mapping topic `temp/berlin_01`
(`pathToTopic()`/`cutOffLeadingSlash` control the exact string transform). "Subscribing"
to a topic for HTTP just means registering that a URL sub-path has an active inbound
mapping, for routing purposes — it is not a poll target, and there's no server socket
managed by this class (the embedded servlet container handles that).

## Connection lifecycle

`connect()`'s own comment states it plainly: "HTTP connector is always 'connected' -
it's a passive receiver." `initialize()`/`connect()`/`disconnect()` just flip status
flags; there's no socket, listener, or resource teardown to manage.
`isPassiveReceiver()` returns `true`, which lets inbound mapping deploy/activate take
effect immediately without requiring "connected" state first.
`connectorSpecificHousekeeping()` and `monitorSubscriptions()` are both empty — "all
subscriptions are always active, they just define routing rules."

## Error handling

`onMessage()` deliberately does **not** catch exceptions from the dispatcher — this is
intentional so `HttpConnectorController` can map them to an HTTP 400 response.
Previously swallowing exceptions here caused malformed payloads to always get a 200 OK
with no retry signal to the caller — a documented past bug, now fixed by letting the
exception propagate to the controller layer.

## Gotchas

- `publishMEAO()` silently no-ops rather than erroring (see above) — the most likely
  source of "nothing happens" reports for HTTP-outbound mappings.
- `singleton = true`, unlike most other connectors — you cannot configure two separate
  HTTP connector instances in one tenant.
- `getHttpPath()` falls back to reading the default value out of the connector's own
  spec if not present in configuration — an unusual self-referential default-lookup
  pattern not used by the other connectors.
