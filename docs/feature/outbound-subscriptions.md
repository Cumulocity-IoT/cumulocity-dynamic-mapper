# Outbound subscriptions

An outbound mapping reacts to changes of Cumulocity devices (measurements, events, alarms, inventory
changes, operations). The mapper only sees those changes for devices it has subscribed to through
Cumulocity Notification 2.0. This page describes how a device comes to be subscribed: one by one
(**static**), because its type is selected (**by type**), or because it sits in a selected group
(**by group**), and how devices that existed before a type was selected are backfilled (**resync**).
[`mapping-processing-outbound.md`](mapping-processing-outbound.md) covers what happens after a
notification arrives.

---

## Requirements

**What it is for.** Telling the mapper which devices feed outbound mappings, without a user having
to subscribe every device by hand.

- **Three ways to select devices.**
  - *Static:* an explicit list of devices. A listed device brings its child devices with it when the
    list is created.
  - *By type:* a list of device types. Every device of a selected type is subscribed, including
    devices created later.
  - *By group:* a list of device groups. Every device below a selected group is subscribed, and
    devices that join the group later follow.
- **The selection is a desired state.** For the group and static update calls, the list sent is the
  complete new selection. The service works out additions and removals. An empty list is valid and
  clears the selection; a missing list is rejected.
- **A device is subscribed once.** If a device is covered by more than one selection, one subscription
  serves it. Type and group selections outrank an explicit one: an explicit subscription is replaced
  when the device becomes covered by a type or group, and skipped when the device is already covered.
- **Removing a selection removes only what it alone justified.** A device that is still covered by
  another group, or by a selected type, keeps its subscription.
- **Existing devices are not picked up by selecting a type.** The platform reports new devices only.
  A separate **resync** subscribes devices of a selected type that already exist. It
  - applies only to a type that is already selected, otherwise it is rejected;
  - runs in the background and answers immediately with "accepted";
  - allows one run per type at a time, a second request meanwhile is rejected as a conflict;
  - skips devices that are already covered and reports counts of subscribed, already covered, and
    failed devices as service events, plus an explicit "incomplete" result if the scan stopped early.
- **The result of a request says how it went.** A request waits up to one minute for the
  subscriptions to be created. The answer lists devices that failed and a status: active, pending
  (still running after the wait), or error.
- **Everything needs outbound mapping to be enabled.** With outbound mapping off in the service
  configuration, every call is refused.
- **Reading is open, changing needs a role.** Any user of the application can read selections.
  Changing them needs the Create or Admin role.

**Non-goals.** The selection does not decide which mapping runs for a device; that is the mapping's own
filters and deployment. The selection does not unsubscribe devices that were deleted in Cumulocity
by itself; stale entries are dropped when a selection is saved.

---

## Implementation

### Parts

| Part | Role |
|---|---|
| [`NotificationSubscriptionController`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/notification/NotificationSubscriptionController.java) | REST under `/subscription`. |
| [`NotificationSubscriptionService`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/notification/NotificationSubscriptionService.java) | Diffing of desired state, child and group expansion, waiting for results. |
| [`NotificationSubscriber`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/notification/NotificationSubscriber.java) | Facade over the services in `notification/service`. |
| [`SubscriptionManager`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/notification/service/SubscriptionManager.java) | Creates and deletes the Notification 2.0 subscriptions, deduplication, type filter, resync. |
| [`Utils`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/notification/Utils.java) | Subscription and subscriber names; its class comment is the reference for the five families. |
| [`UpdateSubscriptionDeviceTypeTask`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/notification/task/UpdateSubscriptionDeviceTypeTask.java) and the group task | React to inventory notifications from the management subscription. |

### Where users manage subscriptions

| Place | Scope |
|---|---|
| Subscription outbound page (tabs static and dynamic) | Overview, bulk management, resync of existing devices of a type. |
| **Subscription** step of the mapping stepper and **Subscription** tab of the unified editor (outbound only) | Collects a selection while the mapping is defined and writes it on save. Plan and decisions: [integrate-subscription-mgmt-outbound](../improvements/integrate-subscription-mgmt-outbound/plan.md). |

