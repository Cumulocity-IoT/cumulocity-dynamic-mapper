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

package dynamic.mapper.client;

import com.google.protobuf.CodedOutputStream;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient;
import com.hivemq.client.mqtt.mqtt3.Mqtt3BlockingClient;
import com.hivemq.client.mqtt.mqtt3.Mqtt3Client;
import com.hivemq.client.mqtt.mqtt3.message.auth.Mqtt3SimpleAuth;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Standalone MQTT test client that publishes hand-crafted SparkPlugB DDATA
 * messages to a broker, without requiring the Eclipse Tahu library.
 *
 * <p>Payloads are built directly with {@link CodedOutputStream} from
 * {@code com.google.protobuf:protobuf-java}, which is already on the
 * classpath.  The encoding follows the
 * <a href="https://github.com/eclipse/tahu/blob/master/sparkplug_b/sparkplug_b.proto">
 * sparkplug_b.proto</a> wire format.</p>
 *
 * <h3>SparkPlugB proto schema (abridged)</h3>
 * <pre>
 * message Payload {
 *   uint64          timestamp = 1;   // tag 0x08  wire-type 0 (varint)
 *   repeated Metric metrics   = 2;   // tag 0x12  wire-type 2 (length-delimited)
 *   uint64          seq       = 3;   // tag 0x18  wire-type 0 (varint)
 * }
 * message Metric {
 *   string name      = 1;   // tag 0x0A  wire-type 2
 *   uint64 timestamp = 3;   // tag 0x18  wire-type 0
 *   uint32 datatype  = 4;   // tag 0x20  wire-type 0
 *   oneof value {
 *     uint32 int_value     = 10;  // tag 0x50  wire-type 0
 *     uint64 long_value    = 11;  // tag 0x58  wire-type 0
 *     float  float_value   = 12;  // tag 0x65  wire-type 5 (fixed32)
 *     double double_value  = 13;  // tag 0x69  wire-type 1 (fixed64)
 *     bool   boolean_value = 14;  // tag 0x70  wire-type 0
 *     string string_value  = 15;  // tag 0x7A  wire-type 2
 *   }
 * }
 * </pre>
 *
 * <h3>SparkPlugB DataType enum values used here</h3>
 * <pre>
 *   Int32=3  Float=9  Double=10  Boolean=11  String=12
 * </pre>
 *
 * <h3>MQTT topic structure</h3>
 * {@code spBv1.0/<groupId>/<msgType>/<edgeNodeId>/<deviceId>}
 *
 * <h3>Environment variables</h3>
 * <pre>
 *   BROKER_HOST      (required) e.g. "localhost"
 *   BROKER_PORT      (default 1883)
 *   CLIENT_ID        (default "sparkplug-test-client")
 *   BROKER_USERNAME  (optional — enables simple auth + TLS)
 *   BROKER_PASSWORD  (optional)
 * </pre>
 *
 * <h3>Dynamic Mapper mapping configuration</h3>
 * <ul>
 *   <li>MappingType    : ANY_PAYLOAD</li>
 *   <li>Transformation : SMART_FUNCTION</li>
 *   <li>Topic pattern  : {@code spBv1.0/+/DDATA/+/+}</li>
 *   <li>Code template  : template-SMART-INBOUND-08 (SparkPlugB DDATA/NDATA decoder)</li>
 * </ul>
 *
 * <p>Expected Cumulocity output for the default payload sent by
 * {@link #sendDdata()}:</p>
 * <pre>
 *   Measurement type=c8y_SparkplugMeasurement
 *     c8y_SparkplugMetrics.temperature  = 23.7  (Float)
 *     c8y_SparkplugMetrics.humidity     = 55.2  (Double)
 *     c8y_SparkplugMetrics.errorCode    = 0     (Int32)
 *     c8y_SparkplugMetrics.motorRunning = 1     (Boolean → 1/0)
 *   Event type=c8y_SparkplugStringMetric
 *     text = "firmwareVersion = 2.4.1"
 * </pre>
 */
@Slf4j
public class SparkplugBMqttTestClient {

    // ── SparkPlugB DataType constants ──────────────────────────────────────
    static final int DT_INT32   = 3;
    static final int DT_FLOAT   = 9;
    static final int DT_DOUBLE  = 10;
    static final int DT_BOOLEAN = 11;
    static final int DT_STRING  = 12;

    // ── Connection settings ────────────────────────────────────────────────
    private static final String BROKER_HOST     = System.getenv("BROKER_HOST");
    private static final int    BROKER_PORT      = resolveBrokerPort();
    private static final String CLIENT_ID       = System.getenv().getOrDefault(
            "CLIENT_ID", "sparkplug-test-client");
    private static final String BROKER_USERNAME = System.getenv("BROKER_USERNAME");
    private static final String BROKER_PASSWORD = System.getenv("BROKER_PASSWORD");
    /** TLS is required.  Defaults to {@code true} when port == 8883, overridable via {@code BROKER_SSL=true|false}. */
    private static final boolean BROKER_SSL      = Boolean.parseBoolean(
            System.getenv().getOrDefault("BROKER_SSL", BROKER_PORT == 8883 ? "true" : "false"));

    // ── SparkPlugB address ─────────────────────────────────────────────────
    private static final String GROUP_ID     = "factory-01";
    private static final String EDGE_NODE_ID = "edge-node-01";
    private static final String DEVICE_ID    = "device-01";

    private final Mqtt3BlockingClient mqttClient;

    private static int resolveBrokerPort() {
        return parseBrokerPort(System.getenv().getOrDefault("BROKER_PORT", "1883"), 1883);
    }

    private static int parseBrokerPort(String brokerPortValue, int defaultPort) {
        try {
            return Integer.parseInt(brokerPortValue);
        } catch (NumberFormatException ex) {
            log.warn("Invalid BROKER_PORT '{}', falling back to default {}", brokerPortValue, defaultPort);
            return defaultPort;
        }
    }

    public SparkplugBMqttTestClient(Mqtt3BlockingClient mqttClient) {
        this.mqttClient = mqttClient;
    }

    private static void validateRequiredConfiguration() {
        if (BROKER_HOST == null || BROKER_HOST.isBlank()) {
            log.error("Missing required environment variable: BROKER_HOST");
            log.error("Set BROKER_HOST to the MQTT broker hostname or IP address before starting this client.");
            System.exit(1);
        }
    }

    // ── Entry point ────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        validateRequiredConfiguration();

        log.info("=== SparkPlugB MQTT Test Client ===");
        log.info("Broker host : {}", BROKER_HOST);
        log.info("Broker port : {}", BROKER_PORT);
        log.info("TLS/SSL     : {}", BROKER_SSL);
        log.info("Client ID   : {}", CLIENT_ID);
        log.info("===================================");

        Mqtt3BlockingClient client = buildMqttClient();
        SparkplugBMqttTestClient testClient = new SparkplugBMqttTestClient(client);
        testClient.sendDdata();
    }

    // ── DDATA message ──────────────────────────────────────────────────────

    /**
     * Builds and publishes one SparkPlugB DDATA message on topic
     * {@code spBv1.0/factory-01/DDATA/edge-node-01/device-01}.
     *
     * <p>The payload contains five metrics that exercise every supported
     * data type:
     * <ul>
     *   <li>{@code temperature}     — Float  (23.7 °C)</li>
     *   <li>{@code humidity}        — Double (55.2 %)</li>
     *   <li>{@code errorCode}       — Int32  (0)</li>
     *   <li>{@code motorRunning}    — Boolean (true)</li>
     *   <li>{@code firmwareVersion} — String ("2.4.1") → emitted as C8Y Event</li>
     * </ul>
     */
    public void sendDdata() throws IOException {
        String topic = "spBv1.0/" + GROUP_ID + "/DDATA/" + EDGE_NODE_ID + "/" + DEVICE_ID;

        List<Metric> metrics = new ArrayList<>();
        metrics.add(Metric.ofFloat("temperature",     23.7f));
        metrics.add(Metric.ofDouble("humidity",       55.2));
        metrics.add(Metric.ofInt32("errorCode",       0));
        metrics.add(Metric.ofBoolean("motorRunning",  true));
        metrics.add(Metric.ofString("firmwareVersion","2.4.1"));

        long timestamp = System.currentTimeMillis();
        long seq       = 1L;
        byte[] payload = buildSparkplugPayload(timestamp, seq, metrics);

        log.info("Connecting to {}:{}", BROKER_HOST, BROKER_PORT);
        mqttClient.connect();

        log.info("Publishing SparkPlugB DDATA ({} bytes) → topic: {}", payload.length, topic);
        log.info("Payload Base64: {}", Base64.getEncoder().encodeToString(payload));

        Mqtt3AsyncClient async = mqttClient.toAsync();
        async.publishWith()
                .topic(topic)
                .qos(MqttQos.AT_LEAST_ONCE)
                .payload(payload)
                .send();

        log.info("Published. Disconnecting.");
        mqttClient.disconnect();
    }

    // ── Wire-format builder ────────────────────────────────────────────────

    /**
     * Serialises a {@code sparkplug_b.proto Payload} containing the given metrics.
     *
     * @param timestamp epoch-milliseconds (Payload.timestamp, field 1)
     * @param seq       sequence number 0-255 (Payload.seq, field 3)
     * @param metrics   list of metrics to include
     * @return raw protobuf bytes ready to publish as MQTT payload
     */
    public static byte[] buildSparkplugPayload(long timestamp, long seq, List<Metric> metrics)
            throws IOException {

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CodedOutputStream cos = CodedOutputStream.newInstance(out);

        // field 1: timestamp (uint64)
        cos.writeUInt64(1, timestamp);

        // field 2: repeated Metric (length-delimited embedded message)
        for (Metric m : metrics) {
            byte[] metricBytes = buildMetric(m);
            cos.writeByteArray(2, metricBytes);
        }

        // field 3: seq (uint64)
        cos.writeUInt64(3, seq);

        cos.flush();
        return out.toByteArray();
    }

    /**
     * Serialises one {@code sparkplug_b.proto Metric}.
     */
    static byte[] buildMetric(Metric m) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CodedOutputStream cos = CodedOutputStream.newInstance(out);

        // field 1: name (string)
        cos.writeString(1, m.name);

        // field 3: timestamp (uint64)
        cos.writeUInt64(3, m.timestamp);

        // field 4: datatype (uint32)
        cos.writeUInt32(4, m.datatype);

        // value oneof — field number chosen per sparkplug_b.proto
        switch (m.datatype) {
            case DT_INT32:
                cos.writeUInt32(10, (int) m.longValue);
                break;
            case DT_FLOAT:
                cos.writeFloat(12, m.floatValue);
                break;
            case DT_DOUBLE:
                cos.writeDouble(13, m.doubleValue);
                break;
            case DT_BOOLEAN:
                cos.writeBool(14, m.boolValue);
                break;
            case DT_STRING:
                cos.writeString(15, m.stringValue);
                break;
            default:
                log.warn("Unsupported SparkPlugB datatype {} for metric '{}' — skipping value", m.datatype, m.name);
        }

        cos.flush();
        return out.toByteArray();
    }

    // ── Metric value object ────────────────────────────────────────────────

    /**
     * Represents a single SparkPlugB metric with its name, datatype, and value.
     * Uses explicit typed fields to avoid boxing/unboxing and retain clarity.
     */
    public static class Metric {
        public final String  name;
        public final int     datatype;
        public final long    timestamp;

        // Value fields — only the one matching datatype is meaningful
        final long    longValue;
        final float   floatValue;
        final double  doubleValue;
        final boolean boolValue;
        final String  stringValue;

        private Metric(String name, int datatype,
                       long longValue, float floatValue, double doubleValue,
                       boolean boolValue, String stringValue) {
            this.name        = name;
            this.datatype    = datatype;
            this.timestamp   = System.currentTimeMillis();
            this.longValue   = longValue;
            this.floatValue  = floatValue;
            this.doubleValue = doubleValue;
            this.boolValue   = boolValue;
            this.stringValue = stringValue;
        }

        public static Metric ofFloat(String name, float value) {
            return new Metric(name, DT_FLOAT, 0, value, 0, false, null);
        }

        public static Metric ofDouble(String name, double value) {
            return new Metric(name, DT_DOUBLE, 0, 0f, value, false, null);
        }

        public static Metric ofInt32(String name, int value) {
            return new Metric(name, DT_INT32, value, 0f, 0, false, null);
        }

        public static Metric ofBoolean(String name, boolean value) {
            return new Metric(name, DT_BOOLEAN, 0, 0f, 0, value, null);
        }

        public static Metric ofString(String name, String value) {
            return new Metric(name, DT_STRING, 0, 0f, 0, false, value);
        }

        @Override
        public String toString() {
            switch (datatype) {
                case DT_FLOAT:   return name + "=" + floatValue  + " (Float)";
                case DT_DOUBLE:  return name + "=" + doubleValue + " (Double)";
                case DT_INT32:   return name + "=" + longValue   + " (Int32)";
                case DT_BOOLEAN: return name + "=" + boolValue   + " (Boolean)";
                case DT_STRING:  return name + "=" + stringValue + " (String)";
                default:         return name + " (datatype=" + datatype + ")";
            }
        }
    }

    // ── MQTT client builder ────────────────────────────────────────────────

    private static Mqtt3BlockingClient buildMqttClient() {
        var builder = Mqtt3Client.builder()
                .serverHost(BROKER_HOST)
                .serverPort(BROKER_PORT)
                .identifier(CLIENT_ID);

        if (BROKER_USERNAME != null && !BROKER_USERNAME.isEmpty()
                && BROKER_PASSWORD != null && !BROKER_PASSWORD.isEmpty()) {
            builder = builder.simpleAuth(Mqtt3SimpleAuth.builder()
                    .username(BROKER_USERNAME)
                    .password(BROKER_PASSWORD.getBytes())
                    .build());
        }

        if (BROKER_SSL) {
            builder = builder.sslWithDefaultConfig();
        }

        return builder.buildBlocking();
    }
}
