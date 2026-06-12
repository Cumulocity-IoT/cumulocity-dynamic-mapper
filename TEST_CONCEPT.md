# Dynamic Mapper — Test Concept

## Overview

This document describes the test strategy for the Cumulocity Dynamic Mapper across three test layers:

1. [Backend — Java unit & integration tests](#1-backend--java-unit--integration-tests)
2. [Frontend — Cypress E2E tests](#2-frontend--cypress-e2e-tests)
3. [System / Shell integration tests](#3-system--shell-integration-tests)

Coverage matrix: payload formats × transformation types × direction.

---

## Coverage Matrix

| Payload Type | Direction | DEFAULT | JSONATA | SMART_FUNCTION | EXTENSION_JAVA |
|-------------|-----------|:-------:|:-------:|:--------------:|:--------------:|
| JSON | Inbound | ✅ | ✅ | ✅ | ✅ |
| JSON | Outbound | ✅ | ✅ | ✅ | ✅ |
| FLAT_FILE | Inbound | ✅ | ✅ | ⬜ | ⬜ |
| FLAT_FILE | Outbound | 🚫 | 🚫 | 🚫 | 🚫 |
| HEX | Inbound | ✅ | ✅ | ⬜ | ⬜ |
| HEX | Outbound | 🚫 | 🚫 | 🚫 | 🚫 |
| PROTOBUF_INTERNAL | Inbound | ✅ | n/a | n/a | ✅ |
| PROTOBUF_INTERNAL | Outbound | 🚫 | 🚫 | 🚫 | 🚫 |
| SPARKPLUGB | Inbound | n/a | n/a | ⬜ | n/a |
| SPARKPLUGB | Outbound | n/a | n/a | ✅ | n/a |
| ANY_PAYLOAD | Inbound | n/a | n/a | ⬜ | ⬜ |
| ANY_PAYLOAD | Outbound | 🚫 | 🚫 | 🚫 | 🚫 |

**Legend:** ✅ covered · ⬜ gap · n/a not applicable · 🚫 not supported (direction rejected by model)

---

## 1. Backend — Java Unit & Integration Tests

**Location:** `dynamic-mapper-service/src/test/java/`
**Run:** `cd dynamic-mapper-service && mvn test`
**Run single class:** `mvn test -Dtest=GraalVMTest`

### 1.1 Unit Tests — Processing Pipeline

These tests exercise individual processor steps in isolation using Mockito mocks.

| Test Class | Coverage |
|------------|----------|
| `DeserializationInboundProcessorTest` | JSON, FlatFile, Hex deserialization; encoding variants |
| `DeserializationInboundProcessorErrorHandlingTest` | Null payload, malformed JSON, unknown format |
| `EnrichmentInboundProcessorTest` | Device lookup, topic-level extraction, `_IDENTITY_` resolution |
| `FlowInboundProcessorTest` | Camel route dispatch, mapping lookup by topic |
| `FlowResultInboundProcessorTest` | Result collection, error accumulation |
| `JSONataInboundProcessorTest` | JSONata expression evaluation on source payload |
| `SubstitutionResultInboundProcessorTest` | Substitution application to target template |
| `DeserializationOutboundProcessorTest` | C8Y notification deserialization |
| `EnrichmentOutboundProcessorTest` | External ID resolution, `_IDENTITY_` outbound |
| `FlowOutboundProcessorTest` | Outbound Camel route dispatch |
| `FlowResultOutboundProcessorTest` | Outbound result collection |
| `JSONataOutboundProcessorTest` | JSONata on C8Y payload, `filterMapping` evaluation |

**Note:** HEX outbound, FLAT_FILE outbound, and ANY_PAYLOAD outbound are not supported directions and require no tests.

### 1.2 Unit Tests — Smart Functions (GraalVM)

| Test Class | Tests |
|------------|-------|
| `GraalVMTest` | Sandbox isolation, polyglot context creation, resource limits |
| `SmartFunctionInboundTest` | Basic measurement; device enrichment (no inventory / found); implicit device create; flow state (telemetry / error / duplicate suppression) |
| `SmartFunctionOutboundTest` | Single measurement result; array result; Kafka transport fields; SparkplugB NCMD (active/inactive device); custom routing / operation forwarding |
| `SmartFunctionMappingTest` | Mapping config access, externalId resolution in outbound |

**Missing:** Sandbox security boundary tests (script injection, infinite loop, memory exhaustion); context isolation between tenants.

### 1.3 Unit Tests — Model & Expression Engine

| Test Class | Tests |
|------------|-------|
| `JsonataDashJoinLibTest` | Array/object/boolean/string/number extraction; non-existing path; null check; `$split`; date conversion; chain operator (`~>`) workaround |
| `MappingTreeTest` | Topic tree insert, lookup, wildcard matching (`+`, `#`) |
| `MappingsRepresentationTest` | Mapping serialization / deserialization round-trip |
| `EscapeEncodedPayloadTest` | Escaped payload encoding/decoding |
| `OutputCollectorTest` | Thread-safe result accumulation |
| `ProcessingStateTest` | AtomicBoolean flags, ConcurrentHashMap state |
| `RoutingContextTest` | Immutable context construction |
| `BuildersTest` | `CumulocityObject` and `DeviceMessage` builder pattern |
| `ContextMemoryBenchmark` | Per-context memory baseline (benchmark, not a functional test) |

### 1.4 Integration Tests — Full Pipeline (Mocked C8Y)

These wire the full Apache Camel pipeline with a mocked `C8YAgent`.

#### Inbound

| Test Class / Method | What it verifies |
|--------------------|-----------------|
| `CamelPipelineInboundIntegrationTest` | |
| `testDispatcherOnMessage_SimpleJSON` | JSON message → measurement |
| `testDispatcherOnMessage_FlatFile` | FlatFile message → measurement |
| `testDispatcherOnMessage_SmartFunction` | Smart Function execution end-to-end |
| `testDispatcherOnMessage_SystemTopicIgnored` | `$SYS/` topics are silently dropped |
| `testDispatcherOnMessage_NullPayloadIgnored` | Null payload does not propagate |
| `testDispatcherOnMessage_NoMatchingMapping` | Unmatched topic produces no output |
| `testDispatcherOnMessage_MultipleMappings` | Multiple mappings on same topic all fire |
| `testDispatcherOnTestMessage` | Test-mode message runs transformation without C8Y write |
| `testPayloadProcessing_JSONWithTopicLevelExtraction` | `_TOPIC_LEVEL_` used to resolve device ID |
| `testPayloadProcessing_FlatFileFormat` | CSV delimiter / field extraction |
| `testPayloadProcessing_ArrayExpansion` | Multi-value array → multiple C8Y requests |
| `InboundTransformationValidationTest` | |
| `testActualTransformation_SimpleMeasurement` | Full substitution pipeline, validates C8Y payload |
| `testActualTransformation_MinimalPayload` | Minimal required fields only |
| `testActualTransformation_FieldExtraction` | Nested field access |
| `testActualTransformation_Event` | Event API target |
| `testActualTransformation_Alarm` | Alarm API target |
| `testActualTransformation_NestedJSON` | Deep nesting, dot-path access |
| `testActualTransformation_ArrayPayload` | Array root payload |
| `testActualTransformation_TopicLevelExtraction` | `_TOPIC_LEVEL_` device ID |
| `testActualTransformation_SpecialCharacters` | Backtick-escaped property names |
| `testActualTransformation_EmptyJSON` | Empty `{}` input |
| `testActualTransformation_LargePayload` | 100-field payload |
| `testActualTransformation_InventoryCreation` | Implicit device creation |
| `testActualTransformation_MultipleMeasurements` | Multi-measurement batch |
| `MappingInboundExecutionIntegrationTest` | |
| `testMapping01_ExecuteTopicLevelExtraction` | Topic level → device ID end-to-end |
| `testMapping02_ExecuteArrayExpansion` | Array expansion |
| `testMapping03_ExecuteWithFilterExpression` | JSONata filter expression |
| `testMapping04_ExecuteFlatFileProcessing` | FlatFile pipeline |
| `testJSONataExpressionEvaluation` | Complex JSONata expressions |
| `testMapping07_ArrayRootPayload_JSONataExtraction` | Array-root + JSONata |

#### Outbound

| Test Class / Method | What it verifies |
|--------------------|-----------------|
| `CamelPipelineOutboundIntegrationTest` | |
| `testDispatcherOnNotification_MeasurementCreate` | Measurement → broker |
| `testDispatcherOnNotification_EventCreate` | Event → broker |
| `testDispatcherOnNotification_AlarmCreate` | Alarm → broker |
| `testDispatcherOnNotification_OperationCreate` | Operation CREATE → broker |
| `testDispatcherOnNotification_UpdateOperationIgnored` | Operation UPDATE silently dropped |
| `testDispatcherOnNotification_DeleteOperationIgnored` | Operation DELETE silently dropped |
| `testDispatcherOnNotification_DisconnectedConnectorIgnored` | Disconnected connector skipped |
| `testDispatcherOnTestNotification` | Test-mode notification |
| `testDispatcherOnNotification_MultipleMappings` | Multiple outbound mappings fire |
| `testResolvedPublishTopic_WithWildcardSubstitution` | Wildcard topic resolved from device external ID |
| `testResolvedPublishTopic_StaticTopic` | Static publish topic |
| `testPayloadTransformation_EventToMQTT` | Event payload → custom MQTT payload |
| `testPayloadTransformation_MeasurementToMQTT` | Measurement payload → custom MQTT payload |
| `testCompleteTransformationPipeline_EndToEnd` | Full pipeline including filter mapping |
| `OutboundTransformationValidationTest` | |
| `testActualTransformation_MeasurementToMQTT` | Validates published JSON structure |
| `testActualTransformation_EventToMQTT` | Event → MQTT payload shape |
| `testActualTransformation_AlarmToMQTT` | Alarm → MQTT payload shape |
| `testActualTransformation_ResolvedPublishTopicWithWildcard` | Topic resolution with wildcard |
| `testActualTransformation_StaticPublishTopic` | Static topic passthrough |
| `testActualTransformation_MultiLevelTopicResolution` | Multi-level topic with substitutions |
| `testActualTransformation_InternalFieldsRemoved` | `_IDENTITY_`, `_CONTEXT_DATA_` stripped from output |
| `testActualTransformation_NestedC8YPayload` | Nested C8Y structures |
| `testActualTransformation_OperationFiltering` | `filterMapping` expression on operation |
| `testActualTransformation_LargeC8YMeasurement` | Large measurement with many fragments |
| `testActualTransformation_SpecialCharactersInC8Y` | Special chars in C8Y payload |
| `testActualTransformation_DeviceIdentifierExtraction` | External ID extraction |
| `MappingOutboundExecutionIntegrationTest` | |
| `testMapping51_ExecuteTopicResolution` | Dynamic topic resolution |
| `testMapping52_ExecuteMeasurementTransformation` | Measurement end-to-end |
| `testMapping54_ExecuteStaticTopicPublish` | Static publish topic |
| `testResolvedPublishTopicCalculation` | Topic calculation logic |
| `testPayloadTransformationStructure` | Output JSON structure |

#### Scenario Tests (cross-cutting)

| Test Class / Method | What it verifies |
|--------------------|-----------------|
| `MappingScenarioIntegrationTest` | |
| `testMapping01_TopicLevelExtraction` | |
| `testMapping02_ArrayExpansionWithTimestamp` | |
| `testMapping03_InventoryCreation` | |
| `testMapping06_MultiArrayDeviceCreation` | Multi-device expansion |
| `testMapping08_RepairStrategyRemoveIfMissing` | `REMOVE_IF_MISSING_OR_NULL` strategy |
| `testMapping09_ConditionalFragmentCreation` | JSONata conditional |
| `testMapping10_HexPayloadType` | HEX format |
| `testMapping12_HexWithSubstitutions` | HEX + substitutions |
| `testMapping14_ProtobufInternal` | PROTOBUF_INTERNAL format |
| `testMapping15_ExtensionJava` | Java extension processor |

### 1.5 Connector Unit Tests

| Test Class | Coverage |
|------------|----------|
| `MQTT3ClientTest` | Constructor, `initialize()`, SSL/self-signed cert, WebSocket config, clean session, wildcard topic support |
| `AMQPClientTest` | AMQP connector initialization and configuration |
| `WebHookTest` | Webhook connector HTTP endpoint binding |
| `MQTTServicePulsarClientTest` | Pulsar client construction and config |
| `ConnectorConfigurationServiceTest` | Connector config CRUD, validation |

**Missing:** MQTT5Client, KafkaClient, PulsarConnectorClient unit tests. Connector reconnect / retry logic under simulated network failure.

### 1.6 Gaps in Backend Tests

> **Not supported** (rejected by `MappingTypeDescriptionMap` — `directionSupported: false`):
> HEX outbound, FLAT_FILE outbound, PROTOBUF_INTERNAL outbound, ANY_PAYLOAD outbound.
> These combinations do not exist and must **not** be tested.

| Gap | Status | New test class |
|-----|--------|----------------|
| ~~HEX outbound~~ | 🚫 Not supported | — |
| ~~FLAT_FILE outbound~~ | 🚫 Not supported | — |
| ~~ANY_PAYLOAD outbound~~ | 🚫 Not supported | — |
| ANY_PAYLOAD inbound (SMART_FUNCTION + EXTENSION_JAVA) | ✅ Implemented | `AnyPayloadInboundTest` |
| SPARKPLUGB inbound deserialization | ✅ Implemented | `SparkplugBDeserializerTest` |
| GraalVM sandbox security | ✅ Implemented | `GraalVMSandboxSecurityTest` |
| Multi-tenancy isolation | ✅ Implemented | `MultiTenancyIsolationTest` |
| Connector retry / reconnect | ✅ Implemented | `ConnectorRetryReconnectTest` |
| Kafka producer configuration | ✅ Implemented | `KafkaConnectorUnitTest` |

---

## 2. Frontend — Cypress E2E Tests

**Location:** `dynamic-mapper-ui/cypress/`
**Run:** `cd dynamic-mapper-ui && npm test` (headless) or open Cypress interactively

### 2.1 Existing Coverage

| File | Tests |
|------|-------|
| `e2e/configuration.cy.ts` | Add MQTT connector (validates POST body); Delete connector |

### 2.2 Required Coverage (gaps)

#### Connector UI

| Test | Description |
|------|-------------|
| Add connector — each type | MQTT 3.1.1, MQTT 5.0, Kafka, HTTP, Webhook, AMQP 0.9.1, AMQP 1.0, Pulsar |
| Edit connector | Update property, verify PATCH |
| Enable / Disable connector | Toggle active state |
| Connection status display | Shows CONNECTED / DISCONNECTED / ERROR in UI |

#### Mapping Table

| Test | Description |
|------|-------------|
| Create inbound mapping — JSON / DEFAULT | Full stepper wizard walkthrough |
| Create inbound mapping — JSON / JSONATA | Verify expression editor |
| Create inbound mapping — JSON / SMART_FUNCTION | JS editor rendered, save |
| Create inbound mapping — FLAT_FILE | Delimiter config |
| Create inbound mapping — HEX | Hex template |
| Create inbound mapping — ANY_PAYLOAD | Extension selection dropdown |
| Create outbound mapping | `filterMapping` field required; publishTopic field |
| Activate / Deactivate mapping | Lock/unlock behavior |
| Delete mapping | Confirmation, removed from table |
| Import mappings | Upload JSON, verify rows appear |
| Export mappings | Download triggered, valid JSON |

#### Mapping Stepper Steps

| Step | Tests |
|------|-------|
| Connector selection | Must select ≥1 connector before proceeding |
| Topic definition | Subscription topic / mapping topic / sample topic validation |
| Template editor | Source/target JSON editing, substitution creation |
| Substitution modal | `expandArray`, `repairStrategy`, `resolveToExternalId` options |
| Transformation editor (Smart Function) | JS editor loaded, syntax highlight |
| Test transformation | Results shown, error messages surfaced |
| Send test message | Test device created, visible in Testing tab |

#### Monitoring & Tools

| Test | Description |
|------|-------------|
| Monitoring tab | Processed / error counts visible |
| Mapping Tree | Tree renders for active inbound mappings |
| Message Explorer | Live message list updates |
| Test Device tab | Generated test devices listed, deletable |

#### Configuration

| Test | Description |
|------|-------------|
| Service configuration | Save/load settings |
| Processor Extension upload | JAR upload dialog, extension appears in list |
| Extension selection in mapping | Extension name shown in Transformation dropdown |

### 2.3 Recommended Approach — `cumulocity-cypress`

`cumulocity-cypress` is already installed and partially integrated (`cy.getAuth()`, `cy.hideCookieBanner()`, `cy.disableGainsight()`, `cy.visitAndWaitForSelector()` are used in the existing `configuration.cy.ts`). The following steps complete the integration and establish the pattern for all new tests.

#### Step 1 — Complete the Plugin Setup

`cypress.config.ts` does not yet load `configureC8yPlugin`. Add it to unlock the record/replay infrastructure:

```ts
import { defineConfig } from 'cypress';
import { configureC8yPlugin, configureEnvVariables } from 'cumulocity-cypress/plugin';

export default defineConfig({
  e2e: {
    baseUrl: 'http://localhost:4200',
    setupNodeEvents(on, config) {
      configureC8yPlugin(on, config);
      configureEnvVariables(config); // reads go-c8y-cli session env vars automatically
      return config;
    },
    env: { commandDelay: 150 },
  },
  viewportWidth: 1920,
  viewportHeight: 1080,
  video: true,
});
```

Also extend `cypress/support/e2e.ts`:

```ts
import 'cumulocity-cypress/lib/commands/';
import 'cumulocity-cypress/lib/commands/c8ypact';    // record & replay
import 'cumulocity-cypress/lib/commands/intercept';  // intercept-aware mocking
import './commands';
```

#### Step 2 — Use `c8ypact` Record/Replay Mode

This is the key feature that makes tests **run without a live tenant in CI**. Record once against a real environment; replay against fixtures from then on.

```json
// cypress.env.json — set C8Y_PACT_MODE=record to record, =apply to replay
{
  "C8Y_PACT_MODE": "apply",
  "C8Y_PACT_FOLDER": "cypress/fixtures/c8ypact"
}
```

| Mode | Behaviour |
|------|-----------|
| `record` | Proxies real API calls and saves request/response pairs to `C8Y_PACT_FOLDER` |
| `apply` | Replays saved fixtures — no network or live tenant required |
| _(unset)_ | Passes through to live tenant without recording |

#### Step 3 — File / Folder Organisation

```
cypress/e2e/
  connector/
    add-connector.cy.ts        ← all connector types (MQTT 3.1.1, MQTT 5.0, Kafka, HTTP, Webhook, AMQP, Pulsar)
    edit-connector.cy.ts
    toggle-connector.cy.ts
    connection-status.cy.ts
  mapping/
    inbound/
      json-default.cy.ts
      json-jsonata.cy.ts
      json-smart-function.cy.ts
      flat-file.cy.ts
      hex.cy.ts
      any-payload.cy.ts
    outbound/
      create-outbound.cy.ts    ← filterMapping required, publishTopic field
    table/
      activate-deactivate.cy.ts
      import-export.cy.ts
    stepper/
      connector-selection.cy.ts
      topic-definition.cy.ts
      substitution-modal.cy.ts
      test-transformation.cy.ts
  monitoring/
    monitoring-tab.cy.ts
    mapping-tree.cy.ts
    message-explorer.cy.ts
  configuration/
    service-config.cy.ts
    extension-upload.cy.ts
```

#### Step 4 — How to Use `cumulocity-cypress` Features per Area

**Authentication** — `configureEnvVariables` picks up credentials from a `go-c8y-cli` session in dev and from `cypress.env.json` / CI env vars in pipelines automatically. Use token-based auth in `cypress.config.ts` for speed:

```ts
import { oauthLogin } from 'cumulocity-cypress';
// in setupNodeEvents: obtain token once, store as C8Y_TOKEN env var
```

**Connector tests** — use `cy.c8yclient` to seed/clean connector state via the Dynamic Mapper REST API directly, avoiding brittle UI-only setup:

```ts
beforeEach(() => {
  cy.useAuth('admin');
  cy.c8yclient((c) =>
    c.core.fetch('/service/dynamic-mapper-service/configuration/connector/instances')
  ).then((resp) => { /* save connector IDs for afterEach cleanup */ });
});
```

**Mapping stepper tests** — stub the specification and mapping list calls so the stepper wizard renders predictably without a live service:

```ts
cy.intercept('GET', '/service/dynamic-mapper-service/configuration/connector/specifications').as('getSpecs');
cy.intercept('GET', '/service/dynamic-mapper-service/mapping*').as('getMappings');
cy.wait('@getSpecs');
```

**Monitoring tab** — use `cy.c8ymatch` to assert the shape of the monitoring API response without brittle field-level checks:

```ts
cy.c8yclient((c) =>
  c.core.fetch('/service/dynamic-mapper-service/monitoring/status/service')
).c8ymatch({ status: Cypress.c8ymatch.ignore });
```

**Screenshot automation** — use `cy.c8yscrn` for documentation screenshots of the mapping stepper and monitoring views:

```ts
cy.c8yscrn('mapping-stepper-step2');
```

#### Step 5 — Implementation Priority

| Priority | Area | Rationale |
|----------|------|-----------|
| 1 | Plugin setup + `c8ypact` wiring | Unlocks stub-mode for all subsequent tests |
| 2 | `connector/add-connector.cy.ts` | Highest-value gap; covers all connector types |
| 3 | `mapping/inbound/json-*.cy.ts` | Core feature; DEFAULT, JSONATA, SMART_FUNCTION |
| 4 | `mapping/stepper/` | Validates the wizard flow users interact with most |
| 5 | `mapping/outbound/` | `filterMapping` and topic resolution edge cases |
| 6 | `monitoring/` + `configuration/` | Lower risk; simpler assertions |

---

## 3. System / Shell Integration Tests

**Location:** `resources/script/test/`
**Prerequisites:** `c8y` CLI configured and authenticated; dynamic mapper microservice deployed

Run all tests with `run-tests.sh` (see [3.4 Test Runner](#34-test-runner)).

### 3.1 Inbound Tests

Publish a message to the broker and verify the resulting object appears in Cumulocity.

| Script | Scenario |
|--------|----------|
| `test-inbound-json-default.sh` | JSON / DEFAULT → MEASUREMENT |
| `test-inbound-json-jsonata.sh` | JSON / JSONATA expression → EVENT |
| `test-inbound-json-smartfunction.sh` | JSON / Smart Function (GraalVM) → MEASUREMENT |
| `test-inbound-flatfile.sh` | FLAT_FILE CSV / DEFAULT → MEASUREMENT |
| `test-inbound-hex.sh` | HEX / DEFAULT → EVENT |
| `test-inbound-http-connector.sh` | POST to HTTP connector endpoint → MEASUREMENT |
| `test-inbound-implicit-device.sh` | First-time device with `createNonExistingDevice` → managed object auto-created |
| `test-inbound-multi-device.sh` | Array payload with `expandArray` → one C8Y object per array element |

### 3.2 Outbound Tests

Create a C8Y object or manage a subscription and verify the broker or mapper state is updated.

#### Payload forwarding

| Script | Scenario |
|--------|----------|
| `test-outbound-measurement.sh` | C8Y MEASUREMENT CREATE → published to MQTT broker |
| `test-outbound-event.sh` | C8Y EVENT CREATE → published to MQTT broker |
| `test-outbound-alarm.sh` | C8Y ALARM CREATE → published to MQTT broker |
| `test-outbound-operation.sh` | C8Y OPERATION CREATE → published to MQTT broker (UPDATE/DELETE are ignored) |
| `test-outbound-filter.sh` | `filterMapping` JSONata expression blocks non-matching objects |
| `test-outbound-topic-resolution.sh` | Dynamic `publishTopic` wildcard resolved from device external ID |

#### Subscription management

| Script | Scenario |
|--------|----------|
| `test-outbound-static-subscription.sh` | Static subscription created → Notification 2.0 subscription exists in C8Y |
| `test-outbound-type-subscription.sh` | Device-type subscription → device of that type discovered and subscribed |
| `test-outbound-group-subscription.sh` | Group subscription → devices added to group are subscribed |
| `test-outbound-group-subscription-removal.sh` | Device removed from group → subscription deleted |
| `test-outbound-subscription-persistence.sh` | Static + dynamic subscriptions persist after microservice `disable`/`enable` cycle |

### 3.3 Reliability Tests

| Script | Scenario |
|--------|----------|
| `test-multi-tenant.sh` | Mapping CRUD round-trip: create → list → delete → verify gone |
| `test-multi-connector.sh` | All configured connectors report CONNECTED status |
| `test-reconnect.sh` | Connector disconnect → assert not connected → reconnect → assert CONNECTED |

### 3.4 Test Runner

`run-tests.sh` is a menu-driven suite runner for all integration tests.

```bash
# Interactive numbered menu
./run-tests.sh

# Run by category
./run-tests.sh all
./run-tests.sh inbound
./run-tests.sh outbound
./run-tests.sh reliability

# Run by menu number (single or multiple)
./run-tests.sh 3 7 11

# Run a single test by name
./run-tests.sh test-inbound-flatfile
```

| Feature | Detail |
|---------|--------|
| Per-test cleanup | Passes `--cleanup` to every script automatically |
| Suite summary | Pass / fail / skip counts + list of failed test names |
| Fail-fast mode | `DM_STOP_ON_FAIL=1` aborts the suite on first failure |
| ANSI colours | Auto-disabled when stdout is piped or redirected |

**Environment variables** (set before running):

| Variable | Default | Purpose |
|----------|---------|---------|
| `DM_SERVICE` | `/service/dynamic-mapper-service` | Dynamic mapper base path |
| `MQTT_HOST` | `localhost` | MQTT broker host |
| `MQTT_PORT` | `1883` | MQTT broker port |
| `MQTT_USER` | — | MQTT username (optional) |
| `MQTT_PASS` | — | MQTT password (optional) |
| `DM_CONNECTOR_ID` | auto-detected | Connector id for `test-reconnect.sh` |
| `DM_STOP_ON_FAIL` | `0` | Set to `1` to abort on first failure |

### 3.5 Conventions

- All scripts: `set -euo pipefail`, cleanup trap behind `--cleanup` flag, `c8y` CLI for all API calls
- Shared helpers (device creation, mapping CRUD, connector operations, C8Y data queries) are in `test-harness.sh`
- Inbound verification: count C8Y objects since test start using `dm_count_measurements_since` / `dm_count_events_since`
- Outbound verification: compare `messagesReceived` from the monitoring API before and after triggering a C8Y notification
- JSON assertions: always `jq '.field? // fallback'` — never assume the response is a non-empty object

---

## 4. Smart Function Module Tests

**Location:** `dynamic-mapper-smart-function/`
**Run:** `cd dynamic-mapper-smart-function && npm test`

Jest tests for the TypeScript type definitions and reference examples.

### Existing Coverage

- Type guards and mock helpers in `src/__tests__/`
- Reference implementation tests verifying `onMessage` signature and return types

### Gaps

- No tests for edge cases: null payload fields, missing `externalId`, malformed return value
- No tests for `context.getState` / `context.setState` persistence simulation

---

## 5. Known Issues and Fixes — Integration Test Hardening (May 2026)

During comprehensive test execution, several issues were identified and corrected in shell integration tests. This section documents the findings to guide future test maintenance.

### 5.1 Mapping Payload Validation

**Issue:** Outbound mappings (Smart Functions, extensions) were rejected with "Validation failed ... Error count: 3" or similar.

**Root causes:**
1. **Missing required fields:**
   - `identifier` (auto-generated identifier for the mapping) — now required by backend
   - `targetAPI` (e.g., `MEASUREMENT`, `ALARM`, `EVENT`) — required for all outbound mappings
   - `sourceTemplate` and `targetTemplate` (even if empty `{}`)
   - `filterMapping` (required for outbound; default: `"true"`)
   - `publishTopicSample` (for outbound mappings)

2. **Incorrect enum values:**
   - Extension type: tests used `INBOUND_PROCESSOR` / `OUTBOUND_PROCESSOR`
   - Backend enum is `EXTENSION_INBOUND` / `EXTENSION_OUTBOUND` (see `ExtensionType.java`)

**Fix:** Updated all mapping creation scripts to include required fields and correct enum values.

| Script | Changes |
|--------|---------|
| `test-outbound-json-smartfunction.sh` | Added `targetAPI`, `filterMapping`, `sourceTemplate`, `targetTemplate` |
| `test-outbound-extension-alarm-to-sparkplugb.sh` | Added `targetAPI`, templates, `publishTopicSample`; corrected `extensionType` |
| `test-inbound-extension-*.sh` (5 files) | Fixed `extensionType: "EXTENSION_INBOUND"` |
| `test-multi-tenant.sh` | Added `identifier` field to mapping JSON |

**Recommendation for maintainers:**
- Validate mapping payloads against `MappingValidator` logic before creating fixtures.
- Include `identifier` in all new test mappings.
- Use backend-defined enum values from `ExtensionType.java`.

### 5.2 External ID Binding

**Issue:** `test-outbound-json-smartfunction.sh` used incorrect API call format that failed silently.

**Root cause:** Tests were calling `dm_api POST "/identity/globalIdentities" --data "..."` with bare JSON, which is invalid for the `c8y` CLI. The correct call is `c8y identity create` with specific flags.

**Fix:** Replaced all identity binding calls with:
```bash
c8y identity create \
    --name "$EXTERNAL_ID" \
    --type "c8y_Serial" \
    --device "$DEVICE_ID" \
    --output json >/dev/null 2>&1 || dm_warn "..."
```

**Recommendation for maintainers:**
- Always use `c8y` CLI subcommands for identity operations (`c8y identity create`, not raw `/identity/globalIdentities`).
- Make identity binding warnings non-fatal (idempotent).

### 5.3 Subscription Verification Robustness

**Issue:** Tests failed with "0 is not > 0" when subscriptions were clearly created, or produced multiline output like `0\n0\n0...` breaking assertions.

**Root causes:**

1. **JSON stream handling:**
   - `/mapping` and `/subscription/*` endpoints return JSON-stream (newline-delimited documents).
   - Scripts using `jq` without slurping (`-s`) processed each document separately, producing one result per line.

2. **Response shape variability:**
   - Responses could be direct arrays, objects with `.data` / `.mappings` / `.types` wrappers, or single documents.
   - Tests assumed one shape and failed on variations.

3. **Type coercion:**
   - IDs can be numeric or string; direct comparison `(.id == $id)` failed on type mismatch.

**Fixes:**

1. **Slurping streams:**
   - All `jq` filters now use `jq -s` to collect all documents before processing.
   - Single-result counts are always numeric, not multiline.

2. **Shape normalization:**
   - Filters handle arrays, objects with nested arrays, wrapped objects, and direct documents.
   - Example:
   ```bash
   jq -s -r --arg id "$MAPPING_ID" '
     [ .[]
       | if type == "array" then .[]
         elif type == "object" and (.devices? != null) then .devices[]
         elif type == "object" then .
         else empty
         end
     ]
     | map(select((.id | tostring) == $id))
     | length
   '
   ```

3. **Type coercion:**
   - All ID comparisons use `(.id | tostring) == $id` to handle numeric ↔ string conversions.

| Script | Changes |
|--------|---------|
| `test-outbound-static-subscription.sh` | Switched to Direct Mapper API assertion (no per-device listing) |
| `test-outbound-type-subscription.sh` | Direct Mapper API assertion + wait-for-subscription polling |
| `test-outbound-group-subscription.sh` | Direct Mapper API assertion |
| `test-outbound-group-subscription-removal.sh` | Slurped jq; state validation + bootstrap; API-based group removal check |
| `test-outbound-subscription-persistence.sh` | Slurped jq; retry polling (60s window) for type restoration; string shape parsing |
| `test-multi-tenant.sh` | Slurped jq with shape normalization for dual listing checks |
| `test-harness.sh` | Added robust `dm_count_subscriptions` with type/shape handling; added `dm_wait_for_subscription_{present,absent}` polling helpers |

**Recommendation for maintainers:**
- Always use `jq -s` when processing API output that might be streamed.
- Normalize response shapes in filters (support arrays, wrapped objects, and mixed payloads).
- Use `tostring` for all ID comparisons.
- Test subscription assertions via Mapper APIs (e.g., `/subscription`, `/subscription/type`), not Notification2 per-device listing.

### 5.4 Subscription Semantics

**Issue:** `test-outbound-group-subscription-removal.sh` expected the group subscription entry to be deleted after removing a device from the group.

**Root cause:** Misunderstanding of correct behavior. Removing a device from a group should:
- Remove the device from the group's child assets (managed object hierarchy).
- **Not** delete the group subscription itself (the subscription remains; it just affects a different set of devices).

**Fix:** Updated the test to assert:
- Device is no longer in the group's child assets (`GET /inventory/managedObjects/{groupId}/childAssets`).
- Group subscription entry still exists (`GET /subscription/group` returns the group).

**Recommendation for maintainers:**
- Document the distinction between subscription definitions and subscription membership.
- Test group removal as a membership change, not a subscription deletion.

### 5.5 Eventual Consistency and Restart Timing

**Issue:** After microservice restart, subscription restoration tests failed intermittently with `expected='1' actual='0'` or empty response `{}`.

**Root causes:**

1. **Health check skipping:**
   - `run-tests.sh` sets `DM_SKIP_HEALTH_CHECK=1` globally for performance.
   - After restart, the persistence test inherited this flag and proceeded before subscriptions were reloaded.

2. **Delayed subscription restoration:**
   - Type subscriptions (`/subscription/type`) may not be immediately available after restart.
   - Static subscription entries persist immediately but type lookup was intermittently stale.

3. **Cleanup during shutdown:**
   - Cleanup handler was using strict `dm_api_must` which failed with 502 when service was restarting.

**Fixes:**

1. **Force readiness check after restart:**
   - Added `unset DM_SKIP_HEALTH_CHECK` and `dm_wait_for_service` after re-enable.
   - Added 10-second grace period for subscription initialization.

2. **Retry polling for type restoration:**
   - Added loop: up to 30 attempts × 2s = 60s total to wait for type subscription to appear.
   - Each loop fetches `/subscription/type` and checks for the target type.

3. **Lenient cleanup:**
   - Changed type subscription cleanup from strict `dm_set_type_subscriptions` to optional `dm_api PUT ... || true`.
   - Prevents cleanup from failing when service is briefly unavailable.

| Script | Changes |
|--------|---------|
| `test-outbound-subscription-persistence.sh` | Unset `DM_SKIP_HEALTH_CHECK`; added 10s post-restart wait; retry polling for type restoration (60s); lenient cleanup |

**Recommendation for maintainers:**
- Always unset global test-runner flags in tests that depend on specific service state.
- Use retry loops for assertions on asynchronously restored state (subscriptions, caches, etc.).
- Make cleanup operations best-effort when service state is transient.
- Document expected propagation windows (e.g., "subscriptions appear within 30s after restart").

### 5.6 Multi-tenant API Response Shapes

**Issue:** Multi-tenant listing assertions produced multiline output or always returned 0, even when the mapping was created.

**Root cause:** Same as [5.3](#53-subscription-verification-robustness) — the `/mapping` endpoint returns JSON-stream, and the test didn't slurp responses before filtering.

**Fix:** Applied the same slurping + shape normalization pattern as subscription tests.

| Script | Changes |
|--------|---------|
| `test-multi-tenant.sh` | Slurped both COUNT and COUNT2 assertions; added shape handling for mixed response types |

---

## 6. References

| Resource | Path |
|----------|------|
| Manual test cases (6.2.0) | `attic/test-plan/TestCases_6.2.0 (1).pdf` |
| Smart Function templates | `dynamic-mapper-service/src/main/resources/templates/` |
| Sample mappings | `resources/samples/` |
| Cypress tests | `dynamic-mapper-ui/cypress/` |
| Shell test scripts | `resources/script/test/` |
| Java test sources | `dynamic-mapper-service/src/test/java/` |
| Smart Function tests | `dynamic-mapper-smart-function/src/` |