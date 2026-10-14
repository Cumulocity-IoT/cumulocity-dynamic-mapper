# Mapping Testing (Dry Run and Live Test)

Mapping testing lets a user check what a mapping does to a sample payload from the mapping editor, without
waiting for a real device and without risking production data. There are two kinds of test:

| Test | Button | What it does |
|---|---|---|
| **Dry Run** | *Dry Run* | Runs the mapping against mocked identity and inventory. Nothing is written to the tenant and nothing is published to a broker. |
| **Live Test** | *Live Test* | Really sends the result: an inbound mapping writes to Cumulocity (always against a **test device**), an outbound mapping publishes to the connected brokers. |

Both inbound and outbound mappings use the same endpoint and the same processing pipeline as live traffic
([`mapping-processing-inbound.md`](mapping-processing-inbound.md),
[`mapping-processing-outbound.md`](mapping-processing-outbound.md)); a test is not a second implementation of it.
How tests are kept apart from live state is specified in [`mapping-testing-isolation.md`](mapping-testing-isolation.md).

---

## Requirements

**What it is for.** Finding out what a mapping does to a payload without a device, a broker, or data being
written into the tenant — and, when wanted, seeing the real object being created.

**Result**
- A test takes a payload and returns what the mapping produced — the resulting Cumulocity objects (inbound) or
  broker messages (outbound) — or the errors, with enough detail to fix the mapping.
- A mapping that fans out to several requests returns all of them; the editor steps through them.
- An inactive or draft mapping, and a mapping that has not been saved yet, must be testable; testing is how you
  check a mapping before activating it.

**Dry Run**
- A Dry Run writes nothing. Identity lookups are mocked and no object is created; the tenant's data, caches and
  subscriptions are untouched.
- If the device does not exist and the mapping's *Create device* setting is off, the Dry Run says so and offers to
  re-run *assuming the device exists* (the mock creates a throwaway device). It never invents a device silently.

**Live Test**
- A Live Test needs the mapping to use an external ID.
- An inbound Live Test always runs against a **test device**: a device registered under the external ID
  `d11r_liveTest_` + the external ID the mapping produces — never under the mapping's own external ID, so a real
  device's first message later is not written to the test device. It is created with the type and the fragment
  `d11r_testDevice` if it does not exist, and reused on the next Live Test.
- A Live Test never writes to a real device: it does not create devices through the mapping's *Create device*
  setting, it never offers the "assume the device exists" dialog, and it is refused for a mapping that names its
  device by Cumulocity ID (`_IDENTITY_.c8ySourceId`, a Smart Function `sourceId`) or does not identify it by an
  external ID at all — use a Dry Run for those.
- An outbound Live Test publishes to the topics of all connected, non-test connectors that deploy the mapping. No
  device is created: the identity is mocked, and the external ID in the topic carries the prefix
  `d11r_liveTest_`, so the message cannot reach a real device.
- The result says what was created, for which device, and whether the test device was created or reused.
- A test device that was deleted by hand is handled without user action (see *Deleted test devices* below).

**What a test does not change**
- Neither test changes the mapping's runtime state: message and error counters, the processing statistics, the
  consecutive-failure streak (and so auto-deactivation) stay as they were.
- Neither test reads or overwrites the Smart Function's persisted state, raises processing alarms, or counts as
  operational traffic in the message metrics.
- A test that does not finish within the pipeline timeout is cancelled and answered with a timeout error.

**Cleanup and permissions**
- Test devices are identifiable (type and fragment `d11r_testDevice`, external ID prefix) and removable from
  *Monitoring → Test device*. They are not removed automatically.
- Testing requires `ROLE_DYNAMIC_MAPPER_ADMIN` or `ROLE_DYNAMIC_MAPPER_CREATE`: with *Live Test* the result is
  written with the microservice's own credentials, so it must not be open to any authenticated user.

