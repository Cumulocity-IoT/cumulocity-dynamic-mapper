/*
 * Copyright (c) 2025 Cumulocity GmbH.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *  @authors Christof Strack, Stefan Witschel
 *
 */

package dynamic.mapper.processor.inbound;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.junit.jupiter.MockitoExtension;

import org.mockito.ArgumentCaptor;

import dynamic.mapper.core.InventoryEnrichmentClient;
import dynamic.mapper.processor.model.ExternalId;
import dynamic.mapper.processor.model.InputMessage;
import dynamic.mapper.processor.model.SmartFunctionContext;
import dynamic.mapper.processor.util.JavaScriptModuleStripper;
import lombok.extern.slf4j.Slf4j;

/**
 * Tests for inbound Smart Function JavaScript templates.
 *
 * Covers PDF TestCases_6.2.0 functional tests:
 *   #9  / #10 – template-SMART-INBOUND-01.js  (basic measurement)
 *   #15        – template-SMART-INBOUND-02.js  (device-enrichment conditional)
 *   #17        – template-SMART-INBOUND-03.js  (implicit device create)
 *   #16        – template-SMART-INBOUND-04.js  (flow state: telemetry + error dedup)
 *
 * Each test loads the template from src/main/resources/templates/, strips the
 * ES module export syntax with {@link JavaScriptModuleStripper}, executes it
 * through a real GraalVM context, and asserts the returned result structure.
 */
@Slf4j
@ExtendWith(MockitoExtension.class)
class SmartFunctionInboundTest {

    private static final String TEMPLATES_RESOURCE_PATH = "templates/";

    private Context graalContext;
    private SmartFunctionContext smartFunctionContext;

    // Returns null for all inventory lookups; SmartFunctionContext handles null gracefully
    @org.mockito.Mock
    private InventoryEnrichmentClient inventoryClient;

    @BeforeEach
    void setUp() {
        graalContext = Context.newBuilder("js")
                .allowAllAccess(true)
                .option("engine.WarnInterpreterOnly", "false")
                .build();

        smartFunctionContext = new SmartFunctionContext(graalContext, "testTenant", inventoryClient, true);
    }

    @AfterEach
    void tearDown() {
        if (graalContext != null) {
            try {
                graalContext.close();
            } catch (Exception e) {
                log.warn("Error closing GraalVM context: {}", e.getMessage());
            }
        }
    }

    // =========================================================================
    // Parameterized tests driven by PDF test case data
    // =========================================================================

    /**
     * PDF test #9 / #10 – template-SMART-INBOUND-01:
     * Basic measurement from sensorData.temp_val.
     * Expected: exactly one result, cumulocityType="measurement", action="create",
     * externalSource externalId matches clientId.
     */
    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("template01TestCases")
    void testTemplate01_BasicMeasurement(
            String displayName,
            Map<String, Object> payload,
            String topic,
            String expectedExternalId,
            double expectedTempVal) throws Exception {

        String code = loadTemplate("template-SMART-INBOUND-01.js");
        Value onMessage = evalTemplate(code);

        Map<String, Object> config = Map.of("clientId", expectedExternalId, "topic", topic);
        smartFunctionContext.setConfig(config);

        InputMessage msg = new InputMessage(payload, topic, expectedExternalId, null);
        Value result = onMessage.execute(graalContext.asValue(msg), graalContext.asValue(smartFunctionContext));

        Value first01 = assertSingleResult(result, "measurement", "create");

        Value resultPayload = first01.getMember("payload");
        double actualTemp = resultPayload.getMember("c8y_Steam")
                .getMember("Temperature").getMember("value").asDouble();
        assertEquals(expectedTempVal, actualTemp, 0.001,
                "Temperature value should match sensorData.temp_val");

        Value externalSource = first01.getMember("externalSource");
        assertEquals(expectedExternalId, externalSource.getArrayElement(0).getMember("externalId").asString(),
                "externalId should match clientId");

        log.info("✅ [{}] template-SMART-INBOUND-01: measurement created, temp={}, externalId={}",
                displayName, actualTemp, expectedExternalId);
    }

