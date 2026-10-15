# Service logs in a multi-tenant deployment

> **Status: built, collector off by default.** Done: the per-line tenant field; the `PROBE_SERVICE_LOGS` diagnostic;
> the **collector** (`dynamic.mapper.logs`, `service.logs.collector.enabled=false`); the **service endpoint**
> `GET /monitoring/service-logs` (admin only) and the **events-backed mode** of the *Monitoring → Service logs* page.
> Not done: a per-tenant opt-out and a per-tenant minimum level, and any run of the whole chain on a deployed tenant.

How a subscriber tenant sees the logs of `dynamic-mapper-service` when the service is deployed in
an enterprise (owner) tenant and subscribed by sub-tenants.

---

## Requirements

**What it is for.** Letting a sub-tenant administrator diagnose *their own* mappings and connectors
without access to the owner tenant.

**The problem.** The platform serves microservice logs (`/application/applications/{id}/logs/{instance}`)
only through the tenant that owns the application. A sub-tenant that merely subscribes gets
`403 Forbidden`, so the logs page is empty exactly for the tenants that cannot ask the operator.

- **A sub-tenant sees its own log lines, and only its own.** Log lines can carry device ids,
  payload fragments and connector hosts. A line attributable to tenant A must never be visible to
  tenant B, and a line that cannot be attributed to any tenant is visible to **no** sub-tenant. This is
  the central rule; everything else is subordinate to it.
- **Lines that are not tenant-specific** (JVM start-up, framework banner, shared infrastructure) stay
  in the owner tenant's native log and are not copied anywhere.
- **The owner tenant keeps using the native logs.** Nothing changes for it, and it needs no copy.
- **Collection is bounded.** Only lines at or above a configured minimum level are copied (default
  `WARN`; the service itself runs at `TRACE`, which must never be mirrored wholesale), each tenant's
  copy is limited in volume per interval, and old copies are deleted after a retention period
  (default 24 h). Hitting a limit is visible in the log view ("lines omitted"), not silent.
- **The copy is near-real-time, not exact.** Lines appear within one collection interval (default
  30 s). Lines from an instance that dies between two collections can be lost. Guaranteed delivery
  is a non-goal.
- **Collection can be switched off** by the operator for the whole service, and by a tenant for
  itself. A disabled tenant sees an explanation, not an empty editor.
- **The log page works the same way in both cases.** Same layout, instance select, time window,
  paging, auto refresh, download. The page picks its source itself; the user does not choose.
  It says which source it is showing, and shows distinct messages for *not enabled*, *no logs yet*
  and *not permitted*.
- **Logging must not feed on itself.** The act of collecting and storing logs must not produce
  enough log output of its own to be collected in turn.
- **Per-tenant isolation** applies to every piece of state — cursor, counters, switches, errors —
  as for the rest of the service.

**Non-goals.** Searching or analysing logs (the view is a window, not a log platform); logs of
other microservices; live streaming below the polling interval; redacting arbitrary secrets from
free text (the minimum level is the protection — see *Traps*).

---

## Implementation

### Flow

```mermaid
flowchart LR
  A[Owner tenant: platform log API] -->|every interval, own instance| B[Collector]
  B --> C[Attribute lines to a tenant]
  C --> D[Filter: level, volume cap, drop unattributed]
  D --> E[Write event chunk with the tenant service user]
  E --> F[(Sub-tenant events)]
  F --> G[Service logs page]
```

1. **Collector** (one scheduled component, owner-tenant context). Each service instance reads **only
   its own** instance's log with the owner tenant's credentials. No leader election, no duplicate
   copies, and replicas never coordinate. Cost: a dying instance loses its last interval.
2. **Cursor.** The collector reads `dateFrom = <dateTo of the newest copied chunk of this instance>`.
   The cursor is **derived from the events already written** (newest chunk per tenant and instance),
   not stored separately, so a restart resumes where the data ends and there is no extra state to
   clean on unsubscribe. With no chunk yet, start at *now − one interval*; never replay history.
   Follow `truncated` the same way as the Administration UI (repeat from the returned `dateTo`).
3. **Attribution** (see *Traps*). Each line is assigned the tenant in its `[tenant]` field, or dropped if
   the field is `-`. The collector parses that one field and nothing else.
