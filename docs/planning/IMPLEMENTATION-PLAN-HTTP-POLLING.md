# Implementation Plan: REST Polling Connector

**Status:** Implemented
**Companion to:** [connector-http-polling.md](../feature/connector-http-polling.md) — the
authoritative Requirements/Implementation page; this document is the point-in-time design
journal (evaluation, alternatives considered, decisions and their reasoning, verified-against-code
research) that produced it, kept for context on *why*, not as the current source of truth on
*what*. Originally `attic/feature/http-polling/PLANNING.md`, moved here 2026-09-21 so it travels
with the repository instead of living in a gitignored scratch directory.

---

## Evaluation

The request is legitimate and the internal note already reaches the right architectural
conclusion: **polling belongs at the connector level, not the flow/mapping level**. A generic
"trigger" flow step would only be meaningful for HTTP and would be dead weight for MQTT/Kafka/etc.
This matches how Dynamic Mapper is already structured: connectors own transport-specific behavior
(`subscribe`/`publish`), mappings stay transport-agnostic.

The codebase already has every building block needed for this — it is a scoped, additive
connector, not a new subsystem.

## Proposed solution: `REST_POLLING` connector type (inbound)

1. **New connector class** `connector/httppolling/HttpPollingConnector.java` extending
   `AConnectorClient` (same base as MQTT/Kafka/Webhook), registered as a new
   `ConnectorType.REST_POLLING` in `ConnectorRegistry`.

2. **"Subscribe" = register a poll job, not a topic.** Where MQTT's `subscribe(topic, qos)`
   subscribes to a broker topic, this connector's `subscribe(topic, qos)` treats `topic` as a
   logical mapping identifier and instead schedules a per-mapping (or per-connector)
   `ScheduledFuture` via a `ScheduledExecutorService`. This mirrors the existing precedent in
   `SparkplugCertificateManager.schedulePeriodicBirthCertificates` — a connector already owns a
   periodic background task today, so this isn't a new pattern.

3. **Reuse Webhook's HTTP client plumbing.** `WebHook.java`'s `buildWebClient()` /
   `executeHttpRequest()` (auth, headers, base URL from `ConnectorConfiguration.properties`) is
   directly adaptable for outbound GET calls — same request-building logic, just triggered by a
   timer instead of by an outgoing `ProcessingContext`.

4. **Feed results into the existing pipeline unchanged.** Each successful poll response is
   wrapped into a `ConnectorMessage` (payload, topic/mapping-id, tenant, connectorIdentifier) and
   passed to `GenericMessageCallback.onMessage(...)` — exactly what `AbstractMqttCallback` does
   today. No changes needed to `ProcessingContext`, mapping engine, or dispatcher.