    static Stream<Arguments> template01TestCases() {
        return Stream.of(
                // PDF test #9 / #10 – "flow" topic payload
                Arguments.of(
                        "PDF-#9 flow/sensor-berlin-01",
                        sensorPayload("C333646782-17108550186195", "C3336467812", 100.0),
                        "flow/sensor-berlin-01",
                        "C3336467812",
                        100.0),
                // PDF test #10 – loadTestGraals topic payload
                Arguments.of(
                        "PDF-#10 testSmartInbound/sensor-berlin-01",
                        sensorPayload("msg-001", "sensor-berlin-01", 23.5),
                        "testSmartInbound/sensor-berlin-01",
                        "sensor-berlin-01",
                        23.5));
    }

    /**
     * PDF test #15 – template-SMART-INBOUND-02:
     * Device-enrichment conditional: no inventory → should return [] (no valid type found).
     */
    @Test
    void testTemplate02_DeviceEnrichment_NoInventory_ReturnsEmpty() throws Exception {
        String code = loadTemplate("template-SMART-INBOUND-02.js");
        Value onMessage = evalTemplate(code);

        Map<String, Object> payload = Map.of(
                "messageId", "msg-001",
                "deviceId", "31621607",
                "sensorData", Map.of("val", 230.5));
        String topic = "testSmartInbound/sensor-berlin-01";

        Map<String, Object> config = Map.of("topic", topic);
        smartFunctionContext.setConfig(config);

        InputMessage msg = new InputMessage(payload, topic, "sensor-berlin-01", null);
        Value result = onMessage.execute(graalContext.asValue(msg), graalContext.asValue(smartFunctionContext));

        assertTrue(result.hasArrayElements(), "Result must be an array");
        // Without real inventory, no device type is found → template returns []
        assertEquals(0, result.getArraySize(),
                "Should return empty array when no inventory data available (null device)");

        log.info("✅ template-SMART-INBOUND-02: correctly returns [] when inventory unavailable");
    }

    /**
     * PDF test #15 – template-SMART-INBOUND-02 with simulated voltage device:
     * When the device has c8y_Sensor.type.voltage=true, a c8y_VoltageMeasurement is produced.
     * We simulate inventory by injecting it into the JS context before calling onMessage.
     */
    @Test
    void testTemplate02_DeviceEnrichment_VoltageDevice() throws Exception {
        String code = loadTemplate("template-SMART-INBOUND-02.js");

        // Inject a mock getManagedObject that returns a voltage device
        String setupCode = """
                var __mockDevice = { c8y_Sensor: { type: { voltage: true } } };
                """;
        graalContext.eval("js", setupCode);

        // Patch the template: replace context.getManagedObject call stub result
        String patchedCode = code.replace(
                "var deviceByDeviceId = context.getManagedObject(payload[\"deviceId\"]);",
                "var deviceByDeviceId = __mockDevice;");

        Value onMessage = evalTemplate(patchedCode);

        Map<String, Object> payload = Map.of(
                "messageId", "msg-001",
                "deviceId", "31621607",
                "sensorData", Map.of("val", 230.5));
        String topic = "testSmartInbound/sensor-berlin-01";

        Map<String, Object> config = Map.of("topic", topic);
        smartFunctionContext.setConfig(config);

        InputMessage msg = new InputMessage(payload, topic, "sensor-berlin-01", null);
        Value result = onMessage.execute(graalContext.asValue(msg), graalContext.asValue(smartFunctionContext));

        Value firstV = assertSingleResult(result, "measurement", "create");

        Value resultPayload = firstV.getMember("payload");
        assertEquals("c8y_VoltageMeasurement", resultPayload.getMember("type").asString(),
                "Should create c8y_VoltageMeasurement for voltage device");
        double voltage = resultPayload.getMember("c8y_Voltage").getMember("voltage").getMember("value").asDouble();
        assertEquals(230.5, voltage, 0.001, "Voltage value should be 230.5");

        log.info("✅ template-SMART-INBOUND-02 (voltage): c8y_VoltageMeasurement created, V={}", voltage);
    }

