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

package dynamic.mapper.processor.outbound;

import static org.junit.jupiter.api.Assertions.*;

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

import dynamic.mapper.core.InventoryEnrichmentClient;
import dynamic.mapper.processor.model.InputMessage;
import dynamic.mapper.processor.model.SmartFunctionContext;
import dynamic.mapper.processor.util.JavaScriptModuleStripper;
import lombok.extern.slf4j.Slf4j;

/**
 * Tests for outbound Smart Function JavaScript templates.
 *
 * Covers PDF TestCases_6.2.0 functional tests:
 *   #51 / #52  – template-SMART-OUTBOUND-01.js  (single result, plain object)
 *   #53        – template-SMART-OUTBOUND-02.js  (array result, payload array)
 *   #54        – template-SMART-OUTBOUND-03.js  (array result with transportFields/Kafka key)
 *   #55        – template-SMART-OUTBOUND-05.js  (custom routing to microservice)
 *
 * Each test:
 *  1. Loads the template from src/main/resources/templates/
 *  2. Strips ES module export syntax via {@link JavaScriptModuleStripper}
 *  3. Executes through a real GraalVM context using {@link InputMessage} + {@link SmartFunctionContext}
 *  4. Asserts the returned result structure matches expectations
 *
 * For outbound, {@code msg.getPayload()} is a Cumulocity API object (measurement / event / alarm /
 * operation); the template returns topic + payload or cumulocityType + targetPath.
 */
@Slf4j
@ExtendWith(MockitoExtension.class)
class SmartFunctionOutboundTest {

    private static final String TEMPLATES_RESOURCE_PATH = "templates/";
    private static final String EXTERNAL_ID = "berlin_01";

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
        // Provide the resolved externalId, as the outbound processor would
        smartFunctionContext.setConfig(Map.of("externalId", EXTERNAL_ID, "topic", "measurements/" + EXTERNAL_ID));
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
    // Parameterized tests – template-SMART-OUTBOUND-01 (PDF #51 / #52)
    // Single plain-object return: { topic, payload }
    // =========================================================================

    /**
     * PDF test #51 / #52 – template-SMART-OUTBOUND-01:
     * Transforms a Cumulocity measurement into a device-facing MQTT payload.
     * Returns a plain object (not wrapped in an array).
     * Expected: topic = "measurements/berlin_01", c8y_Steam.Temperature.value matches input.
     */
    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("template01TestCases")
    void testTemplate01_SingleMeasurementResult(
            String displayName,
            Map<String, Object> c8yPayload,
            double expectedTempValue) throws Exception {

        String code = loadTemplate("template-SMART-OUTBOUND-01.js");
        Value onMessage = evalTemplate(code);

        InputMessage msg = new InputMessage(c8yPayload, "measurements/12345", null, "12345", "measurement");
        Value result = onMessage.execute(graalContext.asValue(msg), graalContext.asValue(smartFunctionContext));

        assertNotNull(result, "onMessage must return a non-null result");
        // template-01 returns a plain object, not an array
        assertTrue(result.hasMembers(), "Result should be an object with topic/payload");
        assertTrue(result.hasMember("topic"), "Result should have topic");
        assertTrue(result.hasMember("payload"), "Result should have payload");

        String topic = result.getMember("topic").asString();
        assertEquals("measurements/" + EXTERNAL_ID, topic, "Topic should use resolved externalId");

        Value payload = result.getMember("payload");
        double actualTemp = payload.getMember("c8y_Steam")
                .getMember("Temperature").getMember("value").asDouble();
        assertEquals(expectedTempValue, actualTemp, 0.001, "Temperature value mismatch");

        log.info("✅ [{}] template-SMART-OUTBOUND-01: topic={}, temp={}", displayName, topic, actualTemp);
    }

    static Stream<Arguments> template01TestCases() {
        return Stream.of(
                Arguments.of(
                        "PDF-#51 standard measurement",
                        measurementPayload("c8y_TemperatureMeasurement", "T", 23.5, "12345"),
                        23.5),
                Arguments.of(
                        "PDF-#52 different temperature",
                        measurementPayload("c8y_TemperatureMeasurement", "T", 99.9, "12345"),
                        99.9));
    }

    // =========================================================================
    // template-SMART-OUTBOUND-02 (PDF #53) – array result, payload array
    // =========================================================================

