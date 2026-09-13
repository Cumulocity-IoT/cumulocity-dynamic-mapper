# Dynamic Mapper — Test Concept

## Overview

This document describes the test strategy for the Cumulocity Dynamic Mapper across four test
layers:

1. [Backend — Java unit & integration tests](#1-backend--java-unit--integration-tests) — 86 classes, ~750 tests
2. [Frontend — Angular unit tests & Cypress E2E](#2-frontend--angular-unit-tests--cypress-e2e) — 25 specs (~420 tests) + 9 E2E specs
3. [System / Shell integration tests](#3-system--shell-integration-tests) — 40 scripts, runnable against two brokers
4. [Smart Function module tests](#4-smart-function-module-tests) — 4 Jest specs

Layers 1, 2 (unit) and 4 run without a tenant. Layer 2 (Cypress) and layer 3 need a deployed
mapper.

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
**Run single class:** `mvn test -Dtest=QosTest`
**Run single method:** `mvn test -Dtest=QosTest#clampDowngrades`

86 test classes, ~750 tests. The tables below group them by the area they cover; the package
path under `dynamic/mapper/` is given where it is not obvious from the name.

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
| `AbstractEnrichmentProcessorTest` (`processor/`) | Shared enrichment: status counters, GraalVM context acquisition, error handling |
| `SendInboundProcessorParallelTest` | Parallel request emission to Cumulocity |
| `AnyPayloadInboundTest` | ANY_PAYLOAD inbound via SMART_FUNCTION and EXTENSION_JAVA |
| `SparkplugBDeserializerTest` (`inbound/deserializer/`) | Sparkplug B decoding |

**Note:** HEX outbound, FLAT_FILE outbound, and ANY_PAYLOAD outbound are not supported directions and require no tests.

### 1.2 Unit Tests — Smart Functions (GraalVM)

| Test Class | Tests |
|------------|-------|
| `GraalVMContextServiceTest` (`core/`) | Polyglot context creation, pooling, engine rotation |
| `TenantRegistryGraalVMSandboxSecurityTest` (`core/`) | Sandbox boundary: host access, `process.env`, prototype pollution, infinite-loop interruption |
| `JavaScriptInteropHelperTest` (`processor/util/`) | Java ↔ JS value conversion |
| `AbstractFlowProcessorTest` (`processor/`) | Flow processor lifecycle, CPU-budget kill wiring |
| `SmartFunctionInboundTest` | Basic measurement; device enrichment (no inventory / found); implicit device create; flow state (telemetry / error / duplicate suppression) |
| `SmartFunctionOutboundTest` | Single measurement result; array result; Kafka transport fields; SparkplugB NCMD (active/inactive device); custom routing / operation forwarding |
| `SmartFunctionMappingTest` | Mapping config access, externalId resolution in outbound |

Sandbox boundary and tenant isolation are covered by `TenantRegistryGraalVMSandboxSecurityTest`
and `MultiTenancyIsolationTest`; what a runaway function does to the *pipeline* is covered by
`ProcessingCancellationTest` (§1.4a).

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

All under `connector/`.

| Test Class | Coverage |
|------------|----------|
| `MQTT3ClientTest` | Constructor, `initialize()`, SSL/self-signed cert, WebSocket config, clean session, wildcard topic support, QoS clamping |
| `MQTTServicePulsarClientTest` | Cumulocity MQTT Service connector: constructor, pre-wired connection properties, supported QoS |
| `AMQPClientTest` | AMQP initialization and configuration; QoS clamped to the connector capability; `supportedQos` reaching the specification |
| `GooglePubSubClientTest` | Ack-before-processing vs. ack-after-success / nack-on-error; topic resolution; config validation |
| `WebHookTest` | Webhook connector HTTP endpoint binding |
| `MappingSubscriptionManagerTest` (`core/client/`) | Reference-counted topic subscriptions, max-QoS-per-topic, QoS upgrade on add and on reconcile |
| `ConnectorRetryReconnectTest` (`core/client/`) | Disconnect → retry → reconnect cycle |
| `AConnectorClientSubscriptionInitRetryTest` (`core/client/`) | Retry of the initial subscription set-up |
| `KafkaTestClientTest` (`client/`) | Kafka producer configuration: SASL/plaintext, bootstrap servers, record shape |

### 1.5a Reliability — QoS, Timeouts, Failure Handling, Status

The behaviour described in [`docs/feature/reliability.md`](docs/feature/reliability.md).

| Test Class | Coverage |
|------------|----------|
| `QosTest` (`model/`) | Levels, `max`/`min`/`orDefault`, `clampTo` (downgrade, upgrade, no restriction), JSON wire format, `Mapping.qos` default |
| `ProcessingCancellationTest` (`processor/model/`) | A runaway `while(true){}` GraalVM context is killed and its thread terminates; cancel actions run (including when one throws); a worker that ignores interruption is reported as **not** drained |
| `ServiceConfigurationTimeoutTest` (`configuration/`) | `maxCPUTimeMS` / `pipelineTimeoutMS` defaults, null fallbacks, the `pipeline > cpu` invariant, no derived accessor leaking into the persisted configuration |
| `MappingStatusServiceFailureCountTest` (`service/status/`) | Consecutive-failure streak: threshold reported only on the failure that reaches it, `maxFailureCount == 0` never trips, success clears the streak |
| `MappingServiceFailureThresholdTest` (`service/`) | The mapping is really deactivated at the threshold, its connectors are told to drop it, and a burst of failures deactivates only once |
| `MappingStatusTest` (`model/`) | Counter semantics, per-tenant catch-all status, snapshot isolation, no lost updates under 8 concurrent writers, JSON shape unchanged |
| `MappingStatusPersistenceCompatibilityTest` (`service/status/`) | A status fragment written by an older release still loads with its counters and is still pushed back to the inventory |

### 1.5b Services, Controllers and Versioning

| Test Class | Coverage |
|------------|----------|
| `MappingServiceActivationTest` (`service/`) | Version-aware activation: lock, version swap, validate-before-persist |
| `MappingServiceVersionTest`, `MappingVersionServiceTest`, `MappingVersioningTest`, `MappingVersioningIntegrationTest` | Draft/publish/rollback, retention, backfill of legacy versions |
| `MappingValidatorTopicSampleTest`, `MappingValidatorFilterUniquenessTest` (`service/`) | Topic/sample consistency and outbound filter uniqueness — see [`mapping-validation.md`](docs/feature/mapping-validation.md) |
| `DeploymentMapServiceTest` (`service/deployment/`) | Which mappings are deployed to which connector; reconcile on change |
| `ServiceConfigurationServiceTest`, `ConnectorConfigurationServiceTest` (`configuration/`) | Configuration CRUD and defaults |
| `MappingControllerTest`, `OperationControllerTest`, `TestControllerTest` (`controller/`) | REST surface: mapping CRUD, service operations, the testing endpoint |
| `TenantRegistryTest`, `InventoryCacheEnrichmentServiceTest`, `MetricLRUCacheEvictionTest`, `GroupCacheManagerTest` | Per-tenant registries and cache eviction |
| `TenantRegistryIsolationTest` (`core/`) | Tenant isolation and cleanup of the external-ID cache, its reverse index and per-ID locks; same external/internal id in two tenants stays separate |
| `ProcessingModeServiceTenantTest` (`core/`) | Per-tenant `RestConnector` cache: bound to the right credentials, cleared per tenant, and not poisoned by an explicit tenant that disagrees with the ambient context |
| `MultiTenancyIsolationTest` (`integration/`) | External-ID cache isolation and eviction across tenants |
| `ExtensionManagerClassLoadingTest` (`core/`) | Processor-extension JAR class loading |
| `MappingJackson3CompatibilityTest`, `LoggingEventTypeFrontendSyncTest` (`model/`) | Serialization compatibility; event-type constants kept in sync with the frontend |
| `CumulocityErrorsTest` (`util/`) | Classification of transient platform errors |

### 1.5c Notification 2.0 / Outbound Subscriptions

All under `notification/`.

| Test Class | Coverage |
|------------|----------|
| `NotificationCallbackTest`, `NotificationHelperTest` | Notification dispatch and payload helpers |
| `NotificationConnectionManagerTest`, `TokenManagerTest` | WebSocket connection lifecycle and token renewal |
| `SubscriptionManagerTest`, `SubscriptionQueryServiceTest` | Subscription creation/removal, device queries |
| `DeviceDiscoveryServiceTest`, `UpdateSubscriptionDeviceGroupTaskTest` (`service/`, `task/`) | Type/group discovery and group-membership updates |
| `CacheInventoryUpdateClientTest`, `MqttPushManagerTest` | Inventory cache invalidation; MQTT Service push |
| `MQTTServicePulsarClientTest` | Pulsar client construction and config |
| `ConnectorConfigurationServiceTest` | Connector config CRUD, validation |

**Missing:** MQTT5Client, KafkaClientV2, PulsarConnectorClient, AMQP10Client and HttpClient unit tests.

### 1.6 Gaps in Backend Tests

> **Not supported** (rejected by `MappingTypeDescriptionMap` — `directionSupported: false`):
> HEX outbound, FLAT_FILE outbound, PROTOBUF_INTERNAL outbound, ANY_PAYLOAD outbound.
> These combinations do not exist and must **not** be tested.

Previously tracked gaps — ANY_PAYLOAD inbound, Sparkplug B inbound, GraalVM sandbox security,
multi-tenancy isolation, connector retry, Kafka producer configuration — are all implemented
(see the tables above). Still open:

| Gap | Why it matters |
|-----|----------------|
| SMART_FUNCTION over FLAT_FILE / HEX inbound | The coverage matrix marks these ⬜; only DEFAULT and JSONATA are exercised for those payload types |
| SPARKPLUGB inbound via SMART_FUNCTION | Outbound is covered (`SmartFunctionOutboundTest`), inbound is not |
| Connector unit tests for Kafka, Pulsar, AMQP 1.0, HTTP | Only MQTT 3, MQTT Service, AMQP 0.9.1, Pub/Sub and WebHook have a client-level test |
| `CamelDispatcherInbound` / `CamelDispatcherOutbound` error paths | Resolution failure and the 422-resend path are only reached indirectly |
| Outbound Smart Function + `filterInventory` | The partial-notification pitfall documented in `USERGUIDE.md` has no regression test |

---

## 2. Frontend — Angular Unit Tests & Cypress E2E

The frontend has **two** independent layers. Only the first runs without a tenant, and it is the
one CI can rely on.

| Layer | Location | Run | Needs a tenant? |
|---|---|---|---|
| Angular unit tests (Karma + Jasmine) | `dynamic-mapper-ui/src/**/*.spec.ts` | `cd dynamic-mapper-ui && npm test` | No |
| Cypress E2E | `dynamic-mapper-ui/cypress/e2e/**/*.cy.ts` | `cd dynamic-mapper-ui && npx cypress run` (or `npx cypress open`) | Yes — a deployed mapper and `C8Y_CYPRESS_URL` |

> `npm test` runs **`ng test`**, i.e. the Karma suite — not Cypress. There is no npm script for
> Cypress; invoke it through `npx`.

### 2.1 Angular Unit Tests

25 spec files, ~420 tests. These exercise component classes, services and cell renderers with
mocked dependencies.

| Area | Specs |
|------|-------|
| Mapping stepper & editor | `stepper-mapping/mapping-stepper.component.spec.ts`, `stepper-mapping/stepper-view.model.spec.ts`, `unified-editor/mapping-unified-editor.component.spec.ts`, `step-property/mapping-properties.component.spec.ts`, `step-template/mapping-template-step.component.spec.ts`, `step-connector/mapping-connector.component.spec.ts` |
| Substitutions | `substitution/substitution-grid.component.spec.ts`, `service/substitution-management.service.spec.ts` |
| Versioning | `versions/mapping-version-drawer.component.spec.ts`, `versions/mapping-versions-count.component.spec.ts`, `versions/publish-version-modal.component.spec.ts`, `versions/version-state-cell.renderer.component.spec.ts` |
| Cell renderers | `renderer/name.renderer.component.spec.ts`, `renderer/version-badge.renderer.component.spec.ts` |
| Models & helpers | `shared/mapping/mapping.model.spec.ts` (incl. QoS metadata and clamping), `shared/mapping/stepper-configuration.strategy.spec.ts`, `mapping/shared/util.spec.ts`, `mapping/core/processor/processor.model.spec.ts` |
| Services | `shared/service/connector-configuration.service.spec.ts`, `mapping/core/testing.service.spec.ts`, `mapping/service/mapping-stepper.service.spec.ts` |
| Connectors / subscriptions / monitoring | `shared/connector-configuration/edit/connector-configuration-drawer.component.spec.ts`, `mapping/subscription/subscription.component.spec.ts`, `monitoring/versions-tab.factory.spec.ts` |
| Mapping creation | `mapping/mapping-create/mapping-type-drawer.component.spec.ts` |
| Service configuration | `configuration/service-configuration.component.spec.ts` (expert-mode visibility, processing-budget validation) |

**Pattern for c8y standalone components:** their templates import `CoreModule`, which eagerly
reaches into the app-shell DI graph the test injector does not provide (`NG0201
ApplicationService`). Strip it with `TestBed.overrideComponent(..., { set: { imports: [],
schemas: [NO_ERRORS_SCHEMA] } })` while keeping the real template — see
`version-badge.renderer.component.spec.ts` and `name.renderer.component.spec.ts`.

**Known state:** 31 of ~420 specs currently fail on `develop`. They are pre-existing failures,
not regressions; treat the current count as the baseline when judging a change.

### 2.2 Cypress E2E — Existing Coverage

| File | Tests |
|------|-------|
| `e2e/configuration.cy.ts` | Add MQTT connector (validates POST body); delete connector |
| `e2e/connector/add-connector.cy.ts` | Create a connector through the drawer |
| `e2e/connector/edit-connector.cy.ts` | Update a property, verify the request |
| `e2e/connector/toggle-connector.cy.ts` | Enable / disable a connector |
| `e2e/connector/delete-connector.cy.ts` | Delete, confirm removal from the grid |
| `e2e/connector/connection-status.cy.ts` | CONNECTED / DISCONNECTED rendering |
| `e2e/mapping/mapping.cy.ts` | Mapping grid: list, activate, delete |
| `e2e/mapping/create-inbound-jsonata-mapping.cy.ts` | Inbound JSON / JSONATA through the stepper |
| `e2e/mapping/create-mapping-ui.cy.ts` | Stepper walkthrough driven purely through the UI |

### 2.2a Cypress E2E — Remaining Gaps

| Test | Description |
|------|-------------|
| Add connector — remaining types | Kafka, HTTP, Webhook, AMQP 0.9.1, AMQP 1.0, Pulsar, MQTT 5.0 (only MQTT 3.1.1 is covered) |
| Create inbound mapping — DEFAULT / SMART_FUNCTION | Only JSONATA is covered end-to-end |
| Create inbound mapping — FLAT_FILE / HEX / ANY_PAYLOAD | Delimiter config, hex template, extension dropdown |
| Create outbound mapping | `filterMapping` required, publishTopic field |
| Import / export mappings | Upload JSON → rows appear; download → valid JSON |
| Substitution modal | `expandArray`, `repairStrategy`, `resolveToExternalId` |
| Test transformation / send test message | Results and error messages surfaced; test device created |
| Monitoring | Processed/error counts; the "Unmapped messages" row pinned last |
| Mapping tree / Message Explorer | Tree renders; live message list updates |
| Service configuration | Save/load; the `pipelineTimeoutMS > maxCPUTimeMS` validation |
| Processor extension upload | JAR upload dialog, extension appears in the list |

### 2.3 Recommended Approach — `cumulocity-cypress`

`cumulocity-cypress` is already installed and partially integrated (`cy.getAuth()`, `cy.hideCookieBanner()`, `cy.disableGainsight()`, `cy.visitAndWaitForSelector()` are used in the existing `configuration.cy.ts`). The following steps complete the integration and establish the pattern for all new tests.

#### Step 1 — Plugin Setup ✅ done

`cypress.config.ts` loads `configureC8yPlugin(on, config)` and `cypress/support/e2e.ts` imports
`cumulocity-cypress/commands`, so the Node-side plugin (tenant-id task, env-var resolution,
version/auth handling) is active. Credentials are resolved from `CYPRESS_C8Y_*` / `C8Y_*`
environment variables, and `C8Y_BASEURL` deliberately points at the same-origin dev proxy —
using the absolute tenant URL makes `c8yclient` calls cross-origin and they fail with
"Failed to fetch".

Nothing to do here; the remaining steps are what is still outstanding.

#### Step 2 — Use `c8ypact` Record/Replay Mode ⬜ outstanding

This is the key feature that makes tests **run without a live tenant in CI**. Record once against
a real environment; replay against fixtures from then on. `cypress.env.json` exists but is still
empty (`{}`), and `cypress/fixtures/` holds only two hand-written request bodies — no recorded
pacts yet, so every E2E spec currently needs a live tenant.

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
| `test-inbound-json-jsonata.sh` | JSON / JSONATA → EVENT |
| `test-inbound-json-smartfunction.sh` | JSON / Smart Function → MEASUREMENT |
| `test-inbound-flatfile.sh` | FLAT_FILE / CSV → MEASUREMENT |
| `test-inbound-hex.sh` | HEX → EVENT |
| `test-inbound-http-connector.sh` | HTTP connector → MEASUREMENT |
| `test-inbound-implicit-device.sh` | Implicit device auto-creation |
| `test-inbound-implicit-device-recreate-after-delete.sh` | Implicit device recreation after delete (inconsistent-cache regression) |
| `test-inbound-multi-device.sh` | Array payload → multiple devices |
| `test-inbound-alarm.sh` | JSON / DEFAULT → ALARM |
| `test-inbound-operation.sh` | JSON / DEFAULT → OPERATION |
| `test-inbound-inventory.sh` | JSON / DEFAULT → INVENTORY (device metadata update) |

### 3.1a Inbound — Smart Function Patterns

The numbered patterns from the Smart Function cookbook, exercised end-to-end.

| Script | Scenario |
|--------|----------|
| `test-inbound-smartfunction-02.sh` | Inbound: Pattern 02: Topic-based external ID + sensor filter |
| `test-inbound-smartfunction-03.sh` | Inbound: Pattern 03: getManagedObjectByExternalId — MO enrichment |
| `test-inbound-smartfunction-04.sh` | Inbound: Pattern 04: Dual payload type + deduplication |
| `test-inbound-smartfunction-05.sh` | Inbound: Pattern 11: Per-device running statistics (device ID from context) |

### 3.1b Java Processor Extensions (inbound + outbound)

| Script | Scenario |
|--------|----------|
| `test-inbound-extension-custom-measurement.sh` | Extension: JSON → Measurement |
| `test-inbound-extension-custom-alarm.sh` | Extension: JSON → Alarm |
| `test-inbound-extension-custom-event.sh` | Extension: Protobuf → Event |
| `test-inbound-extension-sparkplugb-measurement.sh` | Extension: Sparkplug B → Measurement |
| `test-outbound-extension-alarm-to-sparkplugb.sh` | Extension: Alarm → Sparkplug B DCMD |

### 3.2 Outbound Tests

Create a C8Y object or manage a subscription and verify the broker or mapper state is updated.

| Script | Scenario |
|--------|----------|
| `test-outbound-measurement.sh` | C8Y Measurement → MQTT broker |
| `test-outbound-event.sh` | C8Y Event → MQTT broker |
| `test-outbound-alarm.sh` | C8Y Alarm → MQTT broker |
| `test-outbound-operation.sh` | C8Y Operation → MQTT broker |
| `test-outbound-inventory.sh` | C8Y managed-object change → MQTT broker (metadata) |
| `test-outbound-filter.sh` | filterMapping — selective forwarding |
| `test-outbound-topic-resolution.sh` | Dynamic publish topic resolution |
| `test-outbound-json-smartfunction.sh` | Smart Function: Measurement → MQTT JSON |
| `test-outbound-smartfunction-externalsource.sh` | Smart Function externalSource → _externalId_ topic (broker round-trip) |
| `test-outbound-smartfunction-molookup.sh` | Smart Function outbound: Pattern 03 — getManagedObjectByExternalId — MO enrichment |
| `test-outbound-static-subscription.sh` | Static subscription management |
| `test-outbound-type-subscription.sh` | Dynamic type subscription |
| `test-outbound-group-subscription.sh` | Dynamic group subscription |
| `test-outbound-group-subscription-removal.sh` | Group subscription removal |
| `test-outbound-subscription-persistence.sh` | Subscription persistence after restart |

### 3.3 Reliability Tests

| Script | Scenario |
|--------|----------|
| `test-multi-tenant.sh` | Mapping CRUD / tenant isolation |
| `test-multi-connector.sh` | Multiple connector status check |
| `test-reconnect.sh` | Connector disconnect / reconnect cycle |
| `test-cumulocity-mqtt-service.sh` | Cumulocity MQTT Service connector lifecycle |

### 3.4 Test Runner

`run-tests.sh` is a menu-driven suite runner for all integration tests. It takes two kinds of
token, in any order: **which tests** to run and **which broker** to run them against.

```
./run-tests.sh [SUITE ...] [CONNECTOR]
```

**SUITE** — which tests:

| Token | Runs |
|-------|------|
| `a` \| `all` | Every test |
| `i` \| `inbound` | All inbound tests (§3.1) |
| `o` \| `outbound` | All outbound tests (§3.2) |
| `e` \| `extension` | Java processor-extension tests (§3.1b) |
| `s` \| `smartfunc` | Smart Function pattern tests (§3.1a) |
| `r` \| `reliability` | Reliability tests (§3.3) |
| `<script-name>` | One script, with or without `.sh` |
| `<n> [n2 …]` | One or more menu indices |
| `<n>-<m>` | A range of menu indices, e.g. `3-7` |
| _(omitted)_ | Interactive menu |

**CONNECTOR** — which broker the MQTT tests drive (default `g`):

| Token | Broker |
|-------|--------|
| `g` | Generic MQTT — a public broker (`broker.hivemq.com`) |
| `m` | Cumulocity MQTT Service — `CUMULOCITY_MQTT_SERVICE_PULSAR`, TLS `:9883`, X.509 client-certificate auth. Sets `DM_BROKER_MODE=c8y-mqtt-service` automatically |

This is what lets the **same suite run against both brokers with no per-file edits** — the single
most useful property of the runner, and the one worth using before a release.

```bash
./run-tests.sh                      # interactive: prompts for suite and connector
./run-tests.sh all                  # everything, public broker (default)
./run-tests.sh inbound m            # all inbound tests against the MQTT Service
./run-tests.sh 1 3 5 g              # menu items 1, 3, 5 against the public broker
./run-tests.sh 3-7                  # a range of menu items
./run-tests.sh test-inbound-flatfile  # a single script
```

| Feature | Detail |
|---------|--------|
| Per-test cleanup | Passes `--cleanup` to every script automatically |
| Suite summary | Pass / fail / skip counts + list of failed test names |
| Fail-fast mode | `DM_STOP_ON_FAIL=1` aborts the suite on first failure |
| ANSI colours | Auto-disabled when stdout is piped or redirected |

**Environment variables** (set before running; `run-tests.sh --help` prints the full list):

| Variable | Default | Purpose |
|----------|---------|---------|
| `DM_SERVICE` | `/service/dynamic-mapper-service` | Dynamic mapper base path |
| `DM_BROKER_MODE` | `public` | `public` or `c8y-mqtt-service`. Set automatically by the `m` token |
| `MQTT_HOST` | `broker.hivemq.com` (MQTT Service mode: tenant domain) | MQTT broker host |
| `MQTT_PORT` | `1883` | MQTT broker port |
| `MQTT_USER`, `MQTT_PASS` | — | Optional MQTT credentials |
| `MQTT_TLS`, `MQTT_CAFILE`, `MQTT_INSECURE` | `false` | TLS for publish/subscribe |
| `DM_C8Y_MQTT_HOST`, `DM_C8Y_MQTT_PORT`, `DM_C8Y_MQTT_CONNECTOR_ID` | — | MQTT Service overrides |
| `DM_CONNECTOR_ID` | auto-detected | Connector id for `test-reconnect.sh` |
| `DM_DEFAULT_DISCOVERY_WAIT` | `10` | Wait for dynamic-discovery checks |
| `DM_STOP_ON_FAIL` | `0` | Set to `1` to abort on first failure |

### 3.4a Standalone diagnostics (not part of the suite)

| Script | Purpose |
|--------|---------|
| `test-c8y-mqtt-service-spike.sh` | Verbose first-time probe for a Cumulocity MQTT Service setup on a new tenant — creates its own throwaway connector, tears it down (`--keep` to inspect). Not run by `run-tests.sh`; the same path is covered by `./run-tests.sh test-inbound-json-default m` |
| `test-harness.sh` | Shared helper library sourced by every test script (assertions, cleanup, MQTT helpers) |
| `TEST_TEMPLATE.sh` | Starting point for a new test script |
| `create-mqtt-service-x509-cert.sh` | Generates the X.509 client certificate the `m` connector lane needs |

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

> Historical record. During a comprehensive test run in May 2026 several issues were identified
> and corrected in the shell integration tests. Kept because the failure modes recur when new
> scripts are written; it is not a description of current gaps.

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
| Manual test cases (historical) | `attic/test-scripts/test-II/TestCases_01.xlsx` |
| Reliability behaviour (QoS, timeouts, failure handling, status) | `docs/feature/reliability.md` |
| Mapping validation rules | `docs/feature/mapping-validation.md` |
| Smart Function templates | `dynamic-mapper-service/src/main/resources/templates/` |
| Sample mappings | `resources/samples/` |
| Cypress tests | `dynamic-mapper-ui/cypress/` |
| Angular unit tests | `dynamic-mapper-ui/src/**/*.spec.ts` |
| Shell test scripts | `resources/script/test/` |
| Java test sources | `dynamic-mapper-service/src/test/java/` |
| Smart Function tests | `dynamic-mapper-smart-function/src/` |

---

_Inventories in sections 1–4 were regenerated from the source tree on 2026-09-13. When adding a
test, add it to the matching table; when adding a shell script, add it to `run-tests.sh`'s `TESTS`
registry — section 3 is generated from that registry._