    /**
     * PDF test #17 – template-SMART-INBOUND-03:
     * Implicit device creation with contextData (deviceName, deviceType, groups, fragments).
     */
    @Test
    void testTemplate03_ImplicitDeviceCreate() throws Exception {
        String code = loadTemplate("template-SMART-INBOUND-03.js");
        Value onMessage = evalTemplate(code);

        Map<String, Object> payload = Map.of(
                "messageId", "C333646782-17108550186195",
                "clientId", "sensor-berlin-01",
                "sensorData", Map.of("temp_val", 23.5));
        String topic = "testSmartInbound/sensor-berlin-01";

        Map<String, Object> config = Map.of("clientId", "sensor-berlin-01", "topic", topic);
        smartFunctionContext.setConfig(config);

        InputMessage msg = new InputMessage(payload, topic, "sensor-berlin-01", null);
        Value result = onMessage.execute(graalContext.asValue(msg), graalContext.asValue(smartFunctionContext));

        Value first = assertSingleResult(result, "measurement", "create");

        // Verify contextData for implicit device creation
        assertTrue(first.hasMember("contextData"), "Should have contextData for implicit device create");
        Value contextData = first.getMember("contextData");
        assertEquals("Test-Sensor", contextData.getMember("deviceName").asString(),
                "deviceName should be 'Test-Sensor'");
        assertEquals("sensor-type", contextData.getMember("deviceType").asString(),
                "deviceType should be 'sensor-type'");

        // Verify deviceGroups list
        Value groups = contextData.getMember("deviceGroups");
        assertTrue(groups.hasArrayElements(), "deviceGroups should be an array");
        assertEquals(2, groups.getArraySize(), "Should have 2 device groups");

        // Verify measurement payload temperature
        Value resultPayload = first.getMember("payload");
        double actualTemp = resultPayload.getMember("c8y_Steam")
                .getMember("Temperature").getMember("value").asDouble();
        assertEquals(23.5, actualTemp, 0.001, "Temperature value should be 23.5");

        log.info("✅ template-SMART-INBOUND-03: implicit device create with contextData, temp={}", actualTemp);
    }

    /**
     * PDF test #16 – template-SMART-INBOUND-04 (flow state):
     * Telemetry path → produces measurement; error path → produces event; duplicate error → [].
     */
    @Test
    void testTemplate04_FlowState_TelemetryMessage() throws Exception {
        String code = loadTemplate("template-SMART-INBOUND-04.js");
        Value onMessage = evalTemplate(code);

        Map<String, Object> payload = new HashMap<>();
        payload.put("messageId", "msg-001");
        payload.put("externalId", "sensor-berlin-01");
        payload.put("payloadType", "telemetry");
        payload.put("sensorData", Map.of("temp_val", 23.5));

        String topic = "flowState/sensor-berlin-01";
        Map<String, Object> config = Map.of("clientId", "sensor-berlin-01", "topic", topic);
        smartFunctionContext.setConfig(config);

        InputMessage msg = new InputMessage(payload, topic, "sensor-berlin-01", null);
        Value result = onMessage.execute(graalContext.asValue(msg), graalContext.asValue(smartFunctionContext));

        Value firstM = assertSingleResult(result, "measurement", "create");

        Value resultPayload = firstM.getMember("payload");
        assertEquals("c8y_TemperatureMeasurement", resultPayload.getMember("type").asString(),
                "Should produce c8y_TemperatureMeasurement");

        log.info("✅ template-SMART-INBOUND-04: telemetry → measurement created");
    }

    @Test
    void testTemplate04_FlowState_ErrorMessage() throws Exception {
        String code = loadTemplate("template-SMART-INBOUND-04.js");
        Value onMessage = evalTemplate(code);

        Map<String, Object> payload = new HashMap<>();
        payload.put("messageId", "msg-002");
        payload.put("externalId", "sensor-berlin-01");
        payload.put("payloadType", "error");
        payload.put("logMessage", "Sensor malfunction detected");

        String topic = "flowState/sensor-berlin-01";
        Map<String, Object> config = Map.of("clientId", "sensor-berlin-01", "topic", topic);
        smartFunctionContext.setConfig(config);

        InputMessage msg = new InputMessage(payload, topic, "sensor-berlin-01", null);
        Value result = onMessage.execute(graalContext.asValue(msg), graalContext.asValue(smartFunctionContext));

        Value firstE = assertSingleResult(result, "event", "create");

        Value resultPayload = firstE.getMember("payload");
        assertEquals("c8y_ErrorEvent", resultPayload.getMember("type").asString(),
                "Should produce c8y_ErrorEvent");
        assertEquals("Sensor malfunction detected", resultPayload.getMember("text").asString(),
                "Event text should match logMessage");

        log.info("✅ template-SMART-INBOUND-04: error → event created");
    }

