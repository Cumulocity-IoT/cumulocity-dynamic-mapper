# Test Setup: Cumulocity Backend Temporarily Unavailable

This document describes how to reproduce and verify the fix for a class of connector-reconnect
failures that occur when the Cumulocity backend is temporarily unreachable (platform maintenance,
gateway hiccups, expired/invalid tokens on WebSocket reconnect).

## Observed Issue

After an MQTT broker disconnect/reconnect, the MQTT client itself reconnects cleanly, but the
Dynamic Mapper remains only partially functional afterwards. Two distinct problems were observed
in the same incident:

1. **WebSocket reconnect fails with 401.** The management/cache-inventory notification
   WebSockets (`ManagementSubscriptionClient`, `CacheInventoryUpdateClient`) fail their reconnect
   handshake as unauthorized, repeating roughly every minute:
   ```
   Invalid status code received: 401 Unauthorized
   ```
   Root cause: the WebSocket library closes the connection with status code `1002` when the HTTP
   upgrade is rejected with `401`, not with `401` itself. The disconnect handler only checked for
   `statusCode == 401`, so it never detected the unauthorized state, kept reusing an expired token
   on every reconnect attempt, and looped indefinitely.

2. **Subscription/mapping initialization fails with 502.** After the MQTT connect succeeds, the
   Dynamic Mapper tries to reload its mappings from Cumulocity and rebuild its
   subscriptions/caches. This REST call goes through Cumulocity/OpenResty and can return
   `502 Bad Gateway` instead of JSON during a brief platform outage:
   ```
   Error initializing subscriptions after connect: Http status code: 502
   502 Bad Gateway openresty
   ```
   Call chain:
   ```
   initializeSubscriptionsAfterConnect
   -> rebuildMappingCaches
   -> getMappings
   -> MappingRepository.findAll
   -> Cumulocity SDK GET
   -> 502 Bad Gateway
   ```
   Root cause: the Dynamic Mapper assumed the Inventory API is always reachable once MQTT is
   connected. A transient `5xx` during that one REST call aborted subscription initialization
   entirely, with no retry — even though the connector's underlying transport (MQTT) was healthy
   and still sending data (e.g. measurements) the whole time.

These two problems are independent of each other: the 401 loop is a WebSocket
token/reconnect-detection bug, while the 502 case is a missing retry/backoff around the
Inventory API call used to rebuild subscriptions.

## Fix Summary

| Problem | Fix | Key files |
|---|---|---|
| 401 WebSocket reconnect loop | Detect unauthorized close via `statusCode == 401` **or** `reason.contains("401")`; prefer a stored/refreshed token on reconnect and only mint a fresh one if that fails | `CacheInventoryUpdateClient.java`, `ManagementSubscriptionClient.java`, `NotificationConnectionManager.java`, `TokenManager.java` |
| 502/503/504 on subscription init | Detect transient platform errors (`CumulocityErrors.isTransientPlatformError`: `SDKException` with 502/503/504, also as a cause); mark the connector `RETRYING` instead of `FAILED`; retry `doInitializeSubscriptionsAfterConnect()` with exponential backoff (10 s initial, doubling, capped at 300 s) until it succeeds or a non-retryable error occurs | `AConnectorClient.java` (`initializeSubscriptionsAfterConnect`, `isRetryableConnectorError`, `scheduleSubscriptionInitRetry`, `runSubscriptionInitRetry`), `CumulocityErrors.java`, `ConnectionStateManager.java` (`updateStatusRetrying`), `model/status/ConnectorStatus.java` |

Relevant call chain for the 502 case:

`AConnectorClient.doInitializeSubscriptionsAfterConnect()` →
`MappingService.rebuildMappingCaches()` → `MappingService.getMappings()` →
`subscriptionsService.callForTenant(...)` → `inventoryApi.getManagedObjectsByFilter(...)` →
`MappingRepository.findAll()`, which iterates `moc.get().allPages()` — this is where a real
`502/503/504` surfaces as `com.cumulocity.sdk.client.SDKException`.