4. **Filter and cap.** Drop below the minimum level; keep multi-line records (stack traces) glued to
   their first line; apply a per-tenant per-interval cap and record how many lines were omitted.
5. **Write.** One event **per tenant per chunk**, created with that tenant's service user (the same
   mechanism the existing service events use). Never one event per line: at `INFO` the rate would
   overwhelm the tenant's event API.

### Collector (implemented)

| Class | Role | Tested |
|---|---|---|
| `ServiceLogParser` | log text → records; the tenant comes from the `[tenant]` field only, continuation lines stay with their record, JVM/logback noise is dropped | unit |
| `ServiceLogPolicy` / `ServiceLogChunker` | minimum level, collector's own loggers excluded, per-tenant line cap with an `omitted` count, byte-bounded chunks, "already stored" skip; **a record reaches a tenant only if its own field names that tenant and the tenant is subscribed** | unit, incl. cross-tenant cases |
| `ServiceLogCollector` | one run: read window → parse → per-tenant chunks → store; cursor in memory (first run looks back 10 min, never replays history); a failed store holds the cursor (max 3 runs) and tenants already stored are skipped on retry; never throws; purge at most hourly; the owner tenant is not copied | unit with fake source/sink |
| `PlatformServiceLogSource` | reads **this instance's own** log with the bootstrap user; finds the application record that lists this pod | **not unit-tested** (HTTP glue) |
| `ServiceLogSourceLocator` | finds (reader) or creates (writer) the source object; per-tenant cache dropped on unsubscribe; linking it under the agent is cosmetic and may fail silently | unit |
| `EventServiceLogSink` | `d11r_serviceLog` events on that source object, written with that tenant's service user | **not unit-tested** (SDK glue) |
| `ServiceLogCollectorJob` | `@Scheduled`; the beans exist only when `service.logs.collector.enabled=true` | wiring test |

Settings (`application.properties`): `interval-ms` 30000, `min-level` WARN, `max-lines-per-tenant` 500,
`max-chunk-bytes` 65536, `retention-hours` 24, `look-back-minutes` 10, `max-pages` 5.

Known limits: two records with the same millisecond across a window boundary can be skipped or repeated; a pod
that dies between runs loses up to one interval; the line cap counts every line of a multi-line record; the
sink's dedupe reads the newest 20 `d11r_serviceLog` events of the tenant.

### Event shape

| Field | Value |
|---|---|
| `type` | `d11r_serviceLog` |
| `source` | the tenant's **service-log source** object (`d11r_serviceLogSource`, external id `c8y_Serial`/`d11r_serviceLogSource`, a child addition of the agent) — **not the agent**: the *Service events* page lists the agent's newest 1000 events without a type filter, so log events of up to 64 KB would push the real service events off it |
| `time` | `dateTo` of the chunk |
| `text` | short summary, e.g. `37 lines (2 WARN, 1 ERROR)` — what a generic events list shows |
| `d11r_serviceLog.instance` | instance name |
| `d11r_serviceLog.dateFrom` / `dateTo` | window covered; the paging key for the UI |
| `d11r_serviceLog.logs` | the lines, newline-joined; chunk is capped well below the event size limit (start with 64 KB) |
| `d11r_serviceLog.omitted` | lines dropped by cap, if any |

Chunks of one instance are contiguous (`dateFrom` of N+1 = `dateTo` of N, in the platform's own
terms), so the UI reassembles a window by concatenating chunks ordered by `d11r_serviceLog.dateFrom`.

### Retention

The collector deletes a tenant's `d11r_serviceLog` events older than the retention period when it
writes (one delete call per tenant per interval at most), so retention does not depend on the tenant
having a retention rule configured. Retention and level are service properties with a per-tenant
override in the service configuration, validated like the other service-configuration fields.

### Configuration

| Where | Setting | Default |
|---|---|---|
| Service (environment) | collection enabled | `false` until the credential question below is settled |
| Service (environment) | interval, minimum level, per-tenant cap, chunk size, retention | 30 s, `WARN`, 500 lines, 64 KB, 24 h |
| Tenant (service configuration) | opt out | off |

### UI (implemented)