    @Test
    void testTemplate04_FlowState_DuplicateErrorSuppressed() throws Exception {
        String code = loadTemplate("template-SMART-INBOUND-04.js");
        Value onMessage = evalTemplate(code);

        String topic = "flowState/sensor-berlin-01";
        Map<String, Object> config = Map.of("clientId", "sensor-berlin-01", "topic", topic);
        smartFunctionContext.setConfig(config);

        Map<String, Object> errorPayload = new HashMap<>();
        errorPayload.put("messageId", "msg-002");
        errorPayload.put("externalId", "sensor-berlin-01");
        errorPayload.put("payloadType", "error");
        errorPayload.put("logMessage", "Sensor malfunction detected");

        InputMessage msg1 = new InputMessage(errorPayload, topic, "sensor-berlin-01", null);
        Value first = onMessage.execute(graalContext.asValue(msg1), graalContext.asValue(smartFunctionContext));
        // First error → should produce an event
        assertSingleResult(first, "event", "create"); // return value not needed here

        // Same error again → should be suppressed (dedup)
        InputMessage msg2 = new InputMessage(new HashMap<>(errorPayload), topic, "sensor-berlin-01", null);
        Value second = onMessage.execute(graalContext.asValue(msg2), graalContext.asValue(smartFunctionContext));
        assertTrue(second.hasArrayElements(), "Result must be an array");
        assertEquals(0, second.getArraySize(),
                "Duplicate error message should be suppressed by flow state dedup");

        log.info("✅ template-SMART-INBOUND-04: duplicate error suppressed by flow state");
    }

    // =========================================================================
    // Context methods for accessing incoming metadata
    // (documented under "Metadata Properties for Inbound Smart Functions")
    // =========================================================================

    /**
     * Documents {@code context.getManagedObjectByExternalId({externalId, type})}:
     * the Smart Function looks a device up by its external id, and the resolved
     * managed object's properties become available inside the function. The
     * lookup is delegated to {@link InventoryEnrichmentClient}; here we stub the
     * cache and assert both the resolved values reaching the JS code and the
     * external id/type passed to the client.
     */
    @Test
    void testContext_getManagedObjectByExternalId_ResolvesDeviceFromCache() {
        Map<String, Object> device = Map.of(
                "id", "98765",
                "name", "Berlin Sensor",
                "type", "c8y_Sensor",
                "c8y_Hardware", Map.of("serialNumber", "SN-001"));
        when(inventoryClient.getMOFromInventoryCacheByExternalId(eq("testTenant"), any(ExternalId.class), eq(true)))
                .thenReturn(device);

        String code = """
                function onMessage(msg, context) {
                  var dev = context.getManagedObjectByExternalId({ externalId: "sensor-berlin-01", type: "c8y_Serial" });
                  return [{
                    cumulocityType: "measurement",
                    action: "create",
                    payload: { resolvedName: dev.name, serial: dev.c8y_Hardware.serialNumber },
                    externalSource: [{ type: "c8y_Serial", externalId: "sensor-berlin-01" }]
                  }];
                }
                """;
        Value onMessage = evalTemplate(code);

        InputMessage msg = new InputMessage(new HashMap<>(), "testSmartInbound/sensor-berlin-01", "sensor-berlin-01",
                null);
        Value result = onMessage.execute(graalContext.asValue(msg), graalContext.asValue(smartFunctionContext));

        Value first = assertSingleResult(result, "measurement", "create");
        Value payload = first.getMember("payload");
        assertEquals("Berlin Sensor", payload.getMember("resolvedName").asString(),
                "device name resolved via getManagedObjectByExternalId should reach the Smart Function");
        assertEquals("SN-001", payload.getMember("serial").asString(),
                "nested fragment of the resolved device should be accessible");

        // The lookup must carry the externalId and type passed from JavaScript.
        ArgumentCaptor<ExternalId> captor = ArgumentCaptor.forClass(ExternalId.class);
        verify(inventoryClient).getMOFromInventoryCacheByExternalId(eq("testTenant"), captor.capture(), eq(true));
        assertEquals("sensor-berlin-01", captor.getValue().getExternalId(), "externalId passed to the cache lookup");
        assertEquals("c8y_Serial", captor.getValue().getType(), "external id type passed to the cache lookup");

        log.info("✅ context.getManagedObjectByExternalId: resolved device reached the Smart Function");
    }

