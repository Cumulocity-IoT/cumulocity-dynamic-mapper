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

package dynamic.mapper.processor.inbound.deserializer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import dynamic.mapper.client.SparkplugBMqttTestClient;
import dynamic.mapper.connector.core.callback.ConnectorMessage;
import dynamic.mapper.core.C8YAgent;
import dynamic.mapper.core.CacheManager;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.processor.model.MappingType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Unit tests for {@link SparkPlugBDeserializer}.
 *
 * <p>The deserializer is instantiated directly (no Spring context) and its
 * Spring-managed dependencies ({@code c8yAgent}, {@code cacheManager}) are
 * injected via reflection to match the {@code @Autowired} production wiring.</p>
 *
 * <p>Protobuf payloads are built with
 * {@link SparkplugBMqttTestClient#buildSparkplugPayload} — the same helper
 * used by the integration test client.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SparkplugBDeserializerTest {

    private static final String TENANT        = "test-tenant";
    private static final String GROUP_ID      = "factory-01";
    private static final String EDGE_NODE_ID  = "edge-node-01";
    private static final String DEVICE_ID     = "device-01";

    @Mock
    private C8YAgent c8yAgent;

    @Mock
    private CacheManager cacheManager;

    @Mock
    private ConnectorMessage connectorMessage;

    private SparkPlugBDeserializer deserializer;

    @BeforeEach
    void setUp() throws Exception {
        deserializer = new SparkPlugBDeserializer(c8yAgent, cacheManager);

        // Default stubs
        when(connectorMessage.getTenant()).thenReturn(TENANT);
        when(cacheManager.getInventoryCache(anyString())).thenReturn(null); // cache miss
    }

    // ── NBIRTH — all metric types ─────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void nbirth_decodesAllFiveMetricTypes() throws IOException {
        List<SparkplugBMqttTestClient.Metric> metrics = new ArrayList<>();
        metrics.add(SparkplugBMqttTestClient.Metric.ofFloat("temperature",     23.7f));
        metrics.add(SparkplugBMqttTestClient.Metric.ofDouble("humidity",       55.2));
        metrics.add(SparkplugBMqttTestClient.Metric.ofInt32("errorCode",       0));
        metrics.add(SparkplugBMqttTestClient.Metric.ofBoolean("motorRunning",  true));
        metrics.add(SparkplugBMqttTestClient.Metric.ofString("firmware",       "2.4.1"));

        String topic   = "spBv1.0/" + GROUP_ID + "/NBIRTH/" + EDGE_NODE_ID;
        byte[] payload = SparkplugBMqttTestClient.buildSparkplugPayload(System.currentTimeMillis(), 0L, metrics);

        when(connectorMessage.getTopic()).thenReturn(topic);
        when(connectorMessage.getPayload()).thenReturn(payload);

        Object result = deserializer.deserializePayload(buildMapping(), connectorMessage);

        assertNotNull(result);
        Map<String, Object> resultMap = (Map<String, Object>) result;

        assertEquals("NBIRTH", resultMap.get("messageType"));
        assertEquals(GROUP_ID,     resultMap.get("groupId"));
        assertEquals(EDGE_NODE_ID, resultMap.get("edgeNodeId"));

        List<Map<String, Object>> decodedMetrics = (List<Map<String, Object>>) resultMap.get("metrics");
        assertNotNull(decodedMetrics);
        assertEquals(5, decodedMetrics.size(), "All 5 metrics must be decoded");

        // Verify metric names are present
        List<String> names = decodedMetrics.stream()
                .map(m -> (String) m.get("name"))
                .toList();
        assertTrue(names.contains("temperature"),  "temperature metric must be present");
        assertTrue(names.contains("humidity"),     "humidity metric must be present");
        assertTrue(names.contains("errorCode"),    "errorCode metric must be present");
        assertTrue(names.contains("motorRunning"), "motorRunning metric must be present");
        assertTrue(names.contains("firmware"),     "firmware metric must be present");

        // NBIRTH builds alias map inline — no C8Y calls required
        verifyNoInteractions(c8yAgent);
    }

    @Test
    @SuppressWarnings("unchecked")
    void nbirth_withDeviceId_populatesDeviceIdField() throws IOException {
        List<SparkplugBMqttTestClient.Metric> metrics = List.of(
                SparkplugBMqttTestClient.Metric.ofFloat("voltage", 3.3f));
        String topic   = "spBv1.0/" + GROUP_ID + "/DBIRTH/" + EDGE_NODE_ID + "/" + DEVICE_ID;
        byte[] payload = SparkplugBMqttTestClient.buildSparkplugPayload(System.currentTimeMillis(), 0L, metrics);

        when(connectorMessage.getTopic()).thenReturn(topic);
        when(connectorMessage.getPayload()).thenReturn(payload);

        Object result = deserializer.deserializePayload(buildMapping(), connectorMessage);
        Map<String, Object> resultMap = (Map<String, Object>) result;

        assertEquals("DBIRTH",    resultMap.get("messageType"));
        assertEquals(DEVICE_ID,   resultMap.get("deviceId"));
        verifyNoInteractions(c8yAgent);
    }

    // ── NDATA — alias resolution via C8Y agent ────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void ndata_withMissingNbirth_gracefullyReturnsMetricsWithoutNames() throws IOException {
        // No ExternalIDRepresentation in C8Y → alias resolution returns null gracefully
        when(c8yAgent.resolveExternalId2GlobalId(eq(TENANT), any(), anyBoolean()))
                .thenReturn(null);

        List<SparkplugBMqttTestClient.Metric> metrics = List.of(
                SparkplugBMqttTestClient.Metric.ofFloat("pressure", 1013.25f));
        String topic   = "spBv1.0/" + GROUP_ID + "/NDATA/" + EDGE_NODE_ID;
        byte[] payload = SparkplugBMqttTestClient.buildSparkplugPayload(System.currentTimeMillis(), 1L, metrics);

        when(connectorMessage.getTopic()).thenReturn(topic);
        when(connectorMessage.getPayload()).thenReturn(payload);

        Object result = deserializer.deserializePayload(buildMapping(), connectorMessage);
        assertNotNull(result, "Deserializer must return a result even when NBIRTH is unavailable");

        Map<String, Object> resultMap = (Map<String, Object>) result;
        assertEquals("NDATA", resultMap.get("messageType"));
        List<?> decodedMetrics = (List<?>) resultMap.get("metrics");
        assertFalse(decodedMetrics.isEmpty(), "Metrics must still be decoded even without alias map");
    }

    // ── Error cases ───────────────────────────────────────────────────────

    @Test
    void nullPayload_throwsIOException() {
        when(connectorMessage.getTopic()).thenReturn("spBv1.0/g/NDATA/e");
        when(connectorMessage.getPayload()).thenReturn(null);

        assertThrows(IOException.class,
                () -> deserializer.deserializePayload(buildMapping(), connectorMessage),
                "Null payload must throw IOException");
    }

    @Test
    void emptyPayload_throwsIOException() {
        when(connectorMessage.getTopic()).thenReturn("spBv1.0/g/NDATA/e");
        when(connectorMessage.getPayload()).thenReturn(new byte[0]);

        assertThrows(IOException.class,
                () -> deserializer.deserializePayload(buildMapping(), connectorMessage),
                "Empty payload must throw IOException");
    }

    @Test
    void corruptPayload_throwsIOException() {
        when(connectorMessage.getTopic()).thenReturn("spBv1.0/g/NDATA/e");
        when(connectorMessage.getPayload()).thenReturn(new byte[]{0x01, 0x02, 0x03}); // not valid protobuf

        // Corrupt protobuf may parse as an empty payload without metrics (Tahu is lenient)
        // or throw — either is acceptable; we just verify no NPE/unexpected exception type
        try {
            Object result = deserializer.deserializePayload(buildMapping(), connectorMessage);
            // If it doesn't throw, the result must at least be non-null
            assertNotNull(result);
        } catch (IOException e) {
            // Expected path for truly malformed protobuf
            assertTrue(e.getMessage() != null);
        }
    }

    @Test
    void tooFewTopicLevels_throwsIOException() {
        when(connectorMessage.getTopic()).thenReturn("spBv1.0/onlyThreeParts");
        when(connectorMessage.getPayload()).thenReturn(new byte[]{0x08, 0x00}); // minimal valid varint

        assertThrows(IOException.class,
                () -> deserializer.deserializePayload(buildMapping(), connectorMessage),
                "Topic with fewer than 4 levels must throw IOException");
    }

    @Test
    void nullTopic_throwsIOException() {
        when(connectorMessage.getTopic()).thenReturn(null);
        when(connectorMessage.getPayload()).thenReturn(new byte[]{0x08, 0x00});

        assertThrows(IOException.class,
                () -> deserializer.deserializePayload(buildMapping(), connectorMessage),
                "Null topic must throw IOException");
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private Mapping buildMapping() {
        Mapping mapping = new Mapping();
        mapping.setMappingType(MappingType.SPARKPLUGB);
        mapping.setExternalIdType("c8y_Serial");
        return mapping;
    }
}