[`ServiceLogsComponent`](../../dynamic-mapper-ui/src/monitoring/logs/service-logs.component.ts) tries the platform's log
first ([`NativeServiceLogsService`](../../dynamic-mapper-ui/src/monitoring/logs/native-service-logs.service.ts)) and, when
that is refused or the tenant sees no instance, uses the collected log
([`CollectedServiceLogsService`](../../dynamic-mapper-ui/src/monitoring/logs/collected-service-logs.service.ts)) — no user choice.
Both sit behind one interface with the **same window semantics**, so First / Previous / Next / Latest, the *From* field,
auto refresh and Download behave identically: neither bound = latest, `dateFrom` only = after (oldest first),
`dateTo` only = before. A badge says *Platform log* or *Collected log*; the collected view states that only the
tenant's own, filtered, limited-retention lines are shown, flags lines omitted by the cap, and shows a dedicated empty
state when nothing has been collected.

The endpoint ([`ServiceLogsController`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/controller/ServiceLogsController.java),
[`ServiceLogReader`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/logs/ServiceLogReader.java)) reads the
tenant's `d11r_serviceLog` events **as the tenant's service user** (so the caller's own event permission is irrelevant),
assembles up to 10 chunks per page in ascending order, never mixes instances (default: the newest chunk's), and reports
`more`, `empty` and `omitted`. Bounds are exclusive and refer to a chunk's end time; two chunks ending in the same
millisecond at a page boundary can be skipped.

### Traps

- **Attribution comes from one field, never from message text.** The service prefixes many messages
  with the tenant by hand (`"{} - ..."`), but not all, so text matching would mislabel or miss lines.
  Attribution uses the tenant in the logging context (MDC), printed as its own field:
  `... --- [   main] [t2050305588] d.m.core.CacheManager : Initialize inventoryCache` (`-` when absent).
  **Implemented:**
  - **Two keys, on purpose.** The Cumulocity SDK sets `tenant` inside its `ContextService` scopes, but
    its `leaveContext()` removes that key unconditionally and restores it only from an *enclosing SDK
    scope*. A worker of ours that calls `callForTenant(...)` therefore lost the tenant for every line
    after the call (seen as `[-]` on `virtThread-*` in a real log). So
    [`TenantLogContext`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/util/TenantLogContext.java)
    uses its own key (`mapperTenant`), which the SDK never touches.
  - [`TenantLogConverter`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/util/TenantLogConverter.java)
    (`%tenantId`) prints the SDK key when set (innermost scope),
    else ours, else `-`. `logging.pattern.correlation=[%-11tenantId] ` puts it between thread and logger, padded so the
    logger column stays aligned (the collector must trim the field).
  - **No duplicate tenant.** [`TenantMessageConverter`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/util/TenantMessageConverter.java)
    re-registers `%m` and drops a leading `"<tenant> - "` / `"Tenant <tenant> - "` **only if it equals the
    line's tenant**. The ~1,900 call sites were not edited: on a thread without a scope the prefix is
    still the only place the tenant appears, and removing it there would lose information. The side
    effect is a diagnostic — *a line that shows `[-]` and still starts with a tenant is a missing
    scope*. Once none remain on a real tenant, the prefixes can be deleted from the source.
  - **Where the scope is opened** (the tenant is a method argument on these threads, not an SDK
    context): the whole entry method of every callback that runs on a client library's thread — MQTT
    `accept`, Pulsar `received` (both callbacks), AMQP `handleDelivery` / `onMessage` — and
    `InboundMessageDispatcher.onMessage`, which every connector calls synchronously. Wrapping only the
    worker that runs *after* the hand-off is not enough: the callback's own "INITIAL" line and the
    dispatcher's "PROCESSING" / "Resolved n mapping(s)" lines are written on the library thread first
    (this was a real miss, seen in a deployed log). Also: the dispatcher workers; the shared
    virtual-thread pool ([`MdcPropagatingExecutorService`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/util/MdcPropagatingExecutorService.java))
    carries the submitter's tenant to its workers; the MQTT 3/5 connected/disconnected listeners and
    subscribe/unsubscribe completions; the four `CustomWebSocketClient` callbacks (which also covers the
    management and cache-inventory clients it calls back into). REST controllers need nothing: the
    SDK's request scope sets the tenant (`mcat-handler-*` lines are attributed).
  - **Library-internal lines stay unattributed**, e.g. the Pulsar client's `ConsumerStatsRecorderImpl`
    statistics on a `pulsar-timer-*` thread. The topic in the text names the tenant
    (`persistent://t2050305588/...`), but we do not attribute from text by design; these lines remain
    in the owner tenant's native log. Quiet them with the logger level if they are noise.
  - **Also scoped now:** the Kafka consumer loop (it is one long-lived thread per topic, so its whole body runs in
    the scope), the Pub/Sub receiver, the HTTP-polling hand-off (the poll timer thread has no tenant to pass on, so
    the task carries the connector's own), and every connector's housekeeping thread
    ([`TenantScheduledThreadPoolExecutor`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/util/TenantScheduledThreadPoolExecutor.java):
    a timer cannot inherit a scope from whoever scheduled the task, so the executor belongs to one tenant).
    The token-refresh and websocket-reconnect timers are cross-tenant by design and use `runForEachTenant`, whose
    SDK scope already attributes their lines.
  - **Still unscoped, safe by design:** HTTP connector *requests* arriving on Tomcat threads when the SDK request
    scope is absent, the websocket ping timer, the explorer sessions' consumer threads, and any call site not yet
    listed. Their lines are *unattributed* and so are dropped by the collector — missing coverage, never a leak.
    Find them with the diagnostic above (a `[-]` line that still starts with a tenant) and close each with
    `TenantLogContext.open`.
  - **Registration is programmatic, not in `logback-spring.xml`.**
    [`TenantLogging.register()`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/util/TenantLogging.java)
    (first line of `App.main`) puts `%tenantId` and the tenant-aware `%m` into logback's static
    converter registries. A first version used `<conversionRule>` in `logback-spring.xml` and, deployed,
    printed `[%PARSER_ERROR[tenantId]]` on every line: the platform's `/etc/<service>-logging.xml`
    also takes part in configuring logging, and a pattern parsed by a configuration that never saw the
    rule cannot resolve the word. The static registries apply to every `PatternLayout` in the JVM.
    A test that only used `logback-spring.xml` could not catch this — keep `TenantLogPatternTest`
    free of XML rules.
  - **Tests.** `TenantLogContextTest` (nesting, restore on exception, SDK key wins inside its scope and
    ours survives its exit, workers under interleaved tenants). `TenantLogPatternTest` boots Spring's
    real logging with `logback-spring.xml` and asserts the rendered lines: field, SDK/our key, prefix
    dropped only when it matches. **Still missing:** a test that two tenants' rendered lines are
    disjoint end to end (needs the collector).
- **Credentials of the owner tenant.** *Testable inside the service:* as an admin of the owner tenant, `POST /service/dynamic-mapper-service/operation` with `{"operation":"PROBE_SERVICE_LOGS"}` (operation `PROBE_SERVICE_LOGS`, `ServiceLogsProbe`) makes the logs call as that service user and returns `CAN_READ_LOGS`, `FORBIDDEN` (with the roles it holds), `OWNER_NOT_SUBSCRIBED` or `INCONCLUSIVE`. Refused to every other tenant. Reading the native log needs an owner-tenant identity with
  **Measured (2026-10-15, owner tenant t14070519, probe verdict `FORBIDDEN`):** the owner's service
  user `service_dynamic-mapper-service` authenticates and holds 25 roles — exactly the manifest's
  `requiredRoles` plus `ROLE_SYSTEM` — and **no** `ROLE_APPLICATION_MANAGEMENT_*`. Reading the logs of
  its own instance returned `403 Access Denied`. So, as deployed, the collector cannot read the native
  log through the service user. Untested: whether adding `ROLE_APPLICATION_MANAGEMENT_READ` is
  enough. It is **not a free change**: `requiredRoles` are granted to the service user in *every*
  subscribing tenant, so it widens that user's rights everywhere and each tenant has to accept it on
  subscription. This tilts the design toward the in-process alternative below, which needs no extra role.
  **From the public OpenAPI spec (`c8y-oas.yml`):** the logs endpoint is **not documented at all**, so its
  required role is unpublished (the neighbouring application read APIs use `ROLE_APPLICATION_MANAGEMENT_READ`,
  which is only a hint). `GET /application/currentApplication` and `GET /application/currentApplication/subscriptions`
  (`getApplicationUserCollectionRepresentation`) require the **bootstrap user**, not the service user —
  which is why a service user gets 403 on the former. `GET /application/applications/{id}/bootstrapUser`
  needs `ROLE_APPLICATION_MANAGEMENT_ADMIN`. **Untested lead:** the bootstrap user exists in the owner
  tenant only, so if *it* can read its own application's logs, no `requiredRoles` change (and no widening
  in subscribing tenants) is needed.
  **CORRECTION — the bootstrap user cannot read the logs.** An earlier note here said it could, based on a `c8y` call that
  returned 200. That call most likely ran as the operator's own logged-in `c8y` session (an admin with application-management
  rights), not as the bootstrap user. Measured since, **with `curl` and the bootstrap credentials: `403` with Basic
  authentication on the tenant's public host; inside the service: `403` for the service user and for the bootstrap user
  on the internal address, and `403` for the bootstrap user on the public host** (probe, same day). A bearer-token attempt
  also returned 403 but is **inconclusive** (the token may not have been issued). So **no credential the service holds can read
  its own native log**, and reading it through the platform needs a user with an application-management role.
  Consequences: the collector's current source (`PlatformServiceLogSource`) cannot work as is. Options: (1) grant
  `ROLE_APPLICATION_MANAGEMENT_READ` through `requiredRoles` — untested, and it widens the service user in every subscribing
  tenant; (2) **capture the lines inside the process** with a logging appender that already sees each line's tenant (the
  `[tenant]` field is the MDC set by `TenantLogContext`), which needs no platform permission, no credentials, no log parsing
  and no cursor — see *Alternatives considered*. Everything downstream of the source (policy, chunker, sink, source object,
  endpoint, page) is source-independent and stays.

  application read access. The service user of the owner tenant exists only if the owner subscribes
  to its own microservice, and its permissions are those of the manifest's `requiredRoles`. Verify
  that the roles needed for the log endpoint can be requested there; if not, the collection has to be
  moved out of the service (a small separate job in the owner tenant). This decides the default
  of *collection enabled*.
