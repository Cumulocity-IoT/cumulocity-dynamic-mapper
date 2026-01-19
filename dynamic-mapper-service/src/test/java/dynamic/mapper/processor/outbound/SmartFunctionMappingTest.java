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

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import lombok.extern.slf4j.Slf4j;

/**
 * Tests for SMART_FUNCTION mapping type that validates JavaScript code
 * execution for outbound message processing.
 *
 * SMART_FUNCTION mappings use JavaScript with onMessage() function to process
 * platform events and transform them into device-specific messages.
 */
@Slf4j
@ExtendWith(MockitoExtension.class)
class SmartFunctionMappingTest {

    private Context graalContext;

    @BeforeEach
    void setUp() {
        // Create GraalVM context for JavaScript execution
        graalContext = Context.newBuilder("js")
                .allowAllAccess(true)
                .build();
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

    @Test
    void testBasicOnMessageFunction() {
        // Given - Simple onMessage function
        String jsCode = """
                function onMessage(msg, context) {
                    var payload = msg.getPayload();
                    return [{
                        topic: "test/topic",
                        payload: { value: 42 }
                    }];
                }
                """;

        // When
        graalContext.eval("js", jsCode);
        Value onMessage = graalContext.getBindings("js").getMember("onMessage");

        // Then
        assertNotNull(onMessage, "onMessage function should be defined");
        assertTrue(onMessage.canExecute(), "onMessage should be executable");

        log.info("✅ Basic onMessage function validated");
    }

    @Test
    void testOnMessageWithPayloadTransformation() {
        // Given - onMessage with payload transformation
        String jsCode = """
                function onMessage(msg, context) {
                    var payload = msg.getPayload();

                    var result = {
                        topic: "measurement/measurements/" + payload.source.id,
                        payload: {
                            type: payload.type,
                            time: payload.time,
                            c8y_Temperature: {
                                T: {
                                    value: payload.temperature,
                                    unit: "C"
                                }
                            }
                        }
                    };

                    return [result];
                }
                """;

        // Create mock message object
        Map<String, Object> mockPayload = new HashMap<>();
        mockPayload.put("type", "c8y_Measurement");
        mockPayload.put("time", "2024-01-19T10:00:00.000Z");
        mockPayload.put("temperature", 25.5);

        Map<String, Object> source = new HashMap<>();
        source.put("id", "12345");
        mockPayload.put("source", source);

        Map<String, Object> mockMsg = new HashMap<>();
        mockMsg.put("payload", mockPayload);

        // When
        graalContext.eval("js", jsCode);
        graalContext.eval("js", """
                function getPayload() { return this.payload; }
                """);

        Value result = graalContext.eval("js", """
                var msg = {
                    getPayload: function() {
                        return {
                            type: "c8y_Measurement",
                            time: "2024-01-19T10:00:00.000Z",
                            temperature: 25.5,
                            source: { id: "12345" }
                        };
                    }
                };
                onMessage(msg, {});
                """);

        // Then
        assertNotNull(result, "Result should not be null");
        assertTrue(result.hasArrayElements(), "Result should be an array");
        assertEquals(1, result.getArraySize(), "Result should have one element");

        log.info("✅ onMessage with payload transformation validated");
    }

    @Test
    void testOnMessageWithMultipleOutputMessages() {
        // Given - onMessage that returns multiple messages
        String jsCode = """
                function onMessage(msg, context) {
                    var payload = msg.getPayload();

                    var measurement = {
                        topic: "measurement/measurements/" + payload.source.id,
                        payload: {
                            type: "TestMeasurement",
                            time: new Date().toISOString(),
                            value: 42
                        }
                    };

                    var event = {
                        topic: "event/events/" + payload.source.id,
                        payload: {
                            type: "TestEvent",
                            text: "Processed measurement",
                            time: new Date().toISOString()
                        }
                    };

                    return [measurement, event];
                }
                """;

        // When
        graalContext.eval("js", jsCode);
        Value result = graalContext.eval("js", """
                var msg = {
                    getPayload: function() {
                        return { source: { id: "12345" } };
                    }
                };
                onMessage(msg, {});
                """);

        // Then
        assertNotNull(result, "Result should not be null");
        assertTrue(result.hasArrayElements(), "Result should be an array");
        assertEquals(2, result.getArraySize(), "Result should have two elements");

        // Verify first message (measurement)
        Value firstMsg = result.getArrayElement(0);
        assertTrue(firstMsg.hasMember("topic"), "First message should have topic");
        assertTrue(firstMsg.getMember("topic").asString().contains("measurement/measurements"),
                "First message should be measurement");

        // Verify second message (event)
        Value secondMsg = result.getArrayElement(1);
        assertTrue(secondMsg.hasMember("topic"), "Second message should have topic");
        assertTrue(secondMsg.getMember("topic").asString().contains("event/events"),
                "Second message should be event");

        log.info("✅ onMessage with multiple output messages validated");
    }

    @Test
    void testOnMessageWithHelperFunctions() {
        // Given - onMessage with helper functions (like hexStringToUint8Array from Digital Matter)
        String jsCode = """
                function hexStringToUint8Array(hexString) {
                    const length = hexString.length / 2;
                    const result = [];
                    for (let i = 0; i < length; i++) {
                        const byte = hexString.slice(i * 2, i * 2 + 2);
                        result.push(Number.parseInt(byte, 16));
                    }
                    return result;
                }

                function onMessage(msg, context) {
                    var payload = msg.getPayload();
                    var hexData = payload.data;
                    var bytes = hexStringToUint8Array(hexData);

                    return [{
                        topic: "decoded/data",
                        payload: {
                            hexData: hexData,
                            byteCount: bytes.length,
                            firstByte: bytes[0]
                        }
                    }];
                }
                """;

        // When
        graalContext.eval("js", jsCode);
        Value result = graalContext.eval("js", """
                var msg = {
                    getPayload: function() {
                        return { data: "44504120" };
                    }
                };
                onMessage(msg, {});
                """);

        // Then
        assertNotNull(result, "Result should not be null");
        assertTrue(result.hasArrayElements(), "Result should be an array");

        Value output = result.getArrayElement(0);
        Value outputPayload = output.getMember("payload");
        assertEquals(4, outputPayload.getMember("byteCount").asInt(),
                "Should have decoded 4 bytes from hex string");
        assertEquals(0x44, outputPayload.getMember("firstByte").asInt(),
                "First byte should be 0x44");

        log.info("✅ onMessage with helper functions validated");
    }

    @Test
    void testOnMessageWithComplexDecoder() {
        // Given - Complex decoder function (simplified version of Digital Matter G62)
        String jsCode = """
                function Decoder(bytes, port) {
                    var decoded = {};
                    if (port === 1 && bytes.length >= 11) {
                        decoded._type = "full data";
                        decoded.port = port;
                        decoded.byteCount = bytes.length;
                        decoded.latitude = bytes[0] + bytes[1] * 256;
                        decoded.longitude = bytes[2] + bytes[3] * 256;
                    }
                    return decoded;
                }

                function onMessage(msg, context) {
                    var payload = msg.getPayload();
                    var bytes = [0x44, 0x50, 0x41, 0x20, 0x00, 0x00, 0x41, 0xC9, 0xD7, 0x0A, 0x42];
                    var decoded = Decoder(bytes, 1);

                    return [{
                        topic: "decoded/measurement",
                        payload: {
                            type: "LocationMeasurement",
                            decoded: decoded
                        }
                    }];
                }
                """;

        // When
        graalContext.eval("js", jsCode);
        Value result = graalContext.eval("js", """
                var msg = {
                    getPayload: function() {
                        return {};
                    }
                };
                onMessage(msg, {});
                """);

        // Then
        assertNotNull(result, "Result should not be null");
        Value output = result.getArrayElement(0);
        Value outputPayload = output.getMember("payload");
        Value decodedData = outputPayload.getMember("decoded");

        assertEquals("full data", decodedData.getMember("_type").asString(),
                "Should have decoded full data");
        assertEquals(1, decodedData.getMember("port").asInt(),
                "Should have port 1");
        assertTrue(decodedData.getMember("byteCount").asInt() >= 11,
                "Should have at least 11 bytes");

        log.info("✅ onMessage with complex decoder validated");
    }

    @Test
    void testOnMessageWithConditionalLogic() {
        // Given - onMessage with conditional logic
        String jsCode = """
                function onMessage(msg, context) {
                    var payload = msg.getPayload();
                    var results = [];

                    // Only process if temperature is above threshold
                    if (payload.temperature > 30) {
                        results.push({
                            topic: "alarm/alarms",
                            payload: {
                                type: "c8y_TemperatureAlarm",
                                text: "High temperature detected: " + payload.temperature,
                                severity: "MAJOR"
                            }
                        });
                    }

                    // Always send measurement
                    results.push({
                        topic: "measurement/measurements",
                        payload: {
                            type: "c8y_Temperature",
                            value: payload.temperature
                        }
                    });

                    return results;
                }
                """;

        // When - Temperature below threshold
        graalContext.eval("js", jsCode);
        Value resultLow = graalContext.eval("js", """
                var msg = {
                    getPayload: function() {
                        return { temperature: 25 };
                    }
                };
                onMessage(msg, {});
                """);

        // Then - Should return only measurement
        assertEquals(1, resultLow.getArraySize(), "Should return only measurement for low temp");

        // When - Temperature above threshold
        Value resultHigh = graalContext.eval("js", """
                var msg = {
                    getPayload: function() {
                        return { temperature: 35 };
                    }
                };
                onMessage(msg, {});
                """);

        // Then - Should return alarm and measurement
        assertEquals(2, resultHigh.getArraySize(), "Should return alarm and measurement for high temp");
        assertTrue(resultHigh.getArrayElement(0).getMember("topic").asString().contains("alarm"),
                "First message should be alarm");

        log.info("✅ onMessage with conditional logic validated");
    }

    @Test
    void testOnMessageWithTimestampHandling() {
        // Given - onMessage with timestamp handling
        String jsCode = """
                function onMessage(msg, context) {
                    var payload = msg.getPayload();
                    var timestamp = payload.timestamp || new Date().toISOString();

                    return [{
                        topic: "measurement/measurements",
                        payload: {
                            type: "TestMeasurement",
                            time: timestamp,
                            value: payload.value
                        }
                    }];
                }
                """;

        // When
        graalContext.eval("js", jsCode);
        Value result = graalContext.eval("js", """
                var msg = {
                    getPayload: function() {
                        return {
                            timestamp: "2024-01-19T10:00:00.000Z",
                            value: 42
                        };
                    }
                };
                onMessage(msg, {});
                """);

        // Then
        assertNotNull(result, "Result should not be null");
        Value output = result.getArrayElement(0);
        Value outputPayload = output.getMember("payload");
        assertEquals("2024-01-19T10:00:00.000Z", outputPayload.getMember("time").asString(),
                "Should use provided timestamp");

        log.info("✅ onMessage with timestamp handling validated");
    }

    @Test
    void testOnMessageWithConsoleLogging() {
        // Given - onMessage with console.log statements (for debugging)
        String jsCode = """
                function onMessage(msg, context) {
                    var payload = msg.getPayload();
                    console.log("Processing message with payload:", payload);

                    var result = {
                        topic: "test/output",
                        payload: { processed: true }
                    };

                    console.log("Returning result:", result);
                    return [result];
                }
                """;

        // When
        graalContext.eval("js", jsCode);
        Value result = graalContext.eval("js", """
                var msg = {
                    getPayload: function() {
                        return { data: "test" };
                    }
                };
                onMessage(msg, {});
                """);

        // Then
        assertNotNull(result, "Result should not be null even with console.log");
        assertTrue(result.hasArrayElements(), "Result should be an array");

        log.info("✅ onMessage with console logging validated");
    }

    @Test
    void testBase64EncodedSmartFunctionCode() {
        // Given - Base64 encoded JavaScript code (as stored in mapping)
        String jsCode = """
                function onMessage(msg, context) {
                    var payload = msg.getPayload();
                    return [{
                        topic: "test/topic",
                        payload: { value: 42 }
                    }];
                }
                """;

        String encodedCode = Base64.getEncoder().encodeToString(jsCode.getBytes());

        // When - Decode and execute
        byte[] decoded = Base64.getDecoder().decode(encodedCode);
        String decodedJs = new String(decoded);

        graalContext.eval("js", decodedJs);
        Value onMessage = graalContext.getBindings("js").getMember("onMessage");

        // Then
        assertNotNull(onMessage, "onMessage function should be defined after decoding");
        assertTrue(onMessage.canExecute(), "Decoded function should be executable");

        log.info("✅ Base64 encoded SMART_FUNCTION code validated");
    }

    @Test
    void testOnMessageErrorHandling() {
        // Given - onMessage with error handling
        String jsCode = """
                function onMessage(msg, context) {
                    try {
                        var payload = msg.getPayload();
                        if (!payload.source || !payload.source.id) {
                            throw new Error("Missing source.id in payload");
                        }

                        return [{
                            topic: "measurement/measurements/" + payload.source.id,
                            payload: { value: 42 }
                        }];
                    } catch (error) {
                        console.log("Error processing message:", error.message);
                        return [];
                    }
                }
                """;

        // When - Valid payload
        graalContext.eval("js", jsCode);
        Value resultValid = graalContext.eval("js", """
                var msg = {
                    getPayload: function() {
                        return { source: { id: "12345" } };
                    }
                };
                onMessage(msg, {});
                """);

        // Then - Should return result
        assertEquals(1, resultValid.getArraySize(), "Should return result for valid payload");

        // When - Invalid payload
        Value resultInvalid = graalContext.eval("js", """
                var msg = {
                    getPayload: function() {
                        return { data: "test" };
                    }
                };
                onMessage(msg, {});
                """);

        // Then - Should return empty array
        assertEquals(0, resultInvalid.getArraySize(), "Should return empty array for invalid payload");

        log.info("✅ onMessage error handling validated");
    }
}
