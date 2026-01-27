/*
 * Copyright (c) 2022-2025 Cumulocity GmbH.
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

package dynamic.mapper.processor.flow;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;

/**
 * Unit tests for CumulocityObject and DeviceMessage builders
 */
public class BuildersTest {

    @Test
    public void testMeasurementBuilder() {
        CumulocityObject measurement = CumulocityObject.measurement()
                .type("c8y_TemperatureMeasurement")
                .time("2024-01-01T12:00:00Z")
                .fragment("c8y_Temperature", "T", 25.5, "C")
                .externalId("device-001", "c8y_Serial")
                .build();

        assertEquals(CumulocityType.MEASUREMENT, measurement.getCumulocityType());
        assertEquals("create", measurement.getAction());
        assertEquals(1, measurement.getExternalSource().size());
        assertEquals("device-001", measurement.getExternalSource().get(0).getExternalId());
        assertEquals("c8y_Serial", measurement.getExternalSource().get(0).getType());

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) measurement.getPayload();
        assertEquals("c8y_TemperatureMeasurement", payload.get("type"));
        assertEquals("2024-01-01T12:00:00Z", payload.get("time"));
        assertTrue(payload.containsKey("c8y_Temperature"));
    }

    @Test
    public void testAlarmBuilder() {
        CumulocityObject alarm = CumulocityObject.alarm()
                .type("c8y_TemperatureAlarm")
                .severity("CRITICAL")
                .text("Temperature exceeds threshold")
                .status("ACTIVE")
                .externalId("device-001", "c8y_Serial")
                .deviceName("Temperature Sensor 1")
                .build();

        assertEquals(CumulocityType.ALARM, alarm.getCumulocityType());
        assertEquals("create", alarm.getAction());

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) alarm.getPayload();
        assertEquals("c8y_TemperatureAlarm", payload.get("type"));
        assertEquals("CRITICAL", payload.get("severity"));
        assertEquals("Temperature exceeds threshold", payload.get("text"));
        assertEquals("ACTIVE", payload.get("status"));

        assertEquals("Temperature Sensor 1", alarm.getContextData().get("deviceName"));
    }

    @Test
    public void testEventBuilder() {
        CumulocityObject event = CumulocityObject.event()
                .type("c8y_LocationUpdate")
                .text("Device location updated")
                .time("2024-01-01T12:00:00Z")
                .property("c8y_Position", Map.of("lat", 52.5, "lng", 13.4))
                .externalId("device-001", "c8y_Serial")
                .build();

        assertEquals(CumulocityType.EVENT, event.getCumulocityType());

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) event.getPayload();
        assertEquals("c8y_LocationUpdate", payload.get("type"));
        assertEquals("Device location updated", payload.get("text"));
        assertTrue(payload.containsKey("c8y_Position"));
    }

    @Test
    public void testOperationBuilder() {
        CumulocityObject operation = CumulocityObject.operation()
                .description("Restart device")
                .status("PENDING")
                .fragment("c8y_Restart", Map.of())
                .externalId("device-001", "c8y_Serial")
                .build();

        assertEquals(CumulocityType.OPERATION, operation.getCumulocityType());

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) operation.getPayload();
        assertEquals("Restart device", payload.get("description"));
        assertEquals("PENDING", payload.get("status"));
        assertTrue(payload.containsKey("c8y_Restart"));
    }

    @Test
    public void testManagedObjectBuilder() {
        CumulocityObject managedObject = CumulocityObject.managedObject()
                .name("Temperature Sensor")
                .type("c8y_Device")
                .fragment("c8y_IsDevice", Map.of())
                .externalId("device-001", "c8y_Serial")
                .build();

        assertEquals(CumulocityType.MANAGED_OBJECT, managedObject.getCumulocityType());

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) managedObject.getPayload();
        assertEquals("Temperature Sensor", payload.get("name"));
        assertEquals("c8y_Device", payload.get("type"));
        assertTrue(payload.containsKey("c8y_IsDevice"));
    }

    @Test
    public void testDeviceMessageBuilder() {
        DeviceMessage message = DeviceMessage.forTopic("device/messages")
                .payload("{\"temperature\": 25.5}")
                .retain(false)
                .clientId("device-001")
                .transportField("qos", "1")
                .build();

        assertEquals("device/messages", message.getTopic());
        assertEquals("{\"temperature\": 25.5}", message.getPayload());
        assertEquals(false, message.getRetain());
        assertEquals("device-001", message.getClientId());
        assertEquals("1", message.getTransportFields().get("qos"));
    }

    @Test
    public void testDeviceMessageBuilderRetainShorthand() {
        DeviceMessage message = DeviceMessage.forTopic("device/messages")
                .payload("test")
                .retain() // Shorthand for retain(true)
                .build();

        assertTrue(message.getRetain());
    }

    @Test
    public void testBuilderWithMultipleExternalIds() {
        CumulocityObject measurement = CumulocityObject.measurement()
                .type("c8y_TemperatureMeasurement")
                .time("2024-01-01T12:00:00Z")
                .externalId("device-001", "c8y_Serial")
                .externalId("device-001-alt", "c8y_MacAddress")
                .build();

        assertEquals(2, measurement.getExternalSource().size());
    }

    @Test
    public void testBuilderWithAction() {
        CumulocityObject alarm = CumulocityObject.alarm()
                .action("update")
                .type("c8y_TemperatureAlarm")
                .severity("CRITICAL")
                .text("Updated alarm")
                .externalId("device-001", "c8y_Serial")
                .build();

        assertEquals("update", alarm.getAction());
    }

    @Test
    public void testBuilderWithDestination() {
        CumulocityObject measurement = CumulocityObject.measurement()
                .type("c8y_TemperatureMeasurement")
                .time("2024-01-01T12:00:00Z")
                .destination(Destination.ICEFLOW)
                .externalId("device-001", "c8y_Serial")
                .build();

        assertEquals(Destination.ICEFLOW, measurement.getDestination());
    }

    @Test
    public void testBuilderWithProcessingMode() {
        CumulocityObject measurement = CumulocityObject.measurement()
                .type("c8y_TemperatureMeasurement")
                .time("2024-01-01T12:00:00Z")
                .processingMode("TRANSIENT")
                .externalId("device-001", "c8y_Serial")
                .build();

        assertEquals("TRANSIENT", measurement.getContextData().get("processingMode"));
    }

    @Test
    public void testEventBuilderWithAttachment() {
        CumulocityObject event = CumulocityObject.event()
                .type("c8y_LogfileRequest")
                .text("Logfile uploaded")
                .time("2024-01-01T12:00:00Z")
                .attachment("logfile.txt", "text/plain", "base64encodeddata")
                .externalId("device-001", "c8y_Serial")
                .build();

        assertEquals("logfile.txt", event.getContextData().get("attachmentName"));
        assertEquals("text/plain", event.getContextData().get("attachmentType"));
        assertEquals("base64encodeddata", event.getContextData().get("attachmentData"));
    }

    @Test
    public void testMessageFactory() {
        // Test Message.from() factory method would require a ProcessingContext mock
        // For now, just verify the builders work independently
        Message<String> message = new Message<>("test payload", "test/topic", "client-001", Map.of());

        assertEquals("test payload", message.getPayload());
        assertEquals("test/topic", message.getTopic());
        assertEquals("client-001", message.getClientId());
    }
}