The step reuses the four selector drawers of the page. Its selection (`OutboundSubscriptionSelection`) lives on the
editor, not on the step, because the unified editor destroys the tab on every switch. `MappingCommitService` hands
it to `SubscriptionSelectionService.apply` after the mapping and its deployment were saved, so a rejected mapping
never leaves a subscription behind. The writes replace the whole list, as on the page, and a type added with the
resync option triggers `POST /subscription/type/resync/{type}` once per added type.

The step is not part of the base step numbering in `stepper.model.ts` (`STEP_*`, which `advanceFromStepToEndStep`
uses). Positions come from `StepLayout`, which adds the Subscription step for outbound.

### Subscription families

| Name | Context | Used for |
|---|---|---|
| `DynamicMapperStaticDeviceSubscription` | one device | explicit selection, API from the request |
| `DynamicMapperDynamicDeviceSubscription` | one device | devices covered by a type or group, API `ALL` for type and resync |
| `DynamicMapperManagementSubscription` | tenant with a type filter, or a group object | the watcher that notices new devices and group changes |
| `DynamicMapperExplorerDeviceSubscription` | one device | Message Explorer sessions only |
| `DynamicMapperCacheInventorySubscription` | one device | keeps the inventory cache fresh |

Static and dynamic subscriptions each have one WebSocket consumer per connector. Management and cache
use one per tenant.

### Endpoints

| Method and path | Role | Effect |
|---|---|---|
| `POST /subscription` | Admin or Create | Static selection. Expands child devices of each listed device, subscribes each once. Body `devices` must not be empty. |
| `PUT /subscription?subscription=` | Admin or Create | Desired state of the named bucket (default static). Diff against current, add, remove. |
| `GET /subscription?subscription=` | any | Devices of the bucket. Paging is opt-in with `pageSize`, plus `currentPage`, `withTotalPages`, `search`. |
| `DELETE /subscription/{deviceId}?subscription=` | Admin or Create | Removes one device from the bucket. `404` if the device does not exist. |
| `PUT /subscription/group` | Admin or Create | Desired state of selected groups. |
| `GET /subscription/group` | any | Selected groups, paging opt-in. |
| `DELETE /subscription/group/{groupId}` | Admin or Create | Removes one group and its devices that nothing else covers. |
| `GET /subscription/type` | any | Selected types. |
| `PUT /subscription/type` | Admin or Create | Replaces the set of selected types. |
| `POST /subscription/type/resync/{type}` and `POST /subscription/type/resync?type=` | Admin or Create | Backfill; `202`, `400` for a type not selected, `409` for a run in progress. The query form exists because a `/` in a type is rejected in a path segment. |

### Flows

**Static.** `createDeviceSubscription` collects the device and its related devices
(`findAllRelatedDevicesByMO`), de-duplicated by id, and calls `subscribeDeviceAndConnect` for each.
These calls return futures. `awaitSubscriptions` waits for all of them under one 60 s deadline: a failed
future puts the device id in `failedDeviceIds`, a timeout yields `PENDING`, and a `null` result is a
deduplication skip, not a failure. Each subscribed device is also loaded into the inventory cache so
inventory filters work on the first notification.

**Update (static or dynamic bucket).** `updateDeviceSubscription` reads the bucket, computes additions and
removals, subscribes additions, and unsubscribes removals. A removed device that no longer exists is
removed by id (`deleteSubscriptionsForDevice`).

**Group.** `updateGroupSubscription` removes groups first (so a device moving from a removed group to
an added group is not subscribed and unsubscribed again), then creates management subscriptions on the
new group objects and subscribes all devices below them into the dynamic bucket. Devices still reachable
from a remaining group, or whose type is selected, are kept.