    /**
     * Documents {@code context.getTesting()}: returns the flag indicating whether
     * the function runs inside a test cycle. Smart Functions use this to branch
     * (e.g. skip side effects when testing). The value mirrors the flag the
     * context was constructed with — {@code true} for the shared test context and
     * {@code false} for a production-style context.
     */
    @Test
    void testContext_getTesting_ReflectsTestingFlag() {
        String code = """
                function onMessage(msg, context) {
                  return [{
                    cumulocityType: "event",
                    action: "create",
                    payload: { testing: context.getTesting() },
                    externalSource: [{ type: "c8y_Serial", externalId: "d1" }]
                  }];
                }
                """;
        Value onMessage = evalTemplate(code);
        InputMessage msg = new InputMessage(new HashMap<>(), "topic/d1", "d1", null);

        // Shared context constructed with testing=true.
        Value testingResult = onMessage.execute(graalContext.asValue(msg), graalContext.asValue(smartFunctionContext));
        Value testingFirst = assertSingleResult(testingResult, "event", "create");
        assertTrue(testingFirst.getMember("payload").getMember("testing").asBoolean(),
                "context.getTesting() should be true for the test context");

        // A non-testing (production-style) context reports false.
        SmartFunctionContext prodContext = new SmartFunctionContext(graalContext, "testTenant", inventoryClient, false);
        Value prodResult = onMessage.execute(graalContext.asValue(msg), graalContext.asValue(prodContext));
        assertFalse(prodResult.getArrayElement(0).getMember("payload").getMember("testing").asBoolean(),
                "context.getTesting() should be false for a non-testing context");

        log.info("✅ context.getTesting: reflects the testing flag (true/false)");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private String loadTemplate(String templateFileName) throws IOException {
        // Try classpath first (works after maven compile copies resources)
        URL resource = getClass().getClassLoader().getResource(TEMPLATES_RESOURCE_PATH + templateFileName);
        if (resource != null) {
            return new String(resource.openStream().readAllBytes(), StandardCharsets.UTF_8);
        }
        // Fall back to source tree (works without prior compile)
        Path sourcePath = findProjectRoot()
                .resolve("dynamic-mapper-service/src/main/resources/templates/" + templateFileName);
        return Files.readString(sourcePath, StandardCharsets.UTF_8);
    }

    /**
     * Strips ES module syntax and evaluates the template script, binding {@code onMessage}
     * into the GraalVM context's globalThis so it can be retrieved via bindings.
     */
    private Value evalTemplate(String rawCode) {
        String plainCode = JavaScriptModuleStripper.toPlainScript(rawCode);
        // Expose onMessage on globalThis so getBindings("js").getMember() can find it
        String wrappedCode = plainCode + "\nglobalThis['onMessage'] = onMessage;\n";
        graalContext.eval("js", wrappedCode);
        Value fn = graalContext.getBindings("js").getMember("onMessage");
        assertNotNull(fn, "onMessage function must be defined in template");
        assertTrue(fn.canExecute(), "onMessage must be executable");
        return fn;
    }

    /**
     * Normalizes the onMessage return value to a single-element array-like Value.
     * Some templates (e.g. template-04) return a plain object instead of an array.
     */
    private Value assertSingleResult(Value result, String expectedCumulocityType, String expectedAction) {
        assertNotNull(result, "onMessage must return a non-null result");

        Value first;
        if (result.hasArrayElements()) {
            assertEquals(1, result.getArraySize(),
                    "Expected exactly one result entry, got " + result.getArraySize());
            first = result.getArrayElement(0);
        } else {
            // Template returned a plain object (not wrapped in an array)
            assertTrue(result.hasMembers(), "Result must be either an array or an object");
            first = result;
        }

        assertEquals(expectedCumulocityType, first.getMember("cumulocityType").asString(),
                "cumulocityType mismatch");
        assertEquals(expectedAction, first.getMember("action").asString(),
                "action mismatch");
        return first;
    }

    private static Map<String, Object> sensorPayload(String messageId, String clientId, double tempVal) {
        Map<String, Object> sensorData = new HashMap<>();
        sensorData.put("temp_val", tempVal);
        Map<String, Object> payload = new HashMap<>();
        payload.put("messageId", messageId);
        payload.put("clientId", clientId);
        payload.put("sensorData", sensorData);
        return payload;
    }

    private Path findProjectRoot() {
        // Navigate up from target/test-classes to the project root
        Path classPath = Paths.get(getClass().getProtectionDomain().getCodeSource().getLocation().getPath());
        return classPath.getParent().getParent().getParent();
    }
}
