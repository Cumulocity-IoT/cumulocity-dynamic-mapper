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
| FLAT_FILE | Outbound | ⬜ | ⬜ | ⬜ | ⬜ |
| HEX | Inbound | ✅ | ✅ | ⬜ | ⬜ |
| HEX | Outbound | ⬜ | ⬜ | ⬜ | ⬜ |
| PROTOBUF_INTERNAL | Inbound | ✅ | n/a | n/a | ✅ |
| PROTOBUF_INTERNAL | Outbound | ⬜ | ⬜ | ⬜ | ⬜ |
| SPARKPLUGB | Inbound | ⬜ | ⬜ | ⬜ | ✅ |
| SPARKPLUGB | Outbound | ⬜ | ⬜ | ✅ | ⬜ |
| ANY_PAYLOAD | Inbound | n/a | n/a | ⬜ | ⬜ |
| ANY_PAYLOAD | Outbound | n/a | n/a | ⬜ | ⬜ |

**Legend:** ✅ covered · ⬜ gap · n/a not applicable

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
| `SnoopingInboundProcessorTest` | Snoop enable/start/stop lifecycle, payload capture |
| `FlowInboundProcessorTest` | Camel route dispatch, mapping lookup by topic |
| `FlowResultInboundProcessorTest` | Result collection, error accumulation |
| `JSONataInboundProcessorTest` | JSONata expression evaluation on source payload |
| `SubstitutionResultInboundProcessorTest` | Substitution application to target template |
| `DeserializationOutboundProcessorTest` | C8Y notification deserialization |
| `EnrichmentOutboundProcessorTest` | External ID resolution, `_IDENTITY_` outbound |
| `SnoopingOutboundProcessorTest` | Outbound snoop capture |
| `FlowOutboundProcessorTest` | Outbound Camel route dispatch |
| `FlowResultOutboundProcessorTest` | Outbound result collection |
| `JSONataOutboundProcessorTest` | JSONata on C8Y payload, `filterMapping` evaluation |

**Missing:** Processor tests for `HEX`, `FLAT_FILE` outbound; `ANY_PAYLOAD` outbound.

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

- **HEX outbound** — no tests
- **FLAT_FILE outbound** — no tests
- **ANY_PAYLOAD** inbound and outbound — no tests
- **SPARKPLUGB inbound** — no unit/integration tests (only a test client exists)
- **GraalVM sandbox security** — no adversarial tests (script injection, CPU/memory exhaustion)
- **Multi-tenancy isolation** — no tests verifying tenant A cannot access tenant B data
- **Connector retry / reconnect** — no tests for exponential backoff behavior
- **Kafka full integration** — `KafkaTestClient.java` exists but no `@Test` methods

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
| Snoop enable → payload captured | Status column shows snooped count |

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
| Snoop Explorer | Snooped payloads browsable |
| Test Device tab | Generated test devices listed, deletable |

#### Configuration

| Test | Description |
|------|-------------|
| Service configuration | Save/load settings |
| Processor Extension upload | JAR upload dialog, extension appears in list |
| Extension selection in mapping | Extension name shown in Transformation dropdown |

### 2.3 Recommended Approach

- Use `cy.intercept()` to stub all API calls — tests run without a live Cumulocity tenant
- Use fixtures in `cypress/fixtures/` for sample mappings and connector responses
- Group tests by feature area: `connector/`, `mapping/inbound/`, `mapping/outbound/`, `monitoring/`
- Add visual regression snapshots (e.g. Cypress Percy or `cy.screenshot()`) for critical views

---

## 3. System / Shell Integration Tests

**Location:** `resources/script/test/`
**Prerequisites:** `c8y` CLI configured and authenticated; dynamic mapper microservice deployed

### 3.1 Existing Tests

| Script | Description |
|--------|-------------|
| `test-case-I.sh` | Static subscription: create device → subscribe → send measurement → verify Notification 2.0 subscription exists |
| `test-case-II.sh` | Dynamic subscription by device type: register type → create device of that type → wait for discovery → verify subscription |
| `test-case-III.sh` | Dynamic subscription by device group: create group → register group subscription → add device → wait → verify subscription |
| `test-case-IV.sh` | Remove device from group → verify subscription deleted (depends on test-case-III state) |
| `test-case-V.sh` | Subscriptions survive microservice restart: static + dynamic subscriptions persist after `disable`/`enable` cycle |

All five test cases focus exclusively on **outbound subscription management**. They do not test payload transformation.

### 3.2 Required Tests (gaps)

#### Inbound End-to-End

| Script (proposed) | Scenario |
|-------------------|----------|
| `test-inbound-json-default.sh` | Publish JSON to MQTT → verify measurement created in C8Y |
| `test-inbound-json-jsonata.sh` | Publish JSON → verify JSONata expression evaluated correctly |
| `test-inbound-json-smartfunction.sh` | Publish JSON → Smart Function executed → measurement in C8Y |
| `test-inbound-flatfile.sh` | Publish CSV line → verify C8Y event created |
| `test-inbound-hex.sh` | Publish hex string → verify parsed measurement |
| `test-inbound-http-connector.sh` | POST to HTTP connector endpoint → verify C8Y object |
| `test-inbound-implicit-device.sh` | First-time device → verify managed object auto-created |
| `test-inbound-multi-device.sh` | Array payload → multiple C8Y requests generated |

#### Outbound End-to-End

| Script (proposed) | Scenario |
|-------------------|----------|
| `test-outbound-measurement.sh` | Create measurement in C8Y → verify MQTT message published to broker |
| `test-outbound-event.sh` | Create event in C8Y → verify broker receives event payload |
| `test-outbound-alarm.sh` | Create alarm → verify broker message |
| `test-outbound-operation.sh` | Create operation (CREATE only) → verify forwarded to device |
| `test-outbound-filter.sh` | `filterMapping` expression blocks non-matching messages |
| `test-outbound-topic-resolution.sh` | Dynamic publish topic resolved from device external ID |

#### Reliability

| Script (proposed) | Scenario |
|-------------------|----------|
| `test-multi-tenant.sh` | Two tenants subscribed; verify mappings are isolated |
| `test-multi-connector.sh` | Two connectors active simultaneously; messages routed correctly |
| `test-reconnect.sh` | Broker disconnected → reconnected → messages resume |

### 3.3 Approach

- Each script should follow the existing pattern: `set -e`, cleanup trap with `--cleanup` flag, `c8y` CLI for all API calls
- Inbound tests: use a local test MQTT broker (e.g. Mosquitto via Docker) or the C8Y MQTT Service
- Outbound tests: verify broker receipt using `mosquitto_sub` or a c8y notification2 subscriber
- Use `jq` for all JSON assertions; always use `jq '.field? // fallback'` to handle non-object responses gracefully

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

## 5. References

| Resource | Path |
|----------|------|
| Manual test cases (6.2.0) | `attic/test-plan/TestCases_6.2.0 (1).pdf` |
| Smart Function templates | `dynamic-mapper-service/src/main/resources/templates/` |
| Sample mappings | `resources/samples/` |
| Cypress tests | `dynamic-mapper-ui/cypress/` |
| Shell test scripts | `resources/script/test/` |
| Java test sources | `dynamic-mapper-service/src/test/java/` |
| Smart Function tests | `dynamic-mapper-smart-function/src/` |