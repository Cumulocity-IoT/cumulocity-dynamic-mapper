# Mapping Testing (Dry-Run and Send Test)

Mapping testing lets a user validate a mapping's transformation logic against a sample
payload — either as a pure dry-run (no data reaches Cumulocity or the broker) or as a real
send (the transformed result is actually published/created). It exists so mappings can be
iterated on and debugged from the mapping editor without needing a live device, a connected
broker, or risking side effects on production data. Both inbound and outbound mappings share
the same backend endpoint and dispatcher entry points used for live processing — testing is
not a separate code path re-implementing the pipeline, it reuses
[`mapping-processing-inbound.md`](mapping-processing-inbound.md) /
[`mapping-processing-outbound.md`](mapping-processing-outbound.md) with mocked identity and
inventory services swapped in.

---

## Requirements

**What it is for.** Finding out what a mapping does to a payload without a device, a broker, or
data being written into the tenant.

- **A test takes a payload and returns what the mapping produced** — the resulting Cumulocity
  objects, or the error with enough detail to fix the mapping.
- **A dry run writes nothing.** Identity lookups are mocked and no object is created; the tenant's
  data is untouched.
- **A test can optionally be run for real** against a test device, when the user wants to see the
  object actually created.
- **Testing never changes the mapping's runtime state** — no message counters, no failure streaks,
  no auto-deactivation.
- **An inactive or draft mapping must be testable**; testing is how you check it before activating.
- **Test devices are identifiable and removable**, so a tenant can clean up what testing created.

---

## Implementation

### Backend endpoint