**Type.** `updateSubscriptionByType` rebuilds the tenant-level management subscription whose type filter
is the selected types joined with `or`. Cumulocity allows one such subscription per name and context, so
the code deletes the old one and then creates the new one, and re-creates the old one if the create fails.
Types that dropped out are pruned in the background: devices of those types are unsubscribed from the
dynamic bucket unless a selected group still covers them. New devices arrive as inventory CREATE
notifications and `UpdateSubscriptionDeviceTypeTask` subscribes each with API `ALL`.

**Type update details.** An unchanged type list is a no-op (answers `ACTIVE` with the current types); only an
empty list deletes the subscription. The delete-then-create swap runs under a per-tenant lock, and a failed create
retries the restore of the previous filter three times. The background prune and the resync re-read the configured
types (cached for 2 s) so a type removed or added back while they run is respected: a prune skips devices of a
re-added type, and a resync stops (reported as incomplete) when its type was removed.

**Reads must not fail silently.** The reads that desired-state writes depend on (devices, groups, types) throw when
the platform call fails, instead of returning an empty result that would look like "nothing is covered". `GET`
endpoints answer 500 then. `deleteGroupSubscription` reads the remaining groups and types before it changes
anything.

**Group notifications.** `UpdateSubscriptionDeviceGroupTask` expands a sub-group added to or removed from a selected
group into its devices, waits for the asynchronous subscribes, and records only handled members in the group cache,
so a failed add or remove is retried by the next delta. "Still covered by another group" is read live, with the cache
as a fast path. Notifications of one group are processed one at a time.

**Failed device WebSockets.** A device WebSocket whose connect failed is remembered and retried by the 60 s
reconnect cycle. Clients closed on purpose (no subscriptions left, broker connection down) are not retried.

**Write failures are reported.** Unsubscribing a device or a group no longer swallows errors: `DELETE` answers 500, and
in the desired-state updates the devices that could not be removed (and groups whose watcher could not be created)
are listed in `failedDeviceIds` with status `ERROR`, while the remaining devices are still processed. Reading
subscriptions never deletes anything; a device that cannot be resolved is left out of the listing, and stale entries
are dropped when a selection is saved. Bulk deletes read all subscriptions before deleting the first, because the
platform's page offsets shift as entries are removed. The device list of a request is capped at 10,000 entries, as it
carries the whole selection.

**Request validation.** The `subscription` parameter of the device endpoints (`GET`, `PUT`, `DELETE /subscription`)
accepts only the static and the dynamic bucket; anything else is a 400, so the management and cache subscriptions
cannot be changed through them. `POST /subscription` lists requested devices that do not exist in `failedDeviceIds`
(status `ERROR`). A 400 or 404 raised inside an endpoint is passed on, not turned into a 500.

**Outbound mapping set.** `MappingSubscriptionManager.updateSubscriptionsOutbound` builds the new set and swaps it in
atomically; readers take no lock, so a notification never sees an empty set during a reload.

**Device WebSocket tokens.** The 401 state of a device WebSocket is kept per connector (the tenant-level status is
a summary: 401 if any connector is unauthorized). A connector with an expired token gets a new one on the next
reconnect cycle even while another connector is healthy. Tokens are refreshed every 12 h; a cycle with failed
refreshes is retried after 15 minutes, and an exception can no longer end the schedule.

**Large tenants.** Scans that read all subscriptions (listing, search, resync pre-fetch, bulk delete) use the
platform's maximum page size of 2,000 instead of the SDK default.

**Static selection under a dynamic subscription.** An explicit static selection for a device that a dynamic
subscription already covers is held back, not dropped (`shadowedStatic`). It is part of the full static list (the
"manage subscriptions" drawer and the desired-state diff read it), so the user sees it and removes it like any other
static device; removing it forgets the marker and leaves the dynamic subscription and push connectivity untouched.
When the dynamic subscription is removed later, the held-back device becomes a real static subscription. The paged
static grid shows only real subscriptions, so a held-back device appears in the drawer but not in the grid. The
marker is held in memory: after a restart the selection is gone, and the device is simply no longer in the static
list.