**Non-goals.** A Live Test does not target an arbitrary existing device. Test devices are not deleted
automatically. Two simultaneous tests of the same tenant are not isolated from each other (they share, and clear,
the tenant's mock).

---

## Implementation

### Backend endpoint

| Endpoint | Class | Description |
|---|---|---|
| `POST /test/mapping` | [`TestController.testMapping()`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/controller/TestController.java) | Executes one mapping against one payload and returns the generated request(s), warnings, errors and logs. |
| `POST /webhook/echo/**` | `TestController.echoInput()` | Accepts any POST body and returns it unchanged — a scratch target for testing outbound webhook mappings against something other than a real endpoint. |
| `GET /webhook` | `TestController.echoHealth()` | Health check for the echo endpoint. |

The UI reaches it through `PATH_TESTING_ENDPOINT` (`/test`) in
[`TestingService.testMapping()`](../../dynamic-mapper-ui/src/mapping/core/testing.service.ts).

#### Request/response shape

`TestContext` ([`TestContext.java`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/model/TestContext.java)):

| Field | Type | Meaning |
|---|---|---|
| `mapping` | `Mapping` | The full mapping definition to test (not looked up by ID — the UI sends the in-progress, possibly-unsaved edit). |
| `payload` | `String` | The JSON (or raw, for HEX/FLAT_FILE) payload to run through the mapping as a string. |
| `send` | `Boolean` | `false` (default) = Dry Run; `true` = Live Test. |
| `createTestDevice` | `Boolean` | **Deprecated, ignored.** An inbound Live Test always uses a test device. Kept so clients that still send it keep working. |

`TestResult` ([`TestResult.java`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/model/TestResult.java)):

| Field | Type | Meaning |
|---|---|---|
| `requests` | `List<DynamicMapperRequest>` | The Cumulocity/broker request(s) the transformation produced. |
| `errors` | `List<String>` | Flattened error messages, including causes joined in as `"... (caused by: ...)"`. |
| `warnings` | `List<String>` | E.g. a filter-mismatch warning. |
| `logs` | `List<String>` | Free-form log lines emitted during processing (including Smart Function console output). |
| `success` | `Boolean` | `true` iff no context had an error. |
| `testDeviceId` | `String` | Inbound Live Test: the test device that was created or reused. |
| `testDeviceCreated` | `Boolean` | `true`: created by this test (listed under *Monitoring → Test device*); `false`: reused from an earlier Live Test. |
| `testDeviceExternalId` | `String` | `d11r_liveTest_` + the mapping's external ID: the external ID the test device is registered under. |
| `key` | `String` | The broker message key the transformation produced, context-level rather than per-request. |

### What is simulated vs. real

| Test | Identity/inventory resolution | Publish/create side effects |
|---|---|---|
| Inbound Dry Run (`send=false`) | Mocked ([`MockIdentity`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/core/mock/MockIdentity.java), [`MockInventory`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/core/mock/MockInventory.java)) | None |
| Inbound Live Test (`send=true`) | **Real** Cumulocity services, against the test device | Real MEAO creation on the test device |
| Outbound Dry Run | Mocked | None |
| Outbound Live Test | Mocked, always | Published via every connected non-TEST connector that has the mapping deployed |

The inbound dispatcher computes `testing = testMapping != null && !sendPayload`
(`InboundMessageDispatcher.processMessage()`): once `send=true` the identity lookups go to the real services,
because the test device really exists. The outbound dispatcher sets `testing = testMapping != null` unconditionally
(`OutboundMessageDispatcher.processNotification()`): an outbound test's source payload is a synthetic template typed
into the UI, never a real Cumulocity object, so there is no real device to resolve even when actually sending.

`testing=true` routes lookups through [`IdentityFacade`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/core/facade/IdentityFacade.java)
and [`InventoryFacade`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/core/facade/InventoryFacade.java)
to the in-memory, per-tenant mocks. `testing` only means "use the mocks", and an inbound Live Test has it off.
What keeps a test away from the mapping's runtime state is the separate **`testRun`** flag
(see *Test runs and the mapping status*).

**Mock lifecycle.** `TestController.testMapping()` clears both mocks of the tenant before **and after** every run
(`clearMockCaches()`, the second in a `finally`), so a device created in the mock by one run is never found by the
next, and a failed or abandoned run leaves nothing behind. The UI also calls `resetMockCache()` on opening the step
and on *Reset Dry Run* (the `MOCK_IDENTITY_CACHE` / `MOCK_INVENTORY_CACHE` `CLEAR_CACHE` operations); that is now
redundant but harmless.

### Inbound Live Test: the test device

For `send=true` and an `INBOUND` mapping, `testMapping()` first finds the external ID the mapping produces:

1. `resolveExternalIdViaDryRun()` runs the mapping once as a Dry Run and reads the `externalId` the substitution
   produced at runtime.
2. `extractIdentityFromSourceTemplate()` falls back to `_IDENTITY_.externalId` in the static `sourceTemplate`.
3. If neither yields one, the Live Test stops (`liveTestFailure`) — it does not guess from the topic.

It then calls `C8YAgent.ensureTestDevice()` with `d11r_liveTest_` + that ID. `ensureTestDevice` first evicts the ID
from the inbound external-ID cache and the implicit-device-creation cache
(`C8YAgent.evictExternalIdFromCaches()`) so it never trusts a cached answer, looks the device up, and creates it
(type and fragment `d11r_testDevice`) if missing. The live message is then processed with
`ConnectorMessage.testExternalIdPrefix` set, which becomes `ProcessingContext.testExternalIdPrefix`;
`ProcessingContext.liveTestId()` applies it where the mapping's external ID enters the pipeline:
`SubstitutionResultInboundProcessor` (JSONata), `FlowResultInboundProcessor` / `ExtensibleResultInboundProcessor` via
`CommonProcessor.applyLiveTestExternalIdPrefix()` (Smart Functions and extensions, once per message, in place) and
the SparkPlug B paths in `SendInboundProcessor`. For an outbound Live Test the prefix is set by
`OutboundMessageDispatcher` and applied in `CommonProcessor.resolveGlobalId2ExternalId()` and the enrichment
fallback in `EnrichmentOutboundProcessor`.

A mapping that names its device by Cumulocity ID is refused in the pipeline
(`ProcessingContext.LIVE_TEST_C8Y_ID_ERROR`): the prefix only covers external IDs.

#### Deleted test devices

A deleted test device is not subscribed for inventory updates, so nothing evicts it from the caches. Two layers:
`ensureTestDevice` never trusts the cache (above); and if the send still fails with `404 No managedObject for id`,
the controller evicts the ID, re-ensures the device and retries **once**. If that fails too, the result carries an
error telling the user to clear the *Inbound ID cache* (*Monitoring → Cache statistic*, mapping admins only).

### Test runs and the mapping status

Every message from the test panel carries `ConnectorMessage.testRun = true`, which becomes
`ProcessingContext.testRun`. `ProcessingContext.affectsMappingStatus()` (`!testing && !testRun`) guards every place
that updates the mapping's counters, statistics, failure streak (`MappingService.increaseAndHandleFailureCount`,
which can auto-deactivate the mapping) and the streak reset in `ConsolidationProcessor`.