    /**
     * PDF test #53 – template-SMART-OUTBOUND-02:
     * Returns an array of results where each result's payload is itself an array.
     */
    @Test
    void testTemplate02_ArrayResult_PayloadArray() throws Exception {
        String code = loadTemplate("template-SMART-OUTBOUND-02.js");
        Value onMessage = evalTemplate(code);

        Map<String, Object> c8yPayload = measurementPayload("c8y_TemperatureMeasurement", "T", 23.5, "12345");
        InputMessage msg = new InputMessage(c8yPayload, "measurements/12345", null, "12345", "measurement");
        Value result = onMessage.execute(graalContext.asValue(msg), graalContext.asValue(smartFunctionContext));

        assertNotNull(result, "Result must not be null");
        assertTrue(result.hasArrayElements(), "template-02 should return an array");
        assertEquals(1, result.getArraySize(), "Should return one entry");

        Value first = result.getArrayElement(0);
        assertTrue(first.hasMember("topic"), "Entry must have topic");
        assertEquals("measurements/" + EXTERNAL_ID, first.getMember("topic").asString(),
                "Topic should use resolved externalId");

        // In template-02 the payload is itself an array
        Value payloadArray = first.getMember("payload");
        assertTrue(payloadArray.hasArrayElements(), "Payload should be an array in template-02");
        assertEquals(1, payloadArray.getArraySize(), "Payload array should have one measurement");

        double actualTemp = payloadArray.getArrayElement(0)
                .getMember("c8y_Steam").getMember("Temperature").getMember("value").asDouble();
        assertEquals(23.5, actualTemp, 0.001, "Temperature value mismatch");

        log.info("✅ template-SMART-OUTBOUND-02: array result with payload array, temp={}", actualTemp);
    }

    // =========================================================================
    // template-SMART-OUTBOUND-03 (PDF #54) – transportFields / Kafka key
    // =========================================================================

    /**
     * PDF test #54 – template-SMART-OUTBOUND-03:
     * Returns array result with transportFields containing a Kafka key equal to externalId.
     */
    @Test
    void testTemplate03_TransportFields_KafkaKey() throws Exception {
        String code = loadTemplate("template-SMART-OUTBOUND-03.js");
        Value onMessage = evalTemplate(code);

        Map<String, Object> c8yPayload = measurementPayload("c8y_TemperatureMeasurement", "T", 55.0, "12345");
        InputMessage msg = new InputMessage(c8yPayload, "measurements/12345", null, "12345", "measurement");
        Value result = onMessage.execute(graalContext.asValue(msg), graalContext.asValue(smartFunctionContext));

        assertNotNull(result, "Result must not be null");
        assertTrue(result.hasArrayElements(), "template-03 should return an array");
        assertEquals(1, result.getArraySize(), "Should return one entry");

        Value first = result.getArrayElement(0);
        assertEquals("measurements/" + EXTERNAL_ID, first.getMember("topic").asString(),
                "Topic should use resolved externalId");

        double actualTemp = first.getMember("payload")
                .getMember("c8y_Steam").getMember("Temperature").getMember("value").asDouble();
        assertEquals(55.0, actualTemp, 0.001, "Temperature value mismatch");

        // Verify Kafka transport key
        assertTrue(first.hasMember("transportFields"), "Should have transportFields for Kafka key");
        Value transportFields = first.getMember("transportFields");
        assertEquals(EXTERNAL_ID, transportFields.getMember("key").asString(),
                "Kafka key should equal the resolved externalId");

        log.info("✅ template-SMART-OUTBOUND-03: Kafka key set to externalId={}", EXTERNAL_ID);
    }

    // =========================================================================
    // template-SMART-OUTBOUND-05 (PDF #55) – custom routing to microservice
    // =========================================================================

