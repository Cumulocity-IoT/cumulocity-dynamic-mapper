# Connector: REST Polling

The REST Polling connector periodically issues an HTTP GET against a configured URL and
feeds each successful response into the inbound mapping pipeline — the poll-driven
counterpart to the push-driven [HTTP connector](connector-http.md) and the inbound
counterpart to the outbound-only [WebHook connector](connector-webhook.md). Implemented by
[`HttpPollingConnector`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/connector/httppolling/HttpPollingConnector.java),
using Spring's reactive `WebClient` (blocked synchronously per poll), and extends
`AConnectorClient` — see [connector-framework.md](connector-framework.md). Original request
and design discussion: `attic/feature/http-polling/REQUIREMENT.MD` and `PLANNING.md`.

---

## Requirements

**What it is for.** Sources that only expose a REST API and cannot push data — no broker,
no webhook capability on their end — where the alternative would otherwise be a
custom microservice that polls and forwards into Cumulocity or the mapper.

- **Inbound only.** There is no way to "publish" to a polling connector; the reverse
  direction (Cumulocity → external REST API) is the [WebHook connector](connector-webhook.md).
- **Polling belongs to the connector, not the flow.** A generic "on interval" trigger at
  the mapping/flow level would only ever be meaningful for HTTP and dead weight for every
  other connector type — this is a deliberate architectural choice, not an oversight (see
  `PLANNING.md`'s evaluation section).
- **One HTTP call per mapping, not one shared call per connector.** Each mapping deployed
  to the connector gets its own independently scheduled poll job, keyed by the mapping's
  topic — the same way one MQTT connector already hosts many independent topic
  subscriptions. **Known v1 tradeoff**: if multiple mappings happen to target the same URL
  and interval, calls are not deduplicated.
- **`url` and `pollIntervalSeconds` are connector-level, not per-mapping**, despite the
  "one call per mapping" model above — every mapping on one connector instance polls the
  *same* URL. Getting different URLs/intervals requires separate connector instances, one
  per target. This is a deliberate v1 simplification, not the originally-scoped per-mapping
  design (`Mapping` has no generic per-connector-type properties bag to hold that without a
  model change) — flagged as a possible follow-up.
- **A hard floor of 30 seconds on `pollIntervalSeconds`.** Enforced in `isConfigValid()`,
  to protect both the polled endpoint and this service from too many concurrent poll jobs.
- **Delivery is at-least-once, GET only, no pagination.** v1 is a plain "GET full response
  every interval." Pagination and incremental/cursor-based fetching (e.g. `?since=<ts>`)
  are explicitly out of scope for v1 — see `PLANNING.md`'s "v2 candidate design" section
  for the deferred design (separates intra-poll pagination from cross-poll incremental
  fetch, and the cursor-persistence problem that design would need to solve).
- **Failure escalates through the same connector health mechanism as every other
  connector**, not a bespoke one: repeated poll failures move the connector through
  `RETRYING` (with backoff) to `FAILED`, visible in the same status UI/API as an MQTT
  disconnect.

---

## Implementation

### Direction: inbound only

```java
public List<Direction> supportedDirections() {
    return Collections.singletonList(Direction.INBOUND);
}
```

`publishMEAO()` logs a warning and returns rather than throwing — nothing should ever
route an outbound publish to this connector type in practice, but a hard throw isn't
warranted either (mirrors `HttpClient`'s inbound-only pattern).

### "Subscribe" = register a poll job, not a broker subscription

`subscribe(topic, qos)` is called once per distinct inbound mapping topic (the same dedup
semantics `MappingSubscriptionManager` applies for MQTT). It does not talk to any external
system — it registers a **self-rescheduling** poll chain for that topic on a per-instance
`ScheduledExecutorService` (`pollScheduler`, 4 daemon threads, created lazily via
`ensureScheduler()`):

```
subscribe(topic) → pollTasks.put(topic, null) → scheduleNextPoll(topic, 0)
                                                       ↓
                                                 executePoll(topic)
                                          success ↓         ↓ failure
                                 scheduleNextPoll(topic,    handlePollFailure(topic, e)
                                   pollIntervalMs)             → scheduleNextPoll(topic, backoffMs)
```

Each run reschedules itself rather than using a fixed-rate schedule — this is what lets the
delay vary per attempt (backoff) while still respecting the configured interval on success.
`unsubscribe(topic)` cancels that topic's `ScheduledFuture` and removes it from `pollTasks`;
`scheduleNextPoll`/`executePoll` both check `pollTasks.containsKey(topic)` before acting, so
an unsubscribe racing with an in-flight run is a no-op rather than a leaked reschedule.

### Poll execution and dispatch

On a successful (2xx) response, `executePoll()` builds a `ConnectorMessage` (payload = raw
response body bytes, `topic` = the mapping topic, `tenant`, `connectorIdentifier`,
`sendPayload=true`) and calls `dispatcher.onMessage(connectorMessage)` — the same
fire-and-forget dispatch `AbstractMqttCallback`/`KafkaClientV2` use, simpler than MQTT's
QoS-ack handling since polling has no broker redelivery to coordinate with.

### Configuration (`ConnectorSpecification`)

Built via `ConnectorSpecificationBuilder.create("REST Polling", ConnectorType.REST_POLLING)`:

| Property | Type | Required | Default | Notes |
|---|---|---|---|---|
| `url` | string | yes | — | The exact GET target — no path is appended from the mapping topic |
| `pollIntervalSeconds` | numeric | no | `60` | Hard minimum `30`, enforced in `isConfigValid()` and defensively re-clamped at runtime in `getEffectivePollIntervalSeconds()` |
| `authentication` | option | no | — | `None` / `Basic` / `Bearer` |
| `user` / `password` | string / sensitive | no | — | shown when `authentication=Basic` |
| `token` | sensitive | no | — | shown when `authentication=Bearer` |
| `headers` | map | no | `{}` | Additional static headers sent with every poll request |
| `supportsWildcardInTopicInbound` / `Outbound` | boolean, readonly | no | `false` | Each "topic" is a concrete poll-job key, not a broker wildcard pattern |

`isConfigValid()` also requires `user`+`password` (Basic) or `token` (Bearer) to be
non-empty when the corresponding `authentication` value is selected.

### HTTP client

A small, self-contained `buildWebClient()`/`executeGet()` — **not** a call into
`WebHook.java`'s equivalent methods, which are `private` and outbound-shaped (method/path/
payload resolved from a `ProcessingContext`). Duplicating ~30 lines of GET-only client setup
(Basic/Bearer auth header, custom headers, `Accept: application/json` default) was judged
less invasive than changing `WebHook`'s visibility or extracting a shared base class for a
single caller. `executeGet()` maps both 4xx and 5xx responses to a `ConnectorException`,
bounded by a fixed 30s `REQUEST_TIMEOUT` independent of any TCP-level connect/socket
timeout.

### Error handling, backoff, and health status

Reuses the existing `ConnectionStateManager`/`ConnectorStatus` mechanism as-is — no new
status/alarm plumbing. A connector-wide (not per-topic) `AtomicInteger consecutiveFailures`
drives it:

- **Success** → reset the counter, `updateStatus(CONNECTED, ...)`.
- **Failure, attempt ≤ 5** → `updateStatusRetrying(exception, delaySeconds)` (`RETRYING`).
- **Failure, attempt > 5** → `updateStatusWithError(exception)` (`FAILED`).

Backoff is linear-capped, mirroring MQTT's reconnect shape but sized to the poll interval
rather than MQTT's fixed constants: `delay = min(attempt * pollIntervalMs, 300_000ms)`.
Because the failure counter and resulting status are connector-wide, one struggling mapping
sharing a connector with others affects the reported status for all of them — an accepted
consequence of `url` being connector-level (there is realistically one target endpoint per
connector instance in the current design).

### Lifecycle / cleanup

`disconnect()` unconditionally cancels every outstanding poll job for the connector
(`cancelAllPollTasks()`) before flipping state, including in the "already disconnected"
branch, defensively. `connectorSpecificHousekeeping()` is a second safety net: if the
connector reports not-connected but `pollTasks` is non-empty, it force-cancels everything.
`close()` is overridden to call `super.close()` (which takes the connect/disconnect lock and
runs `disconnect()`) and then fully shuts down `pollScheduler` via `shutdownNow()` — this
is reached from tenant cleanup via `BootstrapService.cleanTenantResources()` →
`ConnectorRegistry.unregisterAllClientsForTenant()` → `AConnectorClient.stopHousekeepingAndClose()`
→ `close()`, confirmed during implementation (this chain was an open question in
`PLANNING.md` before the connector was built).

### Message Explorer

Works with no connector-specific code: `executePoll()` dispatches every successful poll via
`dispatcher.onMessage(...)` (`CamelDispatcherInbound`), which unconditionally calls
`connectorClient.notifyExplorerListeners(message)` — the same path every other inbound
connector uses, so a session sees every poll response automatically. Starting a session
relies on `AConnectorClient`'s default `subscribeExplorer()`/`unsubscribeExplorer()`, which
this class doesn't override — they delegate straight to `subscribe()`/`unsubscribe()`.

This has a real cost that doesn't exist for broker-based connectors: opening an explorer
session on a topic with **no mapping already deployed on it** schedules a genuine poll job
(`subscribeExplorer` skips the call only if `mappingSubscriptionManager.isTopicSubscribed()`
is already true for that topic) — i.e. real periodic GET requests against the external
endpoint at the connector's configured interval, for as long as the session stays open (up
to the 10-minute idle TTL if not explicitly stopped). An MQTT explorer session by contrast
just adds a broker subscription, essentially free.