The same switch keeps a test run away from the other shared, per-mapping state: the Smart Function state store
(`FlowStateStore`) is not read or written (the run gets a throw-away state), no processing alarms are raised, and the
mapping status is not even looked up, because that creates an entry for a mapping that may not be saved yet.
The inbound/outbound message counters and timers skip dispatches that carry a `testMapping`; the identity-request
counter skips Dry Runs (an inbound Live Test still counts its few lookups there).

`TestController` waits for the pipeline only up to its timeout (`awaitContexts()`: the pipeline timeout plus a
margin), then cancels it and answers `504`.

### Dispatch reuse

A test calls the same dispatcher entry points as live traffic, with a `testMapping` argument:

- Inbound: `connectorClient.getDispatcher().onTestMessage(testMessage, mapping)` →
  `InboundMessageDispatcher.onTestMessage()` → the same `processMessage()` used by `onMessage()` → `InboundMessageRouter`.
  The synthetic `ConnectorMessage` is built by `createTestMessage()` from `mapping.getMappingTopicSample()`.
- Outbound: `connectorRegistry.getDispatcher(tenant, TestClient.TEST_CONNECTOR_IDENTIFIER).onTestNotification(testNotification, mapping, send)` →
  `OutboundMessageDispatcher.onTestNotification()` → `processNotification()` → `OutboundMessageRouter`. The synthetic
  `Notification` is built by `createTestNotification()` (Notification 2.0-style headers, operation `CREATE`).

