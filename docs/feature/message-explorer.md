# Message Explorer

Message Explorer lets a user watch real broker traffic — inbound (broker → Cumulocity) or
outbound (Cumulocity → broker) — without having an active mapping in place, and turn a
captured message directly into the starting point of a new mapping. It replaced the older
"snooping" feature removed in 6.4.0; snooping's removal is documented in `CHANGES.md`, but
its successor previously was not.

---

## Requirements

**What it is for.** Seeing what a device (or Cumulocity, for outbound) is actually sending,
before a mapping exists to interpret it, so a mapping can be built from a real sample instead
of a guess.

- **Starting a session never requires an existing mapping.** A user picks a connector + topic
  (inbound) or a device/group/device-type (outbound) and captures live traffic independently
  of any deployed mapping.
- **Inbound sessions listen on one specific connector.** The user selects which connector to
  observe, and the topic filter supports MQTT-style wildcards (`+`, `#`).
- **Outbound sessions require a target** — a specific device, a device group, or a device
  type — because outbound messages only exist as Cumulocity operations/notifications
  addressed to a device; without a target there is nothing to subscribe to and the session
  would silently capture nothing.
- **A session buffers a bounded number of messages** (1–500, default 50); once full, the
  oldest captured message is dropped to make room for the newest.
- **A session expires if the UI stops polling it** — an abandoned browser tab must not leave a
  broker subscription (or a Notification 2.0 subscription) running forever. The idle timeout
  is tenant-configurable and defaults to 10 minutes.
- **Duplicate deliveries of the same message are suppressed** within a short window — this can
  happen legitimately (an outbound event is delivered once per connector; overlapping inbound
  topic subscriptions can match the same broker message twice) and must not be shown to the
  user as multiple captures.
- **A captured message can seed a new mapping directly** — its topic, payload, and (for
  outbound) key are handed to the mapping-creation flow as a starting point, without the user
  retyping or re-pasting anything.
- **Binary/non-UTF-8 payloads are still captured**, just flagged and shown differently, rather
  than dropped or corrupted.
- **Starting a new session for the same target stops any previous one** — a page refresh that
  skips the normal stop call must not leave a ghost subscription running indefinitely
  alongside the new one.

---

## Implementation

### Backend endpoints