    /**
     * PDF test #55 – template-SMART-OUTBOUND-05:
     * Routes an operation to a tenant microservice via cumulocityType="custom".
     * Expected: targetPath starts with /service/, deviceId and command are forwarded.
     */
    @Test
    void testTemplate05_CustomRouting_ForwardOperation() throws Exception {
        String code = loadTemplate("template-SMART-OUTBOUND-05.js");
        Value onMessage = evalTemplate(code);

        Map<String, Object> operationPayload = new HashMap<>();
        operationPayload.put("id", "op-99001");
        operationPayload.put("deviceId", "12345");
        operationPayload.put("c8y_Command", Map.of("text", "reboot"));

        InputMessage msg = new InputMessage(operationPayload, "operations/12345", null, "12345", "operation");
        Value result = onMessage.execute(graalContext.asValue(msg), graalContext.asValue(smartFunctionContext));

        assertNotNull(result, "Result must not be null");
        // template-05 returns a plain object
        assertTrue(result.hasMembers(), "Result should be an object");

        assertEquals("custom", result.getMember("cumulocityType").asString(),
                "cumulocityType should be 'custom'");
        assertEquals("create", result.getMember("action").asString(),
                "action should be 'create'");

        String targetPath = result.getMember("targetPath").asString();
        assertTrue(targetPath.startsWith("/service/"),
                "targetPath must start with /service/, got: " + targetPath);

        Value fwdPayload = result.getMember("payload");
        assertEquals("12345", fwdPayload.getMember("deviceId").asString(),
                "deviceId should be forwarded");
        assertEquals("reboot", fwdPayload.getMember("command").asString(),
                "command should be forwarded from c8y_Command.text");
        assertEquals("op-99001", fwdPayload.getMember("operationId").asString(),
                "operationId should be forwarded");

        log.info("✅ template-SMART-OUTBOUND-05: custom routing to targetPath={}", targetPath);
    }

    // =========================================================================
    // template-SMART-OUTBOUND-04 – SparkPlug B NCMD (active device)
    // =========================================================================

    @Test
    void testTemplate04_SparkPlugB_ActiveDevice_ProducesNCMD() throws Exception {
        String code = loadTemplate("template-SMART-OUTBOUND-04.js");
        Value onMessage = evalTemplate(code);

        // externalId format for SparkPlug B: GroupID_EdgeNodeID
        smartFunctionContext.setConfig(Map.of(
                "externalId", "GroupA_Node01",
                "isActive", true,
                "aliasMap", new HashMap<String, Object>()));

        Map<String, Object> opPayload = new HashMap<>();
        opPayload.put("id", "op-001");
        opPayload.put("c8y_Command", Map.of("text", "rebirth"));

        InputMessage msg = new InputMessage(opPayload, "operations/node01", null, "node01", "operation");
        Value result = onMessage.execute(graalContext.asValue(msg), graalContext.asValue(smartFunctionContext));

        assertNotNull(result, "Result must not be null for active device");
        assertTrue(result.hasMembers(), "Result should be an object");

        String topic = result.getMember("topic").asString();
        assertTrue(topic.startsWith("spBv1.0/"), "Topic must follow SparkPlug B format");
        assertTrue(topic.contains("NCMD"), "Topic must contain NCMD message type");
        assertTrue(topic.contains("GroupA"), "Topic must contain GroupID");
        assertTrue(topic.contains("Node01"), "Topic must contain EdgeNodeID");

        log.info("✅ template-SMART-OUTBOUND-04: NCMD topic={}", topic);
    }

    @Test
    void testTemplate04_SparkPlugB_InactiveDevice_ReturnsNull() throws Exception {
        String code = loadTemplate("template-SMART-OUTBOUND-04.js");
        Value onMessage = evalTemplate(code);

        // isActive=false → template suppresses the command
        smartFunctionContext.setConfig(Map.of(
                "externalId", "GroupA_Node01",
                "isActive", false,
                "aliasMap", new HashMap<String, Object>()));

        Map<String, Object> opPayload = Map.of("id", "op-001", "c8y_Command", Map.of("text", "rebirth"));
        InputMessage msg = new InputMessage(opPayload, "operations/node01", null, "node01", "operation");
        Value result = onMessage.execute(graalContext.asValue(msg), graalContext.asValue(smartFunctionContext));

        assertTrue(result == null || result.isNull(),
                "Result should be null when device is offline (isActive=false)");

        log.info("✅ template-SMART-OUTBOUND-04: null returned for inactive device");
    }

    // =========================================================================
    // externalSource in the returned object (PDF "Metadata Properties for
    // Outbound Smart Functions"): selects the external-id type used to resolve
    // the device identity for `_externalId_` token replacement in the broker
    // topic. Earlier tests only fed externalId in via config; this asserts the
    // function-returned externalSource structure itself.
    // =========================================================================

