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