| Endpoint | Class | Description |
|---|---|---|
| `POST /explorer/session` | [`ExplorerController.startSession()`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/explorer/ExplorerController.java#L101-L146) | Creates a session; returns `sessionId` (and an optional `subscriptionWarning`, inbound only). |
| `DELETE /explorer/session/{sessionId}` | [`ExplorerController.stopSession()`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/explorer/ExplorerController.java#L154-L165) | Stops the session and unregisters its listener(s)/subscription(s). |
| `GET /explorer/session/{sessionId}/messages` | [`ExplorerController.getMessages()`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/explorer/ExplorerController.java#L175-L185) | Returns the buffered messages (oldest first) and resets the idle timer. |
| `DELETE /explorer/session/{sessionId}/messages` | [`ExplorerController.clearMessages()`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/explorer/ExplorerController.java#L193-L203) | Empties the buffer without stopping the session. |

All four return `404` when the session no longer exists (expired or already stopped); the
frontend's [`MessageExplorerService`](../../dynamic-mapper-ui/src/mapping/message-explorer/message-explorer.service.ts)
turns a `404` into a `SessionExpiredError` rather than a generic HTTP failure.

`StartSessionRequest` validation happens in the controller, before
[`ExplorerService.startSession()`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/explorer/ExplorerService.java#L144-L333)
is called at all:

- INBOUND requires `connectorIdentifier` (`ExplorerController.java:107`).
- OUTBOUND requires `sourceId` or `deviceType` (`ExplorerController.java:113`) — without
  either, no Notification 2.0 subscription would be created.

### Session model and lifecycle — [`ExplorerService`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/explorer/ExplorerService.java)

Sessions live entirely in memory, keyed `tenant → sessionId → ExplorerSession`. Each session
owns a bounded `ConcurrentLinkedDeque<ExplorerMessage>` (trimmed to `maxMessages` on every
append, `buildListener()` lines 579-584).

**INBOUND** (`startSession`, lines 277-330): looks up the one named connector via
`ConnectorRegistry`, registers a listener with `client.addExplorerListener(listener)`, and
calls `client.subscribeExplorerTopic(topic)` to actually subscribe the topic on the broker —
a no-op for connectors that don't require explicit subscriptions (e.g. HTTP). A non-null
return value from `subscribeExplorerTopic` is surfaced to the UI as `subscriptionWarning`
rather than failing the whole request.

**OUTBOUND** (`startSession`, lines 159-276): `connectorIdentifier` is ignored — the listener
is registered on *every* connector for the tenant (`client.addOutboundExplorerListener`),
because an outbound message can be published through any of them. If a device or group is
given, it's resolved via `DeviceDiscoveryService.findAllRelatedDevicesByMO()` (a group expands
to its member devices — subscribing to the group managed object itself receives no device
events) and each resolved device gets a dedicated Notification 2.0 subscription
(`notificationSubscriber.subscribeDeviceAndConnect(..., Utils.EXPLORER_DEVICE_SUBSCRIPTION)`),
independent of any STATIC/DYNAMIC mapping subscription — so the session captures events even
when no outbound mapping exists yet for that device. A device-type target instead subscribes
every currently-matching device up front (mirroring `UpdateSubscriptionDeviceTypeTask`); a
device created *after* the session starts is not automatically added.

**Stale-session eviction** (lines 184-202 outbound, 296-313 inbound): before creating a
session, any other session belonging to the same user on the same target (outbound: same
`sourceId`; inbound: same connector + topic) is torn down first. This is the primary defence
against ghost listeners from a browser reload that skipped the `DELETE` call.

**Per-message filtering and dedup** — `buildListener()` (lines 502-586):

1. Topic filter via `topicMatches()` (lines 592-606) — lightweight MQTT wildcard matching
   (`+` = one level, `#` = rest of path), independent of what the broker itself already
   filtered.
2. OUTBOUND source filter: message's `sourceId` must be in the session's resolved
   `subscribedDeviceIds` (membership, not equality with the originally-selected ID, since a
   group is expanded at session start).
3. OUTBOUND device-type filter: resolves and caches each source device's type
   (`session.getDeviceTypeCache()`) so each unique device is looked up in inventory at most
   once per session.
4. Deduplication, keyed `sessionId::topic::payloadHash`: OUTBOUND uses a 3-second window
   (`OUTBOUND_DEDUP_WINDOW_MS`) because Notification 2.0 delivers one copy per connector;
   INBOUND uses a 500ms window (`INBOUND_DEDUP_WINDOW_MS`) because overlapping MQTT
   subscriptions (e.g. a mapping on `a/b` plus an explorer session on `a/#`) can otherwise
   double-deliver the same broker message.
5. Payload decoding: UTF-8 first, falling back to Base64 with `binary=true` when the bytes
   contain non-printable characters (`isPrintableUtf8()`, lines 609-619).

### TTL watchdog

`expireIdleSessions()` (`@Scheduled(fixedDelay = 30_000)`, lines 408-429) runs every 30
seconds and removes any session whose `lastPolledAt` is older than its TTL. Default TTL is
10 minutes (`SESSION_TTL_MS`), overridable per-request (`sessionTTLMinutes`) or per-tenant
(`ServiceConfiguration.getExplorerSessionTTLMinutes()`, `resolveSessionTtlMs()` lines
435-439). `getMessages()` refreshes `lastPolledAt` on every poll, so a UI that keeps
auto-refreshing never expires; only an abandoned session does.

Stopping/expiring a session calls `unregisterListener()` (lines 460-500), which:

- OUTBOUND: removes the listener from every connector, closes the dedicated
  Notification 2.0 client (`notificationSubscriber.closeExplorerDeviceClient()`), and
  unsubscribes each device the session subscribed.
- INBOUND: removes the listener from the one connector, then unsubscribes the broker topic
  **only if no other still-active session on the same connector needs the exact same topic**
  (`otherSessionStillNeedsTopic()`, lines 451-458) — otherwise stopping one user's session
  would silently kill another concurrent session watching the same topic.

### Frontend

| Component/service | File | Role |
|---|---|---|
| `MessageExplorerService` | [`message-explorer/message-explorer.service.ts`](../../dynamic-mapper-ui/src/mapping/message-explorer/message-explorer.service.ts) | Thin `FetchClient` wrapper around the four REST endpoints; maps a `404` to `SessionExpiredError`. |
| `MessageExplorerComponent` | [`message-explorer/message-explorer.component.ts`](../../dynamic-mapper-ui/src/mapping/message-explorer/message-explorer.component.ts) | Main list page: start/stop/pause/resume/clear controls, auto-refresh polling, per-row "Create mapping" action. |
| `MessageExplorerDrawerComponent` | [`message-explorer/message-explorer-drawer.component.ts`](../../dynamic-mapper-ui/src/mapping/message-explorer/message-explorer-drawer.component.ts) | The "Start exploring messages…" configuration drawer: direction toggle, connector picker (filtered to connectors supporting the chosen direction), topic input, device/group asset-selector or device-type filter for OUTBOUND. |
| `MessageExplorerPayloadRendererComponent` | [`message-explorer/message-explorer-payload.renderer.component.ts`](../../dynamic-mapper-ui/src/mapping/message-explorer/message-explorer-payload.renderer.component.ts) | Renders/expands a captured payload (JSON editor, binary badge). |
| `ExplorerMappingHandoffService` | [`core/explorer-mapping-handoff.service.ts`](../../dynamic-mapper-ui/src/mapping/core/explorer-mapping-handoff.service.ts) | Single-shot hand-off object carrying topic/payload/key/mappingType/transformationType from a "Create mapping" click across `router.navigate` into the mapping-creation wizard, replacing an older `history.state`-based approach. |

**Flow:** drawer collects direction + target → `POST /explorer/session` → backend registers
listener(s)/subscription(s) → UI polls `GET /explorer/session/{id}/messages` (auto-refresh or
manual) → user clicks "Create mapping" on a row → `ExplorerMappingHandoffService.set()` stores
the payload/topic/key → navigates to
[`MappingTypeDrawerComponent`](../../dynamic-mapper-ui/src/mapping/mapping-create/mapping-type-drawer.component.ts#L632-L647),
which `consume()`s the hand-off to pre-fill the new mapping's source template.

### Known gotchas

- **Kafka requires an isolated consumer group** for explorer subscriptions
  (`KafkaClientV2.subscribeExplorer()`) so exploring a topic never steals messages from — or
  duplicates the offset position of — the consumer group backing live mappings on the same
  topic. This is currently only documented in code comments on that method.
- **MQTT Service (Cumulocity's managed broker) has no real per-topic subscription mechanism**;
  its explorer listener relies on messages already flowing through the shared connection
  rather than issuing a dedicated broker-level subscribe, which is why `subscribeExplorerTopic`
  can return a `subscriptionWarning` instead of guaranteeing delivery for connectors like this.
- **Session cleanup on browser close is best-effort**, not authoritative: it relies on the
  stale-session eviction on next start plus the idle TTL watchdog, not a reliable
  page-unload/disconnect signal. A tab closed uncleanly can leave a broker or Notification 2.0
  subscription alive for up to the TTL.
- **A device-type OUTBOUND session only subscribes devices that exist at session start** — a
  device created afterwards under the same type is not picked up until a new session starts.
- **Sessions are per-JVM in-memory and assume a single service replica.** A poll routed to a
  different instance than the one holding the session returns 404 "session expired". This is
  safe today only because the service runs single-instance by design — see
  [architecture.md § Single-instance requirement](../architecture.md#single-instance-requirement).
- **Navigating away does not stop the session.** `ngOnDestroy` deliberately leaves the backend
  session running so returning to the page resumes it (the UI persists the session id in
  `localStorage`). The broker/Notification 2.0 subscriptions therefore stay live, and messages
  keep being captured, until the idle TTL expires them — by default up to 10 minutes after the
  user stopped looking. An explicit "Stop" is the only immediate teardown.
- **Polling re-transfers the whole buffer.** `GET /messages` returns every buffered message
  (up to 500), not a delta since the last poll, and the UI re-matches them against what it
  already has to preserve sequence numbers. Both costs grow with `maxMessages`; a large buffer
  on a short refresh interval is measurably more expensive than it looks.