## What the tests cover

| Layer | Test | Covers |
|---|---|---|
| Subscription init after connect | `AConnectorClientSubscriptionInitRetryTest` (Path A) | 502/503/504 detection, `RETRYING`, backoff, recovery, give-up, disconnect mid-retry |
| Connector status | `ConnectionStateManagerTest` | `CONNECTED → RETRYING → CONNECTED` stays one status session |
| Generic retry helper | `ConnectorRetryReconnectTest` | `AConnectorClient.retryOperation()` used by Kafka and Pulsar connection retries. Unrelated to the 502 path |
| WebSocket 401 | `NotificationCallbackTest`, `TokenManagerTest` (Path C) | 1002-with-401 detection, token store/clear |
| Message processing during the outage | `MQTT3CallbackC8yOutageTest`, `AMQPCallbackC8yOutageTest`, plus the per-connector acknowledgement tests (Path E) | A 502 from the platform is not acknowledged, is redelivered, succeeds after recovery, and is dropped at `maxProcessingAttempts` |

`AConnectorClient` has no test-friendly constructor for `mappingService`, `connectionStateManager`, or
`housekeepingExecutor`, so Path A uses a package-local subclass with direct field assignment, the same style as
`MQTT3ClientTest`.

## Verification Plan

### Path A — Unit test: subscription-init retry/backoff (the 502/503/504 case) — DONE

Implemented in
[`AConnectorClientSubscriptionInitRetryTest`](../../../dynamic-mapper-service/src/test/java/dynamic/mapper/connector/core/client/AConnectorClientSubscriptionInitRetryTest.java),
in `dynamic-mapper-service/src/test/java/dynamic/mapper/connector/core/client/`. It follows
`MQTT3ClientTest`'s setup style and `SubscriptionManagerTest`'s fault-injection idiom
(`doThrow(new SDKException(502, "Bad Gateway")).when(mappingService).rebuildMappingCaches(...)`).

Covers:

1. **Outage detection:** a `TestableConnector` (mirroring `ConnectorRetryReconnectTest`'s pattern)
   is built with mocked `ConnectorRegistry`/`MappingService`/`ServiceConfiguration`, wired via the
   real `initializeManagers()` and marked connected. Stubbing
   `mappingService.rebuildMappingCaches(tenant, connectorId)` to throw `SDKException(502/503/504)`
   (including wrapped inside a `RuntimeException`) and calling
   `initializeSubscriptionsAfterConnect()` asserts: it does **not** throw, the connector reports
   `ConnectorStatus.RETRYING` (not `FAILED`), and exactly one task is scheduled on
   `housekeepingExecutor` (read via reflection, since the field is private).
2. **Non-retryable error on the initial call:** stubbing a `SDKException(403, ...)` asserts the
   exception propagates and no retry is scheduled — `AConnectorClient` doesn't set `FAILED` itself
   here, that's the caller's (e.g. `AMQTTClient#connect`) responsibility, so status is left
   unchanged.
3. **Recovery:** after an initial 502 puts the connector into `RETRYING`, re-stubbing
   `rebuildMappingCaches` to succeed and invoking the private `runSubscriptionInitRetry(long)` via
   reflection (real wall-clock backoff isn't awaited — see the testability note below) asserts the
   status flips back to `CONNECTED` and the internal `subscriptionInitRetryScheduled` guard flag
   resets to `false`.
4. **Backoff doubling and capping:** invoking `runSubscriptionInitRetry` again with a still-failing
   mock asserts the next delay in the status message doubles (`Retrying in 10s` → `Retrying in 20s`), and a
   near-the-cap delay asserts it clamps at `Retrying in 300s`.
5. **Giving up:** a retry that fails with a non-retryable error (`403`) asserts the connector goes
   to `FAILED` via `updateStatusWithError` and the retry-in-flight flag resets — confirming the
   fix doesn't retry forever on a genuine permission problem.
6. **Disconnect mid-retry:** marking the connector disconnected before a scheduled retry fires
   asserts the retry is abandoned (no further `rebuildMappingCaches` call, status stays
   `DISCONNECTED`) rather than clobbering the disconnect with a stale retry result.

**Testability note:** the delays are private constants (`SUBSCRIPTION_INIT_RETRY_INITIAL_DELAY_SECONDS` = 10,
`..._MAX_DELAY_SECONDS` = 300), so the test invokes the private `runSubscriptionInitRetry(long)` via reflection
instead of waiting out the real 10 s/20 s/300 s backoff on the `housekeepingExecutor` thread. Only the
*scheduling* of the first retry (the delay value against a live timer) is not asserted.

To check that the suite detects a regression, remove `502` from `RETRYABLE_HTTP_STATUS_CODES` in
`CumulocityErrors` and re-run: the 502 tests must fail. (This was last done before the classification moved from
`AConnectorClient` to `CumulocityErrors`; repeat it after changing the classifier.)

Run just this class: `cd dynamic-mapper-service && mvn test -Dtest=AConnectorClientSubscriptionInitRetryTest`.

### Path B — End-to-end simulation against a real/local Cumulocity tenant

For a more realistic reproduction (matching the observed incident: MQTT reconnects fine, then 502
on mapping reload), put a fault-injecting reverse proxy in front of the Cumulocity base URL the
microservice talks to. This only works when running the backend locally (e.g. via the
`dynamic.mapper.App` launch config used for local debugging), since it requires redirecting
`C8Y.baseURL` to `localhost`.

**Why mitmproxy, not toxiproxy:** toxiproxy is TCP-level only (latency/resets/timeouts/bandwidth
limits) — it cannot fabricate a specific HTTP status+body. The observed issue is specifically an
`SDKException` wrapping HTTP `502`, so an HTTP-aware proxy is needed to selectively rewrite
responses for one endpoint while passing everything else through untouched. mitmproxy fits, and
because `C8Y.baseURL` also drives the notification WebSocket URL
(`NotificationConnectionManager`, simple `https://` → `wss://` scheme swap), the same
proxy transparently carries the WebSocket traffic too (reverse-proxy mode handles the WS upgrade).

#### Setup: mitmproxy as a reverse proxy in front of the real tenant

1. Install mitmproxy:
   ```bash
   brew install mitmproxy
   ```
2. Use the fault-injection addon in this directory: [fault_inject.py](fault_inject.py). Adjust
   `REAL_HOST` to the tenant you are testing against. The addon registers three mitmproxy
   commands — `fault.on`, `fault.off`, `fault.toggle` — so the fault can be toggled live from the
   interactive TUI's command bar (press `:`, type the command, hit Enter; the resulting state is
   echoed to the event log at the bottom of the screen).
3. Start the proxy in front of the real tenant (replace `<your-tenant>.cumulocity.com` with the
   same host you set in `REAL_HOST`). Use the interactive `mitmproxy` TUI (not headless
   `mitmdump`) since toggling the fault requires the `:` command bar:
   ```bash
   mitmproxy --mode reverse:https://<your-tenant>.cumulocity.com \
             --listen-port 8888 \
             -s fault_inject.py
   ```
4. Point the microservice at the proxy **without editing the gitignored
   `application-dev.properties`** — use [`debug_local_service.sh`](debug_local_service.sh), which
   is already set up for exactly this: it builds the classpath into an argfile, launches
   `dynamic.mapper.App` with `-Dspring.profiles.active=dev`, and takes the Cumulocity base URL as
   the `C8Y_BASE_URL` env var (defaulting to `http://localhost:8888`, i.e. this proxy's default
   listen port, so no override is needed if you kept step 3's `--listen-port 8888`):
   ```bash
   cd "$REPO_ROOT" # or wherever the repo root is
   ./resources/testing/reliability/debug_local_service.sh
   ```
   It always opens a debug socket in **listen mode** (`server=y`, `suspend=n` by default) on port
   `54346`, so the JVM starts immediately whether or not a debugger ever attaches — no separate
   "skip the debugger" variant is needed. Override via env vars if required:
   ```bash
   C8Y_BASE_URL=http://localhost:8888 DEBUG_PORT=54346 DEBUG_SUSPEND=n \
     ./resources/testing/reliability/debug_local_service.sh
   ```
   Set `DEBUG_SUSPEND=y` if you need the JVM to block at startup until a debugger attaches (e.g.
   to catch early bootstrap code).
   **Troubleshooting `ERROR: transport error 202: connect failed: Connection refused` /
   `JDWP Transport dt_socket failed to initialize`:** this only happens if you bypass the script
   and hand-construct a `server=n` (attach mode) command — in that mode the JVM must connect *out*
   to an already-listening debugger before it will even start `main()`, and unlike a plain socket
   timeout, a refused connection is fatal immediately, **regardless of `suspend=y`/`suspend=n`**.
   The script avoids this entirely by always using `server=y` (listen mode), so the JVM opens its
   own socket and starts normally whether or not an IDE ever attaches to it.
5. Confirm the redirect worked: mitmproxy's flow list should show the microservice's bootstrap and
   OAuth calls flowing through `localhost:8888`, and the app should start up and connect normally
   with the fault still off (default state).

#### Running the test

6. Let an MQTT connector connect and successfully complete its first
   `initializeSubscriptionsAfterConnect()` through the (currently transparent) proxy.
7. Force a **reconnect** (briefly disconnect the broker, or use the "Reconnect Subscription
   outbound" button in the connector UI) so `initializeSubscriptionsAfterConnect()` runs again.
8. While that reconnect is in flight, press `:` in the mitmproxy TUI, type `fault.on`, hit Enter —
   `/inventory/managedObjects*` requests now get `502` for as long as it stays on. After e.g.
   30-60 seconds, press `:`, type `fault.off`, hit Enter (or use `fault.toggle` either way).
9. **Expected before the fix:** connector goes to `FAILED` and stays there.
10. **Expected after the fix:** connector status transitions `CONNECTED → RETRYING` (visible in
    the Monitoring/connector UI, tagged `tag--warning` per
    `connector-status.renderer.component.ts`) while the fault is active, then automatically back to
    `CONNECTED` once it is disabled — no manual reconnect needed. Mappings/subscriptions should be
    present again without restarting the microservice.
11. Cross-check logs for the exact messages the fix emits: `"Transient error initializing
    subscriptions for connector {}, will retry in {}s"` and `"Subscription initialization
    succeeded after retry for connector {} (after {}s)"`.

#### Verified — real run against a local backend

Run end to end against a local instance with this setup (tenant ID, connector identifiers, and hostnames are
placeholders). On reconnect, with the fault active, the connector entered the retry/backoff cycle instead of
failing:

```
WARN  AConnectorClient - Transient error initializing subscriptions for connector Mqtt - Hive,
      will retry in 10s: Http status code: 502
WARN  AConnectorClient - Retry of subscription initialization failed for connector Mqtt - Hive,
      next attempt in 20s: Http status code: 502
```

The backoff doubled 10 s → 20 s as asserted in `AConnectorClientSubscriptionInitRetryTest`'s
`retryStillFailsRetryably_doublesBackoff_staysRetrying`. (The run was recorded when the code still logged
milliseconds, `10000ms`/`20000ms`. The log lines above show the current format.)

The connector status object (as exposed to the UI/API) shows `RETRYING` with the backoff message, then a clean
`CONNECTED` once the fault was disabled, with no restart and no manual reconnect:

```json
{
  "connectorName": "Mqtt - Hive",
  "connectorIdentifier": "1lrtycb2",
  "status": "RETRYING",
  "message": " --- com.cumulocity.sdk.client.SDKException: Http status code: 502\nReceived non-JSON error response (status=502, content-type=text/plain). Skipping JSON parse.\n tenant: <tenant> user: service_dynamic-mapper-service --- Retrying in 20s",
  "date": "2026-07-12 14:30:15"
}
```

→ (fault disabled) →

```json
{
  "connectorName": "Mqtt - Hive",
  "connectorIdentifier": "1lrtycb2",
  "status": "CONNECTED",
  "message": "",
  "date": "2026-07-12 14:31:18"
}
```

This is the full before/during/after loop the fix was meant to close: platform 5xx during
subscription init no longer strands the connector in `FAILED` — it self-heals to `CONNECTED` once
the Inventory API is reachable again. Path B is verified.

Two unrelated observations surfaced during this run, both explicitly out of scope for this fix:

- The management-subscription group-caching step (`initializeManagementClient`'s "cache monitored
  groups" call) also hits `/inventory/managedObjects/{id}` and degrades ungracefully under the
  same fault — it logs a misleading `WARN ... Device with id <id> not found!` instead of surfacing
  the 502, and caches 0 groups instead of retrying. Different code path from the one this fix
  targets.
- The periodic housekeeping status-push (`MappingStatusService.sendStatusToInventory`, called
  every 30s from `performHousekeeping`) has no retry/backoff at all — it just logs
  `ERROR ... Unexpected error sending status to inventory` and tries again next cycle, indefinitely,
  for as long as the outage lasts. Already noted above as a separate gap.

### Path C — Verifying the 401 WebSocket fix — DONE (unit-testable parts)

Independent of the 502 path. Two layers:

1. **1002-vs-401 detection (unit-level, pre-existing):**
   [`NotificationCallbackTest`](../../../dynamic-mapper-service/src/test/java/dynamic/mapper/notification/NotificationCallbackTest.java)
   already covers both `onClose(401, ...)` and `onClose(1002, "...401...")` triggering
   `setManagementConnectionStatus(tenant, 401)` / `setCacheInventoryConnectionStatus(tenant, 401)`,
   and confirms a normal close (`1000`) whose reason happens to contain "401" does **not** false-positive.
   Re-run: `mvn test -Dtest=NotificationCallbackTest`.
2. **Stored-token reuse / clear-and-remint-on-failure (unit-level, added):**
   `createManagementConnection`/`createCacheInventoryConnection`
   build on `TokenManager`'s
   `getManagementToken`/`getCacheInventoryToken`/`getDeviceToken` getters and the
   "storing `null` removes the entry" contract of `storeManagementToken`/`storeCacheInventoryToken`/
   `storeDeviceToken` — this is the piece of the fix that's testable without a live WebSocket, and
   it had no test coverage. Added to
   [`TokenManagerTest`](../../../dynamic-mapper-service/src/test/java/dynamic/mapper/notification/service/TokenManagerTest.java):
   getter round-trips for all three token kinds, `null`-clears-the-entry for all three, that
   clearing one device/connector token doesn't affect another, and that clearing a
   never-stored token is a no-op rather than throwing (the fallback path calls
   `storeDeviceToken(tenant, id, null)` unconditionally). Re-run:
   `mvn test -Dtest=TokenManagerTest`.
3. **Full functional check (manual, not automated):** the actual `connect(...)` call inside
   `createManagementConnection`/`createCacheInventoryConnection`
   opens a real `CustomWebSocketClient` and blocks
   on `connectBlocking(...)` — `NotificationConnectionManagerTest`'s own docstring already notes
   this requires either a live Notification 2.0 endpoint or a deeper refactor to make it mockable,
   and is out of scope for that suite. The same limits apply here, so end-to-end verification
   stays manual: force an expired/invalid management or cache-inventory token (e.g. revoke the
   microservice user's role temporarily in a test tenant) and confirm the WS client closes with
   1002 embedding "401" in the reason, and that the manager mints a fresh token and reconnects
   instead of looping forever reusing the stale one — check the logs for `"Stored management token
   failed to connect, retrying with a fresh token"` (the device-token variants read `"Stored static
   device token ..."` and `"Stored dynamic device token ..."`).

### Path D — Service log while the backend is unavailable

The service log (`dynamic.mapper.logs`) stores a tenant's log lines as events. When the platform fails, nothing
may be lost silently, memory must stay bounded and no objects may be left behind in the tenant.

Unit tests: `ServiceLogCollectorTest` (a failed store puts back exactly the records that were not stored, in order,
and does not hold up other tenants) and `ServiceLogSourceLocatorTest` (below).

| Failure | Expected behaviour |
|---|---|
| Event create fails (5xx, unreachable) | The failed chunk and all later chunks of that tenant go back into the buffer; the other tenants are stored; retry at the tenant's next interval |
| Outage longer than the buffer | Oldest lines are dropped (bounded in records and bytes); the next stored chunk reports them as `omitted` |
| Source object creation fails | The write fails and is retried; nothing is created |
| Source created, external id registration fails, platform still failing | The new object is deleted (best effort); the original error is reported; the failed lookup is attached as a suppressed cause |
| Registration answer lost, but the platform stored it | The lookup returns our own object: it is kept, not deleted |
| Another instance registered first (409) | Use its object, delete ours |

Residual case, not fixable on the client: if the *create* call itself fails after the platform created the object
(the proxy faults responses after the request was handled), the client never learns the id and cannot clean up. It
leaves one unregistered `d11r_serviceLogSource` object per such attempt.

End to end, with the proxy from Path B: `fault.logs.on` fails `/event/events` and `/identity/externalIds`;
add `fault.on` to also fail the inventory call that creates the source. Then:

1. Create log lines (a mapping with debug on, WARN or above) and switch `fault.logs.on`.
2. In the Service logs page, nothing new appears; the service's own log shows one failure message, then a reminder
   every 20 runs (`Service log collection still failing`).
3. Switch `fault.logs.off`. The next runs store the backlog (several runs when it is larger than the per-run line
   cap). If lines were dropped in between, the first stored chunk shows an `omitted` count.
4. Check the inventory for `type=d11r_serviceLogSource`: exactly one object per tenant, and with `fault.on` during
   the first-ever collection, no extra unregistered ones beyond the residual case above.

### Path E — Messages arriving while the platform is unavailable

Paths A to D check that the connector and its helpers recover. This path checks the data: what happens to the
messages that arrive during the outage. A 502, 503, or 504 from Cumulocity while the pipeline runs is a server
error, so the connector does **not** acknowledge the message and the broker redelivers it (see
[reliability.md](../../../docs/feature/reliability.md#the-poison-pill-guard)). Each redelivery counts toward
`maxProcessingAttempts` (**default 5**, Service configuration, Processing tab). The failure that reaches the
limit is acknowledged and dropped. For MQTT every redelivery is a reconnect, with a back-off of 0 s, then 60 s,
120 s, and so on up to 300 s, unless the connector property `reconnectOnProcessingError` is off.

What this means during an outage:

| Outage length | Expected |
|---|---|
| Shorter than the redeliveries the limit allows | No message is lost. Each one is redelivered and stored after `fault.off` |
| Longer | Each message that failed `maxProcessingAttempts` times is **dropped**, and the error stays in the mapping status. A 5xx caused by the platform is counted like a 5xx caused by the message |

Unit tests (run with `cd dynamic-mapper-service && mvn test -Dtest='MQTT3CallbackC8yOutageTest,AMQPCallbackC8yOutageTest'`):

| Test | Asserts |
|---|---|
| `aMessageIsNotLostWhenThePlatformRecoversBeforeTheLimit` (MQTT) | `limit - 1` failed deliveries are not acknowledged, the next one after recovery is, and it is acknowledged once |
| `aMessageIsDroppedWhenTheOutageOutlastsTheLimit` (MQTT, AMQP) | The delivery that reaches the limit is acknowledged and dropped, and the pipeline never sees the recovery. Documents the cost of the guard |
| `messagesArrivingAfterTheOutageAreProcessedNormally` (MQTT) | A message from after the outage is acknowledged while the earlier one waits for redelivery |
| `aMessageIsRequeuedDuringTheOutageAndAcknowledgedAfterRecovery` (AMQP 0-9-1) | `basicNack(requeue)` during the outage, `basicAck` after it |

The same 5xx rule has its own acknowledgement tests for the other connectors:
`KafkaClientV2AcknowledgementTest`, `GooglePubSubClientTest`, `HttpConnectorControllerAcknowledgementTest`, and
`MQTT3CallbackMaxProcessingAttemptsIntegrationTest` (the setting itself).

End to end, with the proxy from Path B. The addon faults only `/inventory/managedObjects*` (and, with
`fault.logs.on`, events and external IDs). A mapping fails with a 5xx only if it calls a faulted path, so pick a
mapping that writes to one, for example an INVENTORY or EVENT mapping, or add the path your mapping writes to
(such as `/measurement/measurements`) to `FAULT_PATH_PREFIX` in `fault_inject.py`. Confirm in the mitmproxy flow
list that the mapping's own request gets the 502 before judging the result.

1. Use an MQTT connector with a mapping that writes to a faulted path and targets an existing device. Note the service
   configuration value of `maxProcessingAttempts`.
2. Start a publisher that sends one message every 5 s at QoS 1. Each payload carries a counter that increases by 1,
   so a missing measurement shows up as a gap. For example:
   ```bash
   i=0; while true; do i=$((i+1)); mosquitto_pub -q 1 -t "<topic>" -m "{\"counter\": $i}"; sleep 5; done
   ```
   Keep it running through steps 3 to 5.
3. **Short outage.** About 30 s is short enough that no message reaches `maxProcessingAttempts`.
   1. Switch `fault.on`.
   2. Wait about 30 s.
   3. Switch `fault.off`.
   4. Wait until the connector is `CONNECTED` and a few more messages have arrived.

   Expected:
   - Every counter value exists as a measurement, none missing. Messages sent during the outage may arrive late and
     out of order.
   - The connector may go through `RETRYING` and back to `CONNECTED`, and the log shows the reconnects.
   - The log has no `POISON PILL` entry.
4. **Long outage.** Switch `fault.on` for 10 min. Expected: a `POISON PILL: <n> failed attempts` entry in the log
   per message that reached the limit, and a gap in the counter values for those messages. Record the number
   of reconnects and the number of lost messages.
5. After `fault.off` the connector reaches `CONNECTED` without a manual action, and new messages are stored.

Residual risks to watch for in this run:

- **Reconnect and subscription retry overlap.** A reconnect caused by a failed message runs
  `initializeSubscriptionsAfterConnect` again, which can also hit the outage and go to `RETRYING`. The connector
  must end in `CONNECTED`, not in a loop of reconnects.
- **Blocked consumer threads.** The callbacks now wait for the pipeline result, up to the Smart Function budget
  (about 30 s) or 120 s. With Kafka, check that a slow batch does not exceed `max.poll.interval.ms` (default 5
  minutes) and cause a rebalance.
- **HTTP connector.** During the outage it answers 500, where it used to answer 200. Senders that retry must
  tolerate duplicates.

## Suggested Order of Work

1. ~~Write Path A unit test first~~ — **done**, see `AConnectorClientSubscriptionInitRetryTest`
   above; cheapest, fastest feedback, directly exercises the new `RETRYING`/backoff code without
   needing any real backend, and closes the test-coverage gap identified above.
2. ~~Add Path C unit tests~~ — **done**, see `TokenManagerTest` additions above; closes the token
   storage-layer gap independently of Path B, no real tenant needed.
3. ~~Do one manual Path B run~~ — **done**, see "Verified — real run against a local backend"
   above; confirmed `RETRYING` with correct backoff then a clean self-healed `CONNECTED` against a
   real 502 from `MappingRepository.findAll`'s pagination.
4. Do the manual Path C functional check (item 3 under Path C above) only if the 401 loop
   reappears in logs after deploying the fix — the two paths are orthogonal and this one doesn't
   block on Path B.
5. Run Path E (manual, with the proxy) before releasing a change to the acknowledgement handling or to
   `maxProcessingAttempts`. The unit tests of Path E run with the normal backend suite.