| Endpoint | Class | Description |
|---|---|---|
| `POST /test/mapping` | [`TestController.testMapping()`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/controller/TestController.java#L94-L207) | Executes one mapping against one payload and returns the generated request(s), warnings, errors, and logs. |
| `POST /webhook/echo/**` | `TestController.echoInput()` | Accepts any POST body and returns it unchanged — a scratch target for testing outbound webhook mappings against something other than a real endpoint. |
| `GET /webhook` | `TestController.echoHealth()` | Health check for the echo endpoint. |

The frontend calls this through `PATH_TESTING_ENDPOINT` (resolved to `/test`), so the full
path from the UI is `${BASE_URL}/test/mapping` — see
[`TestingService.testMapping()`](../../dynamic-mapper-ui/src/mapping/core/testing.service.ts#L44-L59).

#### Request/response shape

`TestContext` ([`TestContext.java`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/model/TestContext.java)):

| Field | Type | Meaning |
|---|---|---|
| `mapping` | `Mapping` | The full mapping definition to test (not looked up by ID — the UI sends the in-progress, possibly-unsaved edit). |
| `payload` | `String` | The JSON (or raw, for HEX/FLAT_FILE) payload to run through the mapping as a string. |
| `send` | `Boolean` | `false` (default) = dry-run only; `true` = actually publish/create the result. |
| `createTestDevice` | `Boolean` | Inbound only; when `send=true`, create a throwaway device in C8Y inventory before dispatching so identity resolution has something real to attach to. |

`TestResult` ([`TestResult.java`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/model/TestResult.java)):

| Field | Type | Meaning |
|---|---|---|
| `requests` | `List<DynamicMapperRequest>` | The Cumulocity/broker request(s) the transformation produced (per-device fan-out for inbound, one for outbound). |
| `errors` | `List<String>` | Flattened error messages, including causes joined in as `"... (caused by: ...)"`. |
| `warnings` | `List<String>` | E.g. a filter-mismatch warning. |
| `logs` | `List<String>` | Free-form log lines emitted during processing (including from Smart Function `console.log`-style output). |
| `success` | `Boolean` | `true` iff the resulting `ProcessingContext` had no errors. |
| `testDeviceId` | `String` | Set only when `createTestDevice=true` and a device was actually created. |
| `key` | `String` | The broker message key the transformation produced (e.g. mapped to `_CONTEXT_DATA_.key`), context-level rather than per-request. |

### What is simulated vs. real

| Condition | Identity/inventory resolution | Publish/create side effects |
|---|---|---|
| Inbound, `send=false` | Mocked (`MockIdentity`, mock inventory) — see below | None |
| Inbound, `send=true` | **Real** Cumulocity services | Real device upsert + real MEAO creation |
| Outbound, `send=false` | Mocked, always | None |
| Outbound, `send=true` | Mocked, always (see below) | Published via every connected non-TEST connector that has the mapping deployed |

Inbound and outbound differ here. `CamelDispatcherInbound.processMessage()` computes
`testing = testMapping != null && !sendPayload`
([`CamelDispatcherInbound.java:97-98`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/processor/inbound/CamelDispatcherInbound.java#L97-L98)):
once `send=true`, the source topic really is a device sending real data, so identity
resolution switches to real C8Y services. `CamelDispatcherOutbound.processNotification()`
instead sets `testing = testMapping != null` unconditionally
([`CamelDispatcherOutbound.java:187`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/processor/outbound/CamelDispatcherOutbound.java#L187),
with the accompanying comment explaining why) — an outbound test's source payload is always
a synthetic template typed into the UI, never a real Cumulocity object, so there is no real
device to resolve against even when actually sending.

`testing=true` routes identity lookups to
[`MockIdentity`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/core/mock/MockIdentity.java)
via [`IdentityFacade`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/core/facade/IdentityFacade.java#L62-L100)
— an in-memory, per-tenant `ConcurrentHashMap`-backed store, not real Cumulocity API calls.
The UI clears this mock state before each test run and on "Reset Transformation" by calling
`resetMockCache()`, which dispatches two `CLEAR_CACHE` operations (`MOCK_IDENTITY_CACHE`,
`MOCK_INVENTORY_CACHE`) handled in `OperationController`. Without this reset, resolution
results from a previous test run (e.g. an implicitly-created mock device) would leak into
the next one.

#### Inbound `send=true`: test device creation

When `send=true`, `createTestDevice=true`, and the mapping is `INBOUND`, `testMapping()`
creates a real throwaway device in inventory before dispatching
([`TestController.java:108-129`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/controller/TestController.java#L108-L129)),
so real identity resolution has something to find. The external ID to register it under is
determined, in order of preference:

1. `resolveExternalIdViaDryRun()` — actually runs the mapping once in dry-run mode
   (`send=false`) first, and reads the `externalId` the substitution logic produced at
   runtime, rather than guessing statically.
2. `extractIdentityFromSourceTemplate()` — falls back to reading `_IDENTITY_.externalId`
   directly out of the static `sourceTemplate` JSON.
3. `deriveExternalIdFromTopic()` — last resort: the last non-wildcard segment of
   `mapping.getMappingTopicSample()`.

### Dispatch reuse

Testing does not have its own processing pipeline — it calls the same dispatcher entry
points used for live traffic, with a `testMapping` argument:

- Inbound: `connectorClient.getDispatcher().onTestMessage(testMessage, mapping)` →
  `CamelDispatcherInbound.onTestMessage()` → the same `processMessage()` used by
  `onMessage()`. The synthetic `ConnectorMessage` is built by `createTestMessage()`, using
  `mapping.getMappingTopicSample()` as the topic.
- Outbound: `connectorRegistry.getDispatcher(tenant, TestClient.TEST_CONNECTOR_IDENTIFIER)
  .onTestNotification(testNotification, mapping, send)` → `CamelDispatcherOutbound
  .onTestNotification()` → `processNotification()`. The synthetic `Notification` is built by
  `createTestNotification()`, which constructs Notification 2.0-style headers
  (`/{tenant}/{apiPath}`, operation `CREATE`) and parses them with `Notification.parse()`.

Because both paths run the real Camel routes, a test result reflects the actual pipeline —
including filters (`filterMapping`/`filterInventory`), identity resolution, and the
transformation dispatch described in the two processing-pipeline docs — not a simplified
approximation.

`TestController` waits synchronously on the returned `Future`
(`processingResultWrapper.getProcessingResult().get()`) and takes only the first resulting
`ProcessingContext` — a mapping that fans out to multiple devices (inbound, multiple
`expandArray` substitutions) will log a warning that only the first result is returned to
the UI.

### Frontend

| Component/service | File | Role |
|---|---|---|
| `TestingService` | [`mapping/core/testing.service.ts`](../../dynamic-mapper-ui/src/mapping/core/testing.service.ts) | Thin HTTP client: `testMapping()` POSTs to `/test/mapping`; `resetMockCache()` clears the two mock caches via `SharedService.runOperation(CLEAR_CACHE, ...)`. |
| `MappingStepTestingComponent` | [`mapping/step-testing/mapping-testing.component.ts`](../../dynamic-mapper-ui/src/mapping/step-testing/mapping-testing.component.ts) | The mapping-editor stepper step (`d11r-mapping-testing`) that drives testing: payload editor, request/response viewers, console log panel. |

Both class names match what is recorded in project memory. Confirmed against current code
— `MappingStepTestingComponent` (not a generically-named `MappingTestingComponent`) is the
selector `d11r-mapping-testing`.

Key interactions in `MappingStepTestingComponent`:

- `onTestTransformation()` / `onSendTest()` both call `executeTest(sendPayload)`, which calls
  `performTest()` (builds the `TestContext` and posts it) then `handleTestResult()`.
- `onResetTransformation()` re-patches the source template for testing
  (`patchC8YTemplateForTesting`), calls `resetTestingModel()`, and re-clears the mock caches.
- `resetTestingModel()`
  ([`mapping-testing.component.ts:240-249`](../../dynamic-mapper-ui/src/mapping/step-testing/mapping-testing.component.ts#L240-L249))
  clears `testingModel` (`results`, `request`, `response`, `logs`) and the selected-result
  index. **Confirmed present in current code** — this is the fix referenced in project
  memory for the "empty payload editor" issue: without calling it after a mapping update, the
  previous test's request/response could remain displayed against a new mapping/payload.
- `displayTestResult(index)`
  ([`mapping-testing.component.ts:270-298`](../../dynamic-mapper-ui/src/mapping/step-testing/mapping-testing.component.ts#L270-L298))
  calls `sortObjectKeys()` on both `result.request` and `result.response` before handing them
  to the JSON editors. **Confirmed present in current code** — this is the fix referenced in
  project memory for "stale response in test results": without sorting, the tree editor can
  appear to show a previous result's key ordering/structure until the user notices the values
  changed.
- A result with `createNonExistingDevice is disabled` in its warnings triggers a confirmation
  modal offering to flip `testMapping.createNonExistingDevice = true` and re-run the test
  automatically.
- Errors carrying `possibleIgnoreErrorNonExisting` similarly offer to re-run with
  `createNonExistingDevice = true`.

### Known gotchas

- **Only the first result is shown** when a mapping fans out to multiple `DynamicMapperRequest`s (see above) — the backend logs a warning but the UI has no indication beyond that server log.
- **Outbound `useExternalId` requirement**: `disableTestSending()` in the UI disables "Send Test Message" unless `testMapping.useExternalId` is set, since outbound test payloads have no real external ID to resolve otherwise.
- **HEX/FLAT_FILE mappings**: `requiresRawPayload()` changes what is sent as `payload` (reads `sourceTemplate['payload']` instead of the whole JSON template) and seeds a static log line warning that whitespace/line-ending parsing may differ from a real device.
- **Mock cache must be reset between unrelated test runs** — `ngOnInit()` and `onResetTransformation()` both call `resetMockCache()`, but a test run that fails before reaching the reset step can leave stale mock identity/inventory state for the next attempt within the same session.