- **Feedback loop.** Writing an event can itself log (HTTP client, Spring, our own `DEBUG`). The
  collector's own package and the HTTP client are excluded from collection, and the collector logs
  at most once per interval.
- **Secrets in lines.** At `DEBUG`/`TRACE` the service logs payloads and topics. The minimum level
  is the protection, so it must not be lowered to `TRACE` by a tenant; only the operator may set
  below `INFO`.
- **Cost.** Each tenant gets one event write per interval plus one delete. With many tenants the
  interval must scale (batch tenants per tick); measure before lowering the 30 s default.
- **Tenant lifecycle.** Unsubscribe stops collection for that tenant immediately (nothing is
  cached, because the cursor is derived); the tenant's already written events stay under that
  tenant's own retention.

### Alternatives considered

- **In-process capture instead of reading the platform API.** A logback appender that already
  knows the tenant (MDC) could buffer per tenant and write events directly, with no owner-tenant
  credentials, no cursor and no attribution step by text. It is the better design if the credential
  question above has a bad answer, and it removes the round trip through the platform. It was not
  chosen as the starting point because the request was to reuse the native log as the source of truth.
- **Read the native log from the sub-tenant by granting access.** Not possible: the endpoint is
  tied to application ownership, not to a role the sub-tenant can hold.
- **One event per line.** Rejected: volume and event-API cost; also loses multi-line records.

### Open decisions

1. **Who may read the collected log — decided: `ROLE_DYNAMIC_MAPPER_ADMIN`.** Enforced in the service endpoint (403 for
   everyone else) and by hiding the navigation entry. **Limit:** the events are ordinary platform events, so a user who
   holds the platform's event-read permission can still list them through the platform's own API; the role controls
   access *through the mapper*, not the events themselves. A platform permission cannot be tied to an application role.
2. ~~Owner-tenant credentials~~ — resolved: the bootstrap user works (see *Traps*).
3. **Minimum level.** Today a service property (`service.logs.collector.min-level`, default `WARN`) set by the operator.
   Tying the *tenant's* choice to the same role means a per-tenant setting in the service configuration (editable by
   `ROLE_DYNAMIC_MAPPER_ADMIN` only) bounded below by an operator floor — not built. INFO lines can carry device payloads,
   which is the reason for the `WARN` default.
