# Mapping Testing Isolation (Mock vs. Live)

A mapping test (see [`mapping-testing.md`](mapping-testing.md)) runs against mocked identity and
inventory services instead of the tenant's real ones. This page is the contract that keeps the two
worlds apart: nothing a dry run does may be visible to live processing, nothing live may change what
a dry run reports, and one dry run may not influence the next.

It exists because the separation was found to be incomplete: the mock itself is isolated, but the
caches in front of it are shared with production (see [Known defects](#known-defects-found-by-review)).

---

## Requirements

1. **Isolate mock from live.**
   - A dry run is decided by the mock alone. Whether a real device with the same external ID exists
     in the tenant — or sits in a production cache — must not change the outcome. With
     `createNonExistingDevice = false` and an empty mock, the test reports the device as not found,
     even if the same external ID exists in the tenant.
   - A dry run leaves live processing untouched: no entry is added to, changed in, or evicted from
     any production cache; no real subscription (Notification 2.0) is opened; no real object is
     read or written.
   - Identifiers produced by the mock can never be mistaken for real ones. A mock device ID must
     not be able to collide with, or be served in place of, a real managed object ID.
   - The isolation holds for inbound and outbound tests alike, and for every transformation type.
   - Isolation applies to `send = false` only where the mock is in use. A `send = true` inbound test
     deliberately uses the real services (see [`mapping-testing.md`](mapping-testing.md)) and is
     outside this guarantee.

2. **Clear caches between runs.**
   - Each test run starts from an empty mock: devices implicitly created by an earlier run
     (`createNonExistingDevice = true`) are not found by a later run.
   - The reset happens on the server side as part of running a test. It does not depend on the UI
     remembering to call it, and a run that failed or was abandoned part-way does not leave state
     behind for the next one.
   - Clearing the mock never touches production caches, and clearing production caches never
     touches the mock.
   - Concurrent tests by different tenants do not see each other's mock state.

3. **Open — requirement not yet specified.** The request listed a third requirement without
   stating it. Candidates raised by the review, to be confirmed or replaced:
   - a regression suite pinning requirements 1 and 2, so the separation cannot silently regress;
   - a visible indicator in the test result when the outcome came from the mock, so a user can tell
     "not found in the mock" from "not found in the tenant".

**Non-goals.** Dry runs do not simulate a *populated* tenant; a user who wants a device to exist
seeds it (or lets the test create it). Mock behaviour is not required to match every nuance of the
real Cumulocity API.

---

## Implementation

### How testing is routed

`ProcessingContext.isTesting()` is passed down to the facades, which pick the mock or the real API:

| Facade | Mock | Real |
|---|---|---|
| [`IdentityFacade`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/core/facade/IdentityFacade.java) | [`MockIdentity`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/core/mock/MockIdentity.java) | `IdentityApi` |
| [`InventoryFacade`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/core/facade/InventoryFacade.java) | [`MockInventory`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/core/mock/MockInventory.java) | `InventoryApi` |

Both mocks are singleton Spring components with per-tenant maps. Mock managed-object IDs count up
from `10000` per tenant ([`MockInventory.java:91`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/core/mock/MockInventory.java#L91)).

The mock layer is isolated. The defects are in the **caches that sit in front of it**, which are
shared with production and are not keyed by `testing`.

### Known defects (found by review)

| # | Where | Defect | Direction |
|---|---|---|---|
| D1 | [`C8YAgent.resolveExternalId2GlobalId`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/core/C8YAgent.java#L215-L246) | Reads the production `InboundExternalIdCache` **before** consulting `testing`; only the *write* is guarded with `if (!testing)`. A real device in that cache is returned to a dry run. | live → test |
| D2 | [`IdentityResolutionService.getOrCreateDeviceThreadSafe`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/core/IdentityResolutionService.java#L69) | Reads `tenantRegistry.getCachedExternalId` before checking `context.isTesting()`; only writes are guarded. | live → test |
| D3 | [`InventoryCacheEnrichmentService.getMOFromInventoryCache`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/core/InventoryCacheEnrichmentService.java#L111-L153) and `updateMOInInventoryCache` | Reads and writes the shared per-tenant `InventoryCache` keyed by `sourceId` regardless of `testing`. After D1 returns a real ID, the mock misses it and an **empty map is stored under the real ID**, which makes `filterInventory` evaluate false for the live device. Mock MOs (ID `10000+`) are likewise stored under IDs a real device can own. | both |
| D4 | same methods | On a mock miss, calls `tenantRegistry.removeFromExternalIdCacheByInternalId(tenant, sourceId)`, evicting a **real** external-ID cache entry. | test → live |
| D5 | same method | Calls `notificationSubscriber.subscribeMOForInventoryCacheUpdates` for the (possibly mock) ID — a real Notification 2.0 subscription opened by a dry run. | test → live |
| D6 | `MockIdentity` / `MockInventory` | State persists across runs; it is cleared only by the `MOCK_IDENTITY_CACHE` / `MOCK_INVENTORY_CACHE` `CLEAR_CACHE` operations, which the UI triggers. Server-side, nothing resets it per run. | test → test |
| D7 | [`IdentityFacade.resolveGlobalId2ExternalId`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/core/facade/IdentityFacade.java) | Uses `!testing` on a `Boolean`; `null` throws, while every sibling method treats `null` as production. | consistency |

Also worth checking, not confirmed as defects: `C8YAgent.java:1032` and
`SparkPlugBDeserializer.java:273` call `resolveExternalId2GlobalId(..., false)` explicitly.

### Proposed design

**R1 — isolate.**
- When `testing` is true, bypass every production cache on both read and write: the
  `InboundExternalIdCache` in `C8YAgent`, the `TenantRegistry` external-ID cache in
  `IdentityResolutionService`, and the `InventoryCache` plus `removeFromExternalIdCacheByInternalId`
  and the notification subscription in `InventoryCacheEnrichmentService`.
- Do not add a second cache for tests unless profiling shows the mock is too slow; the mock is
  already an in-memory map, so a cache in front of it buys nothing.
- Make the decision once, at the facade/service boundary, rather than guarding each call site —
  the current bug is exactly the result of guarding writes in some places and forgetting reads.
- Normalise `testing` to a primitive (`Boolean.TRUE.equals(...)`) in one helper to remove D7.

**R2 — clear between runs.**
- Clear both mocks for the tenant at the start of `TestController.testMapping()` for dry runs,
  instead of relying on `TestingService.resetMockCache()`. Keep the UI call (cheap, harmless) but
  do not depend on it.
- `MockIdentity.clear()` and `MockInventory.clear()` are already per-tenant
  (`subscriptionsService.getTenant()`); verify that `clearAll()` is not what the endpoint calls.
- Two tests for the same tenant running concurrently would clear each other's mock. If that is a
  real use, scope the mock by test-run ID instead of clearing by tenant; otherwise serialise
  tests per tenant. Decide before implementing.

### Verification

A regression test should, for each of inbound and outbound:

1. Warm the real caches with device X (external-ID cache, inventory cache).
2. Run a dry run with `createNonExistingDevice = false` for X; assert "not found" and the
   `createNonExistingDevice is disabled` warning.
3. Assert the real caches are byte-for-byte unchanged and no subscription was opened.
4. Run a dry run with `createNonExistingDevice = true`, then another with `false`; assert the
   second does not find the device the first created.

Existing test scaffolding to build on:
[`InventoryCacheEnrichmentServiceTest`](../../dynamic-mapper-service/src/test/java/dynamic/mapper/core/InventoryCacheEnrichmentServiceTest.java),
[`TenantRegistryIsolationTest`](../../dynamic-mapper-service/src/test/java/dynamic/mapper/core/TenantRegistryIsolationTest.java).