### Gotchas

- **`url`/`pollIntervalSeconds` are connector-level**, not per-mapping, despite each
  mapping getting its own scheduled poll job — see "Requirements" above. Don't assume
  different mappings on one connector instance can poll different endpoints.
- **No pagination/cursor support** — a poll that needs multiple pages to drain new data, or
  needs to avoid re-fetching unchanged data, isn't handled; every poll fetches the full
  response fresh. See `PLANNING.md`'s v2 section if implementing this later — it flags the
  cursor-persistence-across-restarts problem specifically.
- **Backoff/failure state is connector-wide**, not per-mapping-topic — a single failing
  mapping's backoff affects the connector's overall reported status.
- **No frontend-specific work was needed**: connector config forms are data-driven off
  `ConnectorSpecification`, so `REST_POLLING` appears in the connector-type dropdown
  automatically once registered in `ConnectorRegistry`/`ConnectorClientFactory` — verify
  this holds if the UI's dropdown ever turns out to hardcode a connector-type list.
- **Test target**: `resources/testing/environments/http-polling/` is a small Flask
  microservice deployed as its own Cumulocity microservice (`http-polling-mock`) that
  serves a synthetic, changing reading per request and optionally requires Basic/Bearer
  auth, plus a `/requests` inspection endpoint (the inbound equivalent of the WebHook test
  environment's RequestBin) — see its README for setup.
