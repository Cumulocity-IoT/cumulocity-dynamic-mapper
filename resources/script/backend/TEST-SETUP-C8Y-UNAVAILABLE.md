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
| 502/503/504 on subscription init | Detect retryable `SDKException` status codes (502/503/504); mark the connector `RETRYING` (new `ConnectorStatus` value) instead of `FAILED`; retry `doInitializeSubscriptionsAfterConnect()` with exponential backoff (10s initial, doubling, capped at 300s) until it succeeds or a non-retryable error occurs | `AConnectorClient.java` (`initializeSubscriptionsAfterConnect`, `isRetryableConnectorError`, `scheduleSubscriptionInitRetry`, `runSubscriptionInitRetry`), `ConnectionStateManager.java`, `ConnectorStatus.java` |

Relevant call chain for the 502 case:

`AConnectorClient.doInitializeSubscriptionsAfterConnect()` →
`MappingService.rebuildMappingCaches()` (`MappingService.java:780`) →
`MappingService.getMappings()` (`MappingService.java:269-277`) →
`subscriptionsService.callForTenant(...)` → `inventoryApi.getManagedObjectsByFilter(...)` →
`MappingRepository.findAll()` (`MappingRepository.java:100-103`), which iterates
`moc.get().allPages()` — this is where a real `502/503/504` surfaces as
`com.cumulocity.sdk.client.SDKException`.

## Test Coverage Gap (before Path A was implemented)

- `ConnectorRetryReconnectTest` (`dynamic-mapper-service/src/test/java/dynamic/mapper/connector/core/client/`)
  exists but only tests the unrelated generic helper `AConnectorClient.retryOperation()`, used by
  `KafkaClientV2`/`PulsarConnectorClient` for connection retries — **not** the
  `initializeSubscriptionsAfterConnect` retry/backoff path added for the 502 fix.
- No `ConnectionStateManagerTest` exists, so `updateStatusRetrying()` had no dedicated test.
- `AConnectorClient` has no test-friendly constructor/setters for `mappingService`,
  `connectionStateManager`, `housekeepingExecutor`. `MQTT3ClientTest` builds a full concrete
  `MQTT3Client` with mocked `ConfigurationRegistry`/`MappingService`/`ConnectorRegistry` to work
  around this; the new test below reuses that pattern (direct field assignment on a package-local
  subclass) rather than inventing a new test seam.

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
   real `initializeManagers()` (`AConnectorClient.java:508-535`) and marked connected. Stubbing
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
   mock asserts the next delay in the status message doubles (`10000` → `20000`), and a
   near-the-cap delay asserts it clamps at `300000`.
5. **Giving up:** a retry that fails with a non-retryable error (`403`) asserts the connector goes
   to `FAILED` via `updateStatusWithError` and the retry-in-flight flag resets — confirming the
   fix doesn't retry forever on a genuine permission problem.
6. **Disconnect mid-retry:** marking the connector disconnected before a scheduled retry fires
   asserts the retry is abandoned (no further `rebuildMappingCaches` call, status stays
   `DISCONNECTED`) rather than clobbering the disconnect with a stale retry result.

**Testability note:** `SUBSCRIPTION_INIT_RETRY_INITIAL_DELAY_MS`/`_MAX_DELAY_MS` are hardcoded
private static finals, so the test invokes the private `runSubscriptionInitRetry(long)` directly
via reflection instead of waiting out the real 10s/20s/300s backoff on the `housekeepingExecutor`
thread. This exercises the same logic without a slow test; only the *scheduling* of the first
retry (delay value itself) is not asserted against a live timer.

Verified: mutating `RETRYABLE_HTTP_STATUS_CODES` to drop `502` causes 7 of the 10 tests to fail,
confirming the suite actually detects a regression in the fix rather than passing vacuously.

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
(`NotificationConnectionManager.java:79-93`, simple `https://` → `wss://` scheme swap), the same
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
3. Start the proxy in front of the real tenant. Use the interactive `mitmproxy` TUI (not headless
   `mitmdump`) since toggling the fault requires the `:` command bar:
   ```bash
   mitmproxy --mode reverse:https://ck4.eu-latest.cumulocity.com \
             --listen-port 8888 \
             -s fault_inject.py
   ```
4. Point the microservice at the proxy **without editing the gitignored
   `application-dev.properties`** — pass it as a system property on the same local launch command
   already used to run/debug the service. If you don't need to attach a debugger for this test,
   drop `-agentlib:jdwp=...` entirely:
   ```bash
   /usr/bin/env /path/to/java \
     -DC8Y.baseURL=http://localhost:8888 \
     @/path/to/classpath.argfile \
     dynamic.mapper.App
   ```
   If you do want the debugger available, use **listen mode** (`server=y`) rather than attach
   mode (`server=n`) so the JVM opens its own socket instead of dialing out to one that may not
   exist yet:
   ```bash
   /usr/bin/env /path/to/java \
     -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=localhost:54346 \
     -DC8Y.baseURL=http://localhost:8888 \
     @/path/to/classpath.argfile \
     dynamic.mapper.App
   ```
   **Troubleshooting `ERROR: transport error 202: connect failed: Connection refused` /
   `JDWP Transport dt_socket failed to initialize`:** this means `server=n` (attach mode) was used
   but nothing is listening on the given port yet. In `server=n` mode the JVM must connect *out* to
   an already-listening debugger before it will even start `main()` — and unlike a plain socket
   timeout, a refused connection is fatal immediately, **regardless of `suspend=y`/`suspend=n`**.
   Either start your IDE's "attach to remote JVM" listener on that port first, or switch to
   `server=y` as above, or drop the flag if debugging isn't needed for this run.
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
    subscriptions for connector {}, will retry in {}ms"` and `"Subscription initialization
    succeeded after retry for connector {}"`.

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
   (`NotificationConnectionManager.java:711-775`) build on `TokenManager`'s
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
   (`NotificationConnectionManager.java:777-854`) opens a real `CustomWebSocketClient` and blocks
   on `connectBlocking(...)` — `NotificationConnectionManagerTest`'s own docstring already notes
   this requires either a live Notification 2.0 endpoint or a deeper refactor to make it mockable,
   and is out of scope for that suite. The same limits apply here, so end-to-end verification
   stays manual: force an expired/invalid management or cache-inventory token (e.g. revoke the
   microservice user's role temporarily in a test tenant) and confirm the WS client closes with
   1002 embedding "401" in the reason, and that the manager mints a fresh token and reconnects
   instead of looping forever reusing the stale one — check the logs for `"Stored ... token failed
   to connect, retrying with a fresh token"`.

## Suggested Order of Work

1. ~~Write Path A unit test first~~ — **done**, see `AConnectorClientSubscriptionInitRetryTest`
   above; cheapest, fastest feedback, directly exercises the new `RETRYING`/backoff code without
   needing any real backend, and closes the test-coverage gap identified above.
2. ~~Add Path C unit tests~~ — **done**, see `TokenManagerTest` additions above; closes the token
   storage-layer gap independently of Path B, no real tenant needed.
3. Do one manual Path B run against a disposable/test tenant to confirm the 502 fix holds up
   against a real 502 from `MappingRepository.findAll`'s pagination.
4. Do the manual Path C functional check (item 3 above) only if the 401 loop reappears in logs
   after deploying the fix — the two paths are orthogonal and this one doesn't block on Path B.