    @Test
    void testOutbound_ExternalSource_InReturnObject_SelectsExternalIdType() throws Exception {
        String code = """
                function onMessage(msg, context) {
                  var payload = msg.getPayload();
                  return [{
                    topic: "devices/_externalId_/data",
                    payload: { value: payload[payload.type].T.value },
                    externalSource: [{ type: "c8y_Serial" }]
                  }];
                }
                """;
        Value onMessage = evalTemplate(code);

        Map<String, Object> c8yPayload = measurementPayload("c8y_TemperatureMeasurement", "T", 23.5, "12345");
        InputMessage msg = new InputMessage(c8yPayload, "measurements/12345", null, "12345", "measurement");
        Value result = onMessage.execute(graalContext.asValue(msg), graalContext.asValue(smartFunctionContext));

        assertNotNull(result, "Result must not be null");
        assertTrue(result.hasArrayElements(), "Result should be an array");
        Value first = result.getArrayElement(0);

        assertEquals("devices/_externalId_/data", first.getMember("topic").asString(),
                "topic should carry the _externalId_ token to be replaced via externalSource");

        assertTrue(first.hasMember("externalSource"), "Returned object should carry externalSource");
        Value externalSource = first.getMember("externalSource");
        assertTrue(externalSource.hasArrayElements(), "externalSource should be an array");
        assertEquals(1, externalSource.getArraySize(), "externalSource should have one entry");
        assertEquals("c8y_Serial", externalSource.getArrayElement(0).getMember("type").asString(),
                "externalSource[0].type selects the external-id type for topic token replacement");

        log.info("✅ outbound externalSource: returned object selects external-id type 'c8y_Serial'");
    }

    /**
     * {@code sourceId} in the returned object overrides the triggering device
     * (cross-device routing) and, per the docs, the {@code externalSource}
     * lookup is then skipped. This asserts the function-returned sourceId value.
     */
    @Test
    void testOutbound_SourceId_InReturnObject_OverridesDevice() throws Exception {
        String code = """
                function onMessage(msg, context) {
                  return [{
                    topic: "devices/child/data",
                    payload: { forwarded: true },
                    sourceId: "98765"
                  }];
                }
                """;
        Value onMessage = evalTemplate(code);

        Map<String, Object> c8yPayload = measurementPayload("c8y_TemperatureMeasurement", "T", 23.5, "12345");
        InputMessage msg = new InputMessage(c8yPayload, "measurements/12345", null, "12345", "measurement");
        Value result = onMessage.execute(graalContext.asValue(msg), graalContext.asValue(smartFunctionContext));

        Value first = result.getArrayElement(0);
        assertEquals("98765", first.getMember("sourceId").asString(),
                "sourceId override should be present in the returned object (cross-device routing)");

        log.info("✅ outbound sourceId: returned object overrides device with internal id 98765");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Strips ES module syntax and evaluates the template, binding {@code onMessage} on globalThis.
     */
    private Value evalTemplate(String rawCode) {
        String plainCode = JavaScriptModuleStripper.toPlainScript(rawCode);
        String wrappedCode = plainCode + "\nglobalThis['onMessage'] = onMessage;\n";
        graalContext.eval("js", wrappedCode);
        Value fn = graalContext.getBindings("js").getMember("onMessage");
        assertNotNull(fn, "onMessage function must be defined in template");
        assertTrue(fn.canExecute(), "onMessage must be executable");
        return fn;
    }

    private String loadTemplate(String templateFileName) throws IOException {
        URL resource = getClass().getClassLoader().getResource(TEMPLATES_RESOURCE_PATH + templateFileName);
        if (resource != null) {
            return new String(resource.openStream().readAllBytes(), StandardCharsets.UTF_8);
        }
        Path sourcePath = findProjectRoot()
                .resolve("dynamic-mapper-service/src/main/resources/templates/" + templateFileName);
        return Files.readString(sourcePath, StandardCharsets.UTF_8);
    }

    /** Build a minimal C8Y measurement map matching what the outbound processor provides. */
    private static Map<String, Object> measurementPayload(
            String measurementType, String series, double value, String sourceId) {
        Map<String, Object> seriesMap = new HashMap<>();
        seriesMap.put("value", value);
        seriesMap.put("unit", "C");

        Map<String, Object> measurementFragment = new HashMap<>();
        measurementFragment.put(series, seriesMap);

        Map<String, Object> source = new HashMap<>();
        source.put("id", sourceId);

        Map<String, Object> payload = new HashMap<>();
        payload.put("time", "2025-01-01T12:00:00.000Z");
        payload.put("type", measurementType);
        payload.put(measurementType, measurementFragment);
        payload.put("source", source);
        return payload;
    }

    private Path findProjectRoot() {
        Path classPath = Paths.get(getClass().getProtectionDomain().getCodeSource().getLocation().getPath());
        return classPath.getParent().getParent().getParent();
    }
}