Because tests run the real processor chain, a result reflects the actual pipeline — filters (`filterMapping`,
`filterInventory`), identity resolution and the transformation — not an approximation.

`TestController` takes only the first resulting `ProcessingContext` for `requests`, `warnings`, `logs` and `key`
(a mapping that fans out to several devices logs a warning), but it merges the **errors of every context**, so a
failure in a later one cannot make the test look green.

### Frontend

| Component/service | File | Role |
|---|---|---|
| `TestingService` | [`mapping/core/testing.service.ts`](../../dynamic-mapper-ui/src/mapping/core/testing.service.ts) | HTTP client: `testMapping()` POSTs to `/test/mapping`; `resetMockCache()` clears the two mock caches. |
| `MappingStepTestingComponent` | [`mapping/step-testing/mapping-testing.component.ts`](../../dynamic-mapper-ui/src/mapping/step-testing/mapping-testing.component.ts) | The stepper step (`d11r-mapping-testing`): payload editor, request/response viewers, console panel, the buttons. |
| Test device page | [`test-device/`](../../dynamic-mapper-ui/src/test-device/) | *Monitoring → Test device*: lists devices carrying the fragment `d11r_testDevice`. The header total counts test devices only. Deleting (row or bulk) is offered to mapping admins only; the outcome alert appears after the deletes have finished, reports partial failures, and the grid is refreshed either way. |

Key interactions in `MappingStepTestingComponent`:

- *Dry Run* and *Live Test* call `executeTest(sendPayload)` → `performTest()` (builds the `TestContext`) →
  `handleTestResult()`. *Show Next Test Result* steps through the requests; *Reset Dry Run* re-patches the source
  template (`patchC8YTemplateForTesting`), clears the results and the mock.
- `handleTestResult()` looks for `createNonExistingDevice is disabled` in the result's warnings **and** errors
  (flow/extension mappings report it as a warning, substitution mappings as an error). For a **Dry Run** with the
  flag off it asks whether to assume, for this test, that the device exists and re-runs with the flag set **for that
  one run** (`executeTest(send, assumeDeviceExists)`); it is not written into `testMapping`, so the next Dry Run asks
  again and a Live Test never carries it. It never asks for a Live Test.
- After a successful Live Test, `describeSendResult()` builds the alert: what was created, on which device, and
  whether the test device was created or reused.
- `disableTestSending()` disables *Live Test* unless sending is allowed for the mapping, the mapping uses an external
  ID and a Dry Run has produced a result; `liveTestDisabledReason` is the button's tooltip, so the user is told which
  of the three applies. *Dry Run* is disabled while a result is shown (tooltip: use *Reset Dry Run*). Only the button
  that started a test shows the spinner (`runningTest`).
- HEX/FLAT_FILE mappings send `sourceTemplate['payload']` instead of the whole JSON template
  (`requiresRawPayload()`) and the console carries a note that whitespace/line-ending parsing may differ from a device.

### Known gotchas and limits

- **`Mapping.tested`** is deprecated and ignored; testing sets and reads nothing on the mapping.
- **SparkPlug B:** an inbound Live Test of NDATA/DDATA reads the alias map of the *unprefixed* node (read-only), so
  metrics may be decoded without alias names. A Dry Run never finds an alias map: it only sees the mock, and nothing is stored in a Dry Run.
- **Operational metrics:** an inbound Live Test still counts its identity lookups in
  `dynmapper_inbound_identity_requests_total`.
- **Same tenant, two tests at once** share the tenant's mock and clear each other's.
- **Live Test needs a Dry Run first** in the UI (it is disabled until a result exists; the tooltip says so), and it
  can be clicked repeatedly, sending again each time.
- **The editor's source template** receives the test payload patch (a random id) because the testing step emits it
  to the stepper; whether that patch ends up in the saved mapping is determined by the stepper's commit
  (`reduceSourceTemplate` removes only the `_IDENTITY_`, `_CONTEXT_DATA_` and `_TOPIC_LEVEL_` tokens).
