# Mapping Testing Isolation (Mock vs. Live)

A mapping test (see [`mapping-testing.md`](mapping-testing.md)) runs against mocked identity and
inventory services instead of the tenant's real ones. This page is the contract that keeps the two
worlds apart: nothing a dry run does may be visible to live processing, nothing live may change what
a dry run reports, and one dry run may not influence the next.

It exists because the separation was found to be incomplete: the mock itself was isolated, but the
caches in front of it were shared with production (see [Defects found and fixed](#defects-found-and-fixed)).

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
   - A device the mock does not know is reported as not found. The test never invents a device on
     the user's behalf: with `createNonExistingDevice = false` the result carries the
     `createNonExistingDevice is disabled` marker (as a warning or an error), and the UI offers to
     re-run *assuming the device exists*. Only the user's confirmation switches the test to
     `createNonExistingDevice = true`.
   - Mock IDs cannot be mistaken for real ones because no live cache is consulted or written in test
     mode, so an ID collision has no effect.
   - The isolation holds for inbound and outbound tests alike, and for every transformation type.
   - Isolation applies to `send = false` only where the mock is in use. A `send = true` inbound test
     deliberately uses the real services (see [`mapping-testing.md`](mapping-testing.md)) and is
     outside this guarantee.

2. **Clear caches between runs.**
   - Each test run starts from an empty mock: devices implicitly created by an earlier run
     (`createNonExistingDevice = true`) are not found by a later run.
   - The reset happens on the server side, before **and** after every run, including a failed one.
     It does not depend on the UI remembering to call it.
   - Clearing the mock never touches production caches, and clearing production caches never
     touches the mock.
   - Concurrent tests by different tenants do not see each other's mock state.
   - Accepted limitation: two simultaneous tests for the *same* tenant clear each other's mock.

3. **A Live Test never touches the real device.**
   - A live test (`send = true`) of an inbound mapping registers its test device under the external ID
     `d11r_liveTest_` + the external ID the mapping produces, and every lookup, implicit creation and request of
     that message uses the prefixed ID. The real device's external ID is never resolved, so a message
     is never written to the real device, and — the reason for the prefix — the real device's first
     message later is never written to the test device.
   - The prefix is applied wherever the mapping's external ID enters the pipeline: JSONata substitutions,
     Smart Function / extension results, and the SparkPlug B NBIRTH/DBIRTH paths.
   - Known gap: the SparkPlug B deserializer still reads the alias map of the *unprefixed* node during
     a live test (read-only), so a live test of NDATA/DDATA metrics may be decoded without alias names.

4. **A test never changes the mapping's runtime status.** Neither a Dry Run nor a Live Test updates the
   message and error counters, the processing statistics, or the consecutive-failure streak — and so a
   failing test can never auto-deactivate the mapping. This holds for the inbound Live Test as well, which
   uses real services and therefore cannot be recognised by the mock flag alone.
5. **An outbound Live Test never addresses a real device.** The identity is mocked, but the result is
   published to the real broker, so the external ID the mock hands out carries the `d11r_liveTest_`
   prefix and the topic built from it cannot match a real device.

6. **A test never touches the Smart Function's state.** A Dry Run or Live Test of a Smart Function or Java
   extension starts from an empty state and writes nothing back, so a counter, average or dedup window kept in
   `context.setState` in production is neither read nor overwritten by testing.
7. **A Live Test only ever uses its test device.** A mapping that names a device by its Cumulocity ID
   (`_IDENTITY_.c8ySourceId`, or a Smart Function emitting `sourceId`) cannot be live-tested: the test is
   refused with an explanation instead of writing to that real device. The same applies to a mapping that does
   not identify its device by an external ID at all.
8. **A test raises no alarms and no operational metrics.** Processing-issue alarms are not created for a test
   run, and the inbound/outbound message counters and timers do not count test traffic. (An inbound Live Test
   still counts its handful of identity lookups in the identity-request counter.)
9. **A test cannot hang the request.** A test that does not finish within the pipeline timeout is cancelled and
   answered with a timeout error.

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

### Defects found and fixed

All of these are fixed on branch `fix/properIDHandling`. Each one let live state into a dry run, or a
dry run's state into live processing, because a cache sat in front of the mock and was not keyed by
`testing`.

| # | Where | Defect | Direction | Fix |
|---|---|---|---|---|
| D1 | `C8YAgent.resolveExternalId2GlobalId` | Read the production `InboundExternalIdCache` before consulting `testing`; only the write was guarded. | live → test | Cache is neither read nor written when `testing`. |
| D2 | `IdentityResolutionService.getOrCreateDeviceThreadSafe` | Read the `TenantRegistry` external-ID cache before checking `context.isTesting()`. | live → test | Reads skipped when testing. |
| D3 | `InventoryCacheEnrichmentService` | Read and wrote the shared `InventoryCache` by `sourceId`. A mock miss stored an empty map under a real device's ID, so `filterInventory` evaluated false for the live device. | both | No read or write of the live `InventoryCache` when testing. |
| D4 | same | A mock miss evicted a real external-ID cache entry. | test → live | Eviction only in production. |
| D5 | same | Opened a real Notification 2.0 subscription for the (possibly mock) ID. | test → live | Subscribe only in production. |
| D6 | `MockIdentity` / `MockInventory` | State persisted across runs; only the UI cleared it. | test → test | `TestController` clears both mocks for the tenant before and after every run. |
| D7 | `IdentityFacade.resolveGlobalId2ExternalId` | `!testing` on a `Boolean` threw on `null`. | consistency | `Boolean.TRUE.equals(testing)`. |
| D8 | `C8YAgent.resolveGlobalId2ExternalId` | Same read-before-check pattern on the `OutboundExternalIdCache`. | live → test | Cache bypassed when testing. |
| D9 | `SparkPlugBDeserializer.loadAliasMap` | Hard-coded `resolveExternalId2GlobalId(..., false)` and read the live `InventoryCache`; the method had no testing flag. | live → test | `testing` is passed through a new `PayloadDeserializer.deserializePayload(mapping, message, testing)` overload; the dry run resolves and fetches through the mock. |
| D10 | `addOrUpdateClientRelation` (client-relation cache) | Updated by dry runs. | test → live | Skipped when testing. |

**Consequence for SparkPlug.** A dry run of NDATA/NCMD/DDATA/DCMD only finds an alias map if the mock
holds one. Without it the metrics are decoded but not alias-resolved. Before the fix the test
silently used a real device's alias map.

**Not changed, deliberately.** `C8YAgent.createTestDevice` resolves with `testing=false`: it belongs
to the `send=true` flow, which uses real services by design.

### Where the guard lives

The rule is "when `testing`, no production cache is read, written, evicted or subscribed to". It is
applied at each cache access, not once at a boundary, so a new cache added in front of a facade must
repeat it. The regression tests below are the safety net for that.

### Tests

| Test | Pins |
|---|---|
| `MockIsolationRegressionTest` | D1, D2, D7, D8, per-tenant clear, and an end-to-end dry run with every live cache warmed: the answer comes from the mock, every live cache is identical afterwards, and no subscription is opened. A production-mode control proves the caches were warm. |
| `InventoryCacheEnrichmentServiceTest` | D3–D5 |
| `SparkplugBDeserializerTest` | D9: a dry run never resolves live, never touches the live cache, and fetches from the mock; production is unchanged |
| `TestControllerTest` | Clear before and after a run, after a failed run, and that a failing cleanup does not mask the result |
| `SubstitutionResultInboundProcessorTest` | A dry run with an unknown device and `createNonExistingDevice = false` fails with the marker instead of inventing an ID |
| `mapping-testing.component.spec.ts` (UI) | A Dry Run opens the dialog for the marker as a warning and as an error and re-runs with `createNonExistingDevice = true` on confirm; a Live Test never opens it; the dialog does nothing on cancel, without the marker, or when the flag is already on |
| `ProcessingContextLiveTestIdTest`, `ConsolidationProcessorTestRunTest`, `OutboundTestRunAccountingTest`, `DeserializationInboundProcessorErrorHandlingTest` | A test run (Dry Run or inbound Live Test) never counts toward the mapping status; a real run does |
| `CommonProcessorLiveTestIdTest` | An outbound live test registers the mock device under the prefixed external ID; a dry run keeps it unchanged |
| `TestControllerTest` (live test) | A live test always provides a test device (created or reused), ignores the deprecated flag, fails without sending when none can be provided, and a Dry Run never touches test devices |

Removing the guards in `C8YAgent` or the `finally` clear makes five of these fail.

### UI

`MappingStepTestingComponent.handleTestResult()` looks for the `createNonExistingDevice is disabled`
marker in the result's warnings **and** errors — flow and extension mappings report it as a warning,
substitution mappings as an error — and asks whether to assume, for this test, that the device
exists. Confirming sets `testMapping.createNonExistingDevice = true` and re-runs.

The dialog is a **Dry Run** feature: it offers to *assume the device exists* (the mock creates a throwaway device). A **Live Test** never offers it, because it always runs against a test device and confirming would create a real, untagged device through `createNonExistingDevice`.