**Groups of a device.** Subscribed devices carry `groups` (names) and `groupIds`. The "Subscription by" column on
the dynamic tab matches on the ids, so two groups with the same name are told apart; names are only the fallback for
an answer without ids. Only direct parents are listed: a device in a sub-group of a subscribed group is covered by
the subscription but shown as "Dynamic".

**New devices of a selected type.** The CREATE notification is acknowledged only once the device is subscribed.
`UpdateSubscriptionDeviceTypeTask` waits for the subscribe and tries up to three times, with pauses of 1 s and 5 s.
- *Done, or failed for good* (the platform rejected the request with a 4xx other than 408 and 429, or the arguments
  are invalid): the notification is acknowledged. A redelivery would only repeat the failure.
- *Transient failure that outlived the retries* (a 5xx, a timeout, a failed lookup): the notification is not
  acknowledged, so Cumulocity delivers it again. The WebSocket client's existing poison-pill limit bounds the
  redeliveries. Not acknowledging reconnects the management WebSocket, as it does for outbound messages.
- Every failure that outlives the retries is also written as a service event (type `d11r_subscriptionEvent`,
  severity error, with device, type and error), so it is not only in the log. A resync remains the fallback.

The management client does this by returning a result whose future completes when the task is done: normally to
acknowledge, exceptionally not to. `CustomWebSocketClient` applies its usual rule to it.

**Known limits.**
- Group notifications (UPDATE) are still acknowledged before their task runs. A failed add or remove is kept in the
  group cache and retried by the next notification of that group, but is not redelivered. Redelivering an older
  group state could undo a newer one, so this needs ordering guarantees first.

**Deduplication.** `SubscriptionManager.checkAndHandleDeduplication` enforces the rule "dynamic outranks
static". Requesting static while a dynamic subscription exists is skipped. Requesting dynamic while a
static one exists deletes the static one first and remembers it in `shadowedStatic`; if the dynamic create
then fails, the static subscription is put back. Each skip or replacement writes a deduplication event.
Per-device work is serialized by a device lock and an in-flight key, and a semaphore bounds the number of
concurrent creations.

**Resync.** `resyncTypeSubscription` validates that the type is in the current filter, claims
`resyncsInFlight` for `tenant:type`, and starts a background job on the virtual thread pool. The job
reads all dynamic and static subscription device ids in two paged queries (the batch pre-fetch replaces
two lookups per device), scans devices of the type, subscribes those not already dynamic, settles
the futures in chunks, reconnects the device clients once at the end, and writes
`BACKFILL_SUBSCRIPTION_EVENT_TYPE` events: a start event and an end event. Only a fully completed run
carries a `subscribed` property; the UI derives the "last sync" time from its presence. A failed scan
writes `subscribedBeforeFailure` and `error` instead.

### Traps

- **Re-saving the same type list deletes the type subscription.** `Utils.createChangedTypeFilter`
  returns `null` when the new types equal the existing ones, and `updateSubscriptionByType` treats
  `null` like an empty filter: it deletes the existing subscription and answers `INACTIVE`. Callers
  must send a changed list; see the gaps reported with this page.
- **Create and update differ for static selections.** Create expands child devices; the update call
  subscribes exactly the devices listed.
- **API differs by path.** Type and resync subscribe with `ALL`; the group update uses the API of the
  request; static uses the API of the request.
- **The `subscription` parameter is a free string.** It is passed through as the subscription name.
  Only the static and dynamic names are meaningful.
- **A type name is placed in a filter string between single quotes without escaping.** A type that
  contains a `'` produces a broken filter.
- **Resync works from the stored type filter.** It reads the filter from Cumulocity, so a type that was
  selected but is missing there (for example after the trap above) is rejected with `400`.