5. **Config additions** via `ConnectorSpecificationBuilder`, same pattern as
   `WebHook.createConnectorSpecification()`: `url`, `method` (fixed to GET, or configurable for
   POST-with-body polling), `headers` (MAP), auth (reuse Webhook's existing auth property types),
   and new fields `pollIntervalSeconds` and optionally pagination/`nextPageField`.

6. **Multi-tenancy.** Since `AConnectorClient` instances are already tenant-scoped, the
   `ScheduledFuture` is simply held as an instance field and cancelled in `disconnect()` /
   `connectorSpecificHousekeeping()`, consistent with how `BootstrapService.cleanTenantResources()`
   already tears down connector clients.

## Supporting research (connector architecture)

1. **Connector extension point** (docs/backend/conventions.md:53-60, docs/extensions.md)
   Base class: `dynamic.mapper.connector.core.client.AConnectorClient` (abstract,
   `dynamic-mapper-service/.../connector/core/client/AConnectorClient.java`, ~1500 lines). Must
   implement:
   - `boolean initialize()`, `void connect()`, `void disconnect()` (lines 292-296)
   - `boolean isConfigValid(ConnectorConfiguration)` (313)
   - `void publishMEAO(ProcessingContext<?>)` for outbound (315)
   - `Boolean supportsWildcardInTopic(Direction)`, `List<Direction> supportedDirections()` (328, 346)
   - `protected abstract void subscribe(String topic, Qos qos)` / `unsubscribe(...)` (1269, 1274)
   - `protected abstract void connectorSpecificHousekeeping(String tenant)` (890)

   Also provide a `ConnectorSpecification` (config schema) and register the new `ConnectorType`
   enum value in `connector/core/client/ConnectorType.java` — current values as of this plan:
   `MQTT, CUMULOCITY_MQTT_SERVICE, KAFKA, HTTP, WEB_HOOK, WEB_HOOK_INTERNAL, PULSAR,
   CUMULOCITY_MQTT_SERVICE_PULSAR, AMQP_091, AMQP_10, GOOGLE_PUBSUB, TEST` — add `REST_POLLING`,
   via `ConnectorRegistry`.

   *Verified 2026-09-21: all abstract hooks above confirmed present at the stated lines;
   `subscribe`/`unsubscribe` are `protected`, matching the intended override.*

2. **Webhook (outbound) as REST analog**
   `connector/webhook/WebHook.java` (909 lines) extends `AConnectorClient`, uses Spring
   `WebClient` (`protected WebClient webhookClient`, built in `buildWebClient()` line 251, reading
   headers/auth from `connectorConfiguration.getProperties()`). `publishMEAO()` (351) resolves
   method/path/payload from `ProcessingContext` and calls
   `executeHttpRequest(RequestMethod, path, payload, context)` (515) which returns
   `Mono<ResponseEntity<String>>`. `WebHookInternal.java` (141 lines) subclasses it for internal
   C8y API calls. This WebClient setup (auth, headers, base URL) is directly reusable for building
   outbound GET calls in a poller. Note: there's also `connector/http/HttpClient.java`
   (`ConnectorType.HTTP`) which is an inbound HTTP connector, but it's a push/webhook *receiver*
   endpoint, not a poller — not directly reusable for polling logic itself, though its
   `ConnectorSpecification` config pattern is a useful template.

   *Verified 2026-09-21: `buildWebClient()` (line 251) and `executeHttpRequest(...)` (line 515)
   confirmed present, but both are `private`. Direct reuse needs either a visibility change or
   extracting the shared WebClient-building/auth/header logic into a common base class or util
   that both `WebHook` and the new polling connector call — not a straight subclass/call.*

3. **Dispatch into processing pipeline**
   Interface `dynamic.mapper.connector.core.callback.GenericMessageCallback`
   (`connector/core/callback/GenericMessageCallback.java`) — key method:
   `ProcessingResultWrapper<?> onMessage(ConnectorMessage message)`. `ConnectorMessage`
   (`connector/core/callback/ConnectorMessage.java`) carries `payload (byte[])`, `topic`,
   `tenant`, `key`, `clientId`, `connectorIdentifier`, `sourceId`. MQTT's
   `AbstractMqttCallback.java:184` shows the pattern:
   `genericMessageCallback.onMessage(connectorMessage)`. A polling connector would construct a
   `ConnectorMessage` per poll response and invoke this same callback — no new pipeline entry
   point needed. Inbound dispatch also goes through `processor/inbound/CamelDispatcherInbound`
   (referenced in `WebHookInternal` constructor).

   *Verified 2026-09-21: `ConnectorMessage` also has `headers` (String[]) and `sendPayload`
   (Boolean) fields beyond what was originally listed — check whether either is relevant when
   building the message from a poll response (e.g. propagating response headers).*

4. **Existing scheduling infra**
   - Spring `@Scheduled` used at service level: `core/BootstrapService.java:659`
     (`cron = "0 * * * * *"`, per-minute housekeeping) and `:672` (hourly),
     `explorer/ExplorerService.java:408` (`@Scheduled(fixedDelay=...)` watchdog).
   - Per-component `ScheduledExecutorService`/`ScheduledFuture` pattern already exists for
     connector-adjacent background work: `notification/service/TokenManager.java`,
     `notification/GroupCacheManager.java`, `notification/service/NotificationConnectionManager.java`,
     and notably `connector/mqtt/SparkplugCertificateManager.java`
     (`schedulePeriodicBirthCertificates(ScheduledExecutorService scheduler)`) — a precedent for a
     connector-owned periodic task. `AConnectorClient` itself uses `CompletableFuture` task fields
     (`initializeTask`, `connectTask`, etc.) and a `monitorSubscriptions()`/housekeeping loop (355,
     730) that a poll-interval trigger could hook into, supporting the "onInterval belongs at
     connector" design.

5. **Config schema definition**
   Per-connector-type config fields declared via
   `ConnectorSpecificationBuilder`/`ConnectorPropertyBuilder`
   (`connector/core/ConnectorSpecificationBuilder.java`, `ConnectorPropertyBuilder.java`,
   `ConnectorProperty.java`, `ConnectorPropertyType.java`) inside each client's
   `createConnectorSpecification()` method (e.g. `WebHook.java:847-891` defines `headers` as
   `MAP_PROPERTY`, method/URL/auth properties similarly). A polling connector would add properties
   like `pollIntervalSeconds` (NUMBER/STRING), `url`, `method` (fixed GET), `headers` (MAP),
   `authType`/credentials, and pagination fields the same way — each
   `.property(name, ConnectorPropertyBuilder...)` call.

6. **Multi-tenancy**
   docs/backend/conventions.md:66-120 is directly relevant: service is a singleton per JVM serving
   many tenants; any per-tenant scheduled task/executor must be tenant-keyed and torn down on
   tenant cleanup. A per-tenant, per-connector-instance poll timer (e.g. a `ScheduledFuture` held
   on the `AConnectorClient` instance) fits this since connector clients are already tenant-scoped
   instances; ensure `disconnect()`/cleanup cancels the scheduled task, and credentials used in the
   poll's HTTP client must be built/keyed per the conventions doc's "Credentials deserve extra
   care" section (107-112).

   *Verified 2026-09-21: `BootstrapService.cleanTenantResources()` (private, line 171) was only
   confirmed to disconnect/unsubscribe the `NotificationSubscriber` in the lines inspected
   (174-190) — connector-client teardown for this path was not directly confirmed there and needs
   a targeted check during implementation (read the rest of that method, or confirm connector
   disconnect happens via a different tenant-cleanup path) before relying on it to cancel the poll
   `ScheduledFuture`. Don't assume it "just works" — verify or add the teardown call explicitly.*

## Resolved decisions

- **One HTTP call per distinct topic, not per connector — corrected 2026-09-21.** Reuse the
  existing `subscribe(topic, qos)`/`unsubscribe` lifecycle: each distinct inbound *topic* gets
  its own scheduled poll job (keyed by topic), the same way one MQTT connector already hosts many
  independent topic subscriptions. No change to `AConnectorClient`'s contract. Mappings sharing a
  topic share that one poll job — `MappingSubscriptionManager.subscribeToNewTopics()` only
  invokes `subscribe()` for the first mapping on a given topic, so same-topic calls **are**
  deduplicated by construction. (Originally documented here as the opposite — "not
  deduplicated" — which was inaccurate; a Copilot PR review caught the discrepancy between that
  claim and the actual `subscribe()`-once-per-topic behavior described two bullets above.)
- **Interval floor: 30 seconds.** Enforce as a hard minimum for `pollIntervalSeconds`, validated in
  `isConfigValid(ConnectorConfiguration)`, to prevent tenants from hammering external APIs or
  overloading the service with many concurrent per-mapping poll jobs. `pollIntervalSeconds` remains
  connector-level (every mapping on one connector instance shares one interval) — unlike `url`,
  see below.
- **`url` is a base URL; the mapping topic is the path — RESOLVED 2026-09-21, originally deferred
  here.** The v1 implementation initially made `url` the exact poll target (connector-level, one
  URL per connector instance) rather than the per-mapping design sketched above, because `Mapping`
  had no generic per-connector-type properties bag to hold a per-mapping URL without a model
  change. Reviewer feedback (paraphrased from German: *"do you define the full API in the
  connector, or just the base URL and extend it per mapping? Otherwise you need a new connector
  instance for every URL"*) prompted revisiting this — resolved by reusing the mapping's existing
  `topic` field as a URL path appended to `url`, the same convention the Default HTTP Connector
  already uses for inbound (`.../httpConnector/<MAPPING_TOPIC>`). No `Mapping` schema change
  needed after all — the "per-connector-type properties bag" problem doesn't apply because the
  topic field already existed for an unrelated reason (the poll-job key) and doubles for this too.
  `pollIntervalSeconds` is not addressed by this fix and remains connector-level.

## Scope / open questions to settle before implementing

- **Pagination/incremental fetch** (e.g. `?since=<lastTimestamp>`) — out of scope for v1, see
  candidate design below (now implemented — see "v2 candidate design" section for the
  as-built description). v1 itself remains a plain "GET full response every interval" with
  neither feature enabled by default.

This is a scoped, additive connector (no core pipeline changes) — reasonable to size at roughly
what the Webhook connector took, plus a scheduler layer.

## Resolved: error/backoff & health status on repeated failed polls

Reuse the existing connector health mechanism as-is — no new status/alarm plumbing needed.

- Every `AConnectorClient` already owns a `ConnectionStateManager`
  (`connector/core/client/ConnectionStateManager.java`), which is what MQTT uses to track and
  surface connection health. Give the polling connector the same instance-per-client manager.
- `ConnectorStatus` (`model/status/ConnectorStatus.java`) already has a `RETRYING` value, documented
  for exactly this case: a transient error delaying recovery, with a retry already scheduled — as
  opposed to `FAILED` (fully given up). Use it as-is rather than adding a new status.
- **Pattern**: after each failed poll, call `connectionStateManager.updateStatusRetrying(lastException,
  nextRetryDelaySeconds)` while under a configurable consecutive-failure threshold (e.g. N=5).
  Once the threshold is exceeded, escalate with `updateStatusWithError(lastException)` (→ `FAILED`),
  matching how MQTT escalates after exhausting its reconnect attempts. A successful poll resets the
  consecutive-failure counter and calls `updateStatus(CONNECTED, ...)`.
- **Backoff shape**: match MQTT's linear-capped backoff for consistency —
  `delay = min(attempt * STEP_MS, MAX_MS)` (MQTT uses `AMQTTClient.RECONNECT_DELAY_STEP_MS = 10_000`,
  `RECONNECT_DELAY_MAX_MS = 300_000`) — but sized to the polling use case: step ≈ the mapping's
  configured `pollIntervalSeconds` (respecting the 30s floor), cap at a few minutes, so a struggling
  endpoint doesn't get hammered faster than its own configured interval.
- This automatically surfaces in the existing UI status badge and persisted connector status
  history/event mechanism (`connectorRegistry.getConnectorStatusMap`, `c8yAgent.updateConnectorStatusEvent`)
  with zero new frontend work.

*Verified 2026-09-21 against `connector/mqtt/` (MQTT3Client/MQTT5Client `connectMqttWithRetry`,
`AMQTTClient` constants) and `ConnectionStateManager`/`ConnectorStatus`.*

## v2 candidate design: pagination / incremental fetch

Two distinct mechanisms get conflated by "pagination" — worth splitting them cleanly.

**A. Incremental fetch (cross-poll cursor) — IMPLEMENTED 2026-09-21.** Avoids re-fetching data
already processed between poll cycles. Implementation notes (see `HttpPollingConnector.java` and
`docs/feature/connector-http-polling.md` for the authoritative description):
- Config: `cursorParam` (query parameter name, e.g. `since`) and `cursorExtractionExpression`
  (JSONata evaluated against each response to compute the next cursor, e.g. `items[-1].timestamp`).
  Both empty (the default) = v1 behavior, plain full-response poll, nothing sent/extracted/stored.
  **Deviation from the original sketch above**: dropped the separate `cursorTemplate` string
  ("e.g. `?since={{cursor}}`") in favor of `WebClient`'s `UriBuilder.queryParam(cursorParam,
  cursor)` — properly URL-encodes the value and composes correctly with an existing query string
  on `url`, rather than a fragile manual string-substitution template.
- **State persistence — resolved, reused an existing pattern rather than inventing one.**
  `MappingStatus` (already inventory-persisted per mapping, survives restarts, flushed on the
  existing periodic/debounced housekeeping cycle — the same mechanism a message counter uses)
  gained a `cursor` field. A topic resolves back to its `Mapping` via
  `mappingService.getCacheMappingInbound(tenant)` (matched by `mappingTopic`); if several mappings
  share one topic they already share this connector's one poll job for it, so they share its
  cursor too — no separate storage needed.
- Cursor is advanced only *after* `dispatcher.onMessage(...)` for that poll succeeds — never
  speculatively before — so a crash re-polls the same window rather than silently skipping it.
- A response the extraction expression can't evaluate logs a warning and leaves the cursor
  unchanged (degrades to "that poll behaved like v1"), rather than failing the poll outright.

**B. Intra-poll pagination (draining multiple pages per cycle) — IMPLEMENTED 2026-09-21.** One
poll interval can now need several HTTP calls before all new data is retrieved.
- Config, matching the original sketch: `paginationMode` (`None` | `NextLinkHeader` |
  `NextFieldInBody` | `PageNumber`), `maxPagesPerPoll` (default 20, safety cap regardless of
  whether more pages are actually available), plus mode-specific `pageParam`,
  `nextPageExpression` (JSONata), and `pageStartValue`.
- Each mode has its own **data-driven stop condition** from the response itself, deliberately, so
  no separate "has more pages" config is needed: `NextLinkHeader` stops when the RFC 5988
  `Link: rel="next"` header is absent; `NextFieldInBody` stops when `nextPageExpression` returns
  nothing; `PageNumber` stops when a page's body is an empty `[]`/`{}`.
- `NextLinkHeader`'s continuation is an absolute URI handed back by the server — `executeGet()`
  hits it directly rather than composing query params onto `url`, since the server already
  encodes everything (including, typically, its own cursor-equivalent) into that URL.
- Each page's response feeds into the pipeline as its own `ConnectorMessage` (no buffering of
  whole result sets in memory) — `executePoll()`'s loop calls `executeGet()` per page, dispatches,
  then decides whether to continue based on the active mode, until no next-page signal or
  `maxPagesPerPoll` is hit.

**Failure semantics, resolved as designed**: the cursor from (A) advances **after every page**,
not once at the end of the whole poll — so if page 3 of 5 fails, pages 1–2's cursor progress is
already recorded and the next poll resumes from there rather than re-fetching (and re-dispatching)
already-processed pages, or silently losing the unprocessed remainder. This is the resolution to
the "must not advance past the last fully processed page" requirement from the original sketch —
achieved by moving the `advanceCursor()` call inside the page loop, immediately after each
successful dispatch, rather than only once outside it.

Persistent per-mapping state (the part flagged as the crux above) turned out to be cheap — reuse
of `MappingStatus`, not new infrastructure. Both parts of the original v2 sketch are now
implemented; see `docs/feature/connector-http-polling.md` for the authoritative description and
any gotchas found since.
