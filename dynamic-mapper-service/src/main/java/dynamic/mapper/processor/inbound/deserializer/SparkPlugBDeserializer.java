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

import com.cumulocity.model.ID;
import com.cumulocity.rest.representation.identity.ExternalIDRepresentation;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import dynamic.mapper.connector.core.callback.ConnectorMessage;
import dynamic.mapper.core.C8YAgent;
import dynamic.mapper.model.Mapping;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.tahu.protobuf.SparkplugBProto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deserializer for SparkPlug B encoded MQTT payloads.
 * <p>
 * SparkPlug B topics follow the pattern:
 * {@code spBv1.0/{group_id}/{message_type}/{edge_node_id}[/{device_id}]}
 * <p>
 * Supported message types: NBIRTH, NDATA, NDEATH, NCMD, DBIRTH, DDATA, DDEATH,
 * DCMD, STATE.
 * <p>
 * For NBIRTH/DBIRTH messages the decoded payload is returned as-is (all metrics
 * carry full name and data-type information). The caller (
 * {@code SendInboundProcessor}) is responsible for persisting the NBIRTH/DBIRTH
 * payload as the {@value C8YAgent#SPARKPLUGB_NBIRTH_FRAGMENT} fragment on the
 * managed object so that subsequent NDATA/DDATA messages can resolve metric
 * aliases back to their original names.
 * <p>
 * For NDATA messages the deserializer retrieves the previously stored NBIRTH
 * ({@value #SPARKPLUGB_NBIRTH_FRAGMENT}) from the <b>Edge Node</b> managed object
 * (identified by the Edge Node ID) and uses the alias→name mapping it contains.
 * <p>
 * For DDATA messages the deserializer retrieves the previously stored DBIRTH
 * ({@value #SPARKPLUGB_DBIRTH_FRAGMENT}) from the <b>Device</b> managed object
 * (identified by the Device ID) and uses the alias→name mapping it contains.
 */
@Slf4j
@Component
public class SparkPlugBDeserializer implements PayloadDeserializer<Object> {

    /**
     * Fragment key used to store the NBIRTH alias→definition map on the
     * <b>Edge Node</b> managed object (type {@code spark_Node}).
     * Retrieved when decoding subsequent NDATA / NCMD messages.
     */
    public static final String SPARKPLUGB_NBIRTH_FRAGMENT = "sparkPlugB_NBIRTH";

    /**
     * Fragment key used to store the DBIRTH alias→definition map on a
     * <b>Device</b> managed object (type {@code spark_Device}).
     * Retrieved when decoding subsequent DDATA / DCMD messages.
     */
    public static final String SPARKPLUGB_DBIRTH_FRAGMENT = "sparkPlugB_DBIRTH";

    @Autowired
    @Lazy
    private C8YAgent c8yAgent;

    @Override
    public Object deserializePayload(Mapping mapping, ConnectorMessage message) throws IOException {
        byte[] bytes = message.getPayload();
        String topic = message.getTopic();
        String tenant = message.getTenant();

        if (bytes == null || bytes.length == 0) {
            throw new IOException("SparkPlug B payload is null or empty for topic: " + topic);
        }

        // Parse the topic to extract SparkPlug B components
        SparkplugTopic sparkplugTopic = parseTopic(topic);

        // Decode the protobuf payload using the generated Tahu proto class
        SparkplugBProto.Payload protoPayload;
        try {
            protoPayload = SparkplugBProto.Payload.parseFrom(bytes);
        } catch (Exception e) {
            throw new IOException("Failed to parse SparkPlug B protobuf payload from topic " + topic + ": " + e.getMessage(), e);
        }

        // For NDATA / NCMD: load NBIRTH from the Edge Node MO
        // For DDATA / DCMD: load DBIRTH from the Device MO
        Map<Long, Map<String, Object>> aliasToMetricDef = null;
        String msgType = sparkplugTopic.getMessageType();
        if ("NDATA".equals(msgType) || "NCMD".equals(msgType)) {
            aliasToMetricDef = loadAliasMap(tenant, mapping, sparkplugTopic.getEdgeNodeId(),
                    SPARKPLUGB_NBIRTH_FRAGMENT);
        } else if ("DDATA".equals(msgType) || "DCMD".equals(msgType)) {
            // Device ID must be present for device-level messages
            String devId = sparkplugTopic.getDeviceId();
            if (devId != null) {
                aliasToMetricDef = loadAliasMap(tenant, mapping, devId, SPARKPLUGB_DBIRTH_FRAGMENT);
            } else {
                log.warn("{} - DDATA/DCMD message on topic '{}' has no Device ID; alias resolution skipped",
                        tenant, topic);
            }
        }

        // Convert protobuf metrics to a list of plain Map objects
        List<Map<String, Object>> metrics = convertMetrics(protoPayload.getMetricsList(), aliasToMetricDef);

        // Build the result map that the Smart Function will receive as its payload
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("messageType", msgType);
        result.put("groupId", sparkplugTopic.getGroupId());
        result.put("edgeNodeId", sparkplugTopic.getEdgeNodeId());
        if (sparkplugTopic.getDeviceId() != null) {
            result.put("deviceId", sparkplugTopic.getDeviceId());
        }
        if (protoPayload.hasTimestamp()) {
            result.put("timestamp", protoPayload.getTimestamp());
        }
        if (protoPayload.hasSeq()) {
            result.put("seq", protoPayload.getSeq());
        }
        result.put("metrics", metrics);

        // Attach the stored birth map if it was loaded (useful for Smart Functions)
        if (aliasToMetricDef != null) {
            String birthFragment = "NDATA".equals(msgType) || "NCMD".equals(msgType)
                    ? SPARKPLUGB_NBIRTH_FRAGMENT : SPARKPLUGB_DBIRTH_FRAGMENT;
            result.put(birthFragment, aliasToMetricDef);
        }

        log.debug("{} - SparkPlug B payload decoded: messageType={}, metrics={}", tenant, msgType, metrics.size());
        return result;
    }

    // ─── Topic parsing ────────────────────────────────────────────────────────

    /**
     * Parse a SparkPlug B topic into its components.
     * Format: {@code spBv1.0/{group_id}/{message_type}/{edge_node_id}[/{device_id}]}
     */
    private SparkplugTopic parseTopic(String topic) throws IOException {
        if (topic == null) {
            throw new IOException("SparkPlug B topic is null");
        }
        String[] parts = topic.split("/");
        // Minimum: spBv1.0 / groupId / messageType / edgeNodeId  → 4 parts
        if (parts.length < 4) {
            throw new IOException("Invalid SparkPlug B topic (too few levels): " + topic);
        }
        String groupId = parts[1];
        String messageType = parts[2];
        String edgeNodeId = parts[3];
        String deviceId = parts.length >= 5 ? parts[4] : null;
        return new SparkplugTopic(groupId, messageType, edgeNodeId, deviceId);
    }

    // ─── Alias resolution ─────────────────────────────────────────────────────

    /**
     * Load the alias→metric-definition map from the named fragment on the managed object
     * identified by {@code externalIdValue}.
     * <ul>
     *   <li>For NDATA / NCMD: {@code externalIdValue} = Edge Node ID,
     *       {@code fragmentKey} = {@value #SPARKPLUGB_NBIRTH_FRAGMENT}</li>
     *   <li>For DDATA / DCMD: {@code externalIdValue} = Device ID,
     *       {@code fragmentKey} = {@value #SPARKPLUGB_DBIRTH_FRAGMENT}</li>
     * </ul>
     *
     * @return alias→definition map, or {@code null} if the fragment cannot be found
     */
    @SuppressWarnings("unchecked")
    private Map<Long, Map<String, Object>> loadAliasMap(String tenant, Mapping mapping,
            String externalIdValue, String fragmentKey) {
        String externalIdType = mapping.getExternalIdType();
        if (externalIdType == null || externalIdType.isEmpty()) {
            externalIdType = "c8y_Serial";
        }

        try {
            ID identity = new ID(externalIdType, externalIdValue);
            ExternalIDRepresentation extIdRep = c8yAgent.resolveExternalId2GlobalId(tenant, identity, false);
            if (extIdRep == null) {
                log.debug("{} - No managed object found for externalId={} (type={}); alias resolution skipped",
                        tenant, externalIdValue, externalIdType);
                return null;
            }

            String sourceId = extIdRep.getManagedObject().getId().getValue();
            ManagedObjectRepresentation mor = c8yAgent.getManagedObjectForId(tenant, sourceId, false);
            if (mor == null) {
                return null;
            }

            Object nbirth = mor.get(fragmentKey);
            if (nbirth instanceof Map) {
                return (Map<Long, Map<String, Object>>) nbirth;
            }
        } catch (Exception e) {
            log.warn("{} - Failed to load '{}' fragment for alias resolution (externalId={}): {}",
                    tenant, fragmentKey, externalIdValue, e.getMessage());
        }
        return null;
    }

    // ─── Metric conversion ────────────────────────────────────────────────────

    /**
     * Convert a list of protobuf {@link SparkplugBProto.Payload.Metric} objects into plain
     * {@code Map<String, Object>} representations suitable for JSON serialisation and for
     * passing to Smart Functions.
     *
     * @param protoMetrics  raw metrics from the decoded protobuf
     * @param aliasToMetricDef  optional alias→definition map from a stored NBIRTH; used to
     *                          enrich metrics that carry only an alias
     */
    private List<Map<String, Object>> convertMetrics(
            List<SparkplugBProto.Payload.Metric> protoMetrics,
            Map<Long, Map<String, Object>> aliasToMetricDef) {

        List<Map<String, Object>> result = new ArrayList<>();
        for (SparkplugBProto.Payload.Metric pm : protoMetrics) {
            Map<String, Object> metric = new LinkedHashMap<>();

            // Name: may be absent in NDATA (alias-only encoding)
            String name = pm.hasName() ? pm.getName() : null;
            Long alias = pm.hasAlias() ? pm.getAlias() : null;

            // Resolve name from NBIRTH if missing
            if ((name == null || name.isEmpty()) && alias != null && aliasToMetricDef != null) {
                Map<String, Object> def = aliasToMetricDef.get(alias);
                if (def != null && def.get("name") != null) {
                    name = (String) def.get("name");
                }
            }

            if (name != null) {
                metric.put("name", name);
            }
            if (alias != null) {
                metric.put("alias", alias);
            }
            if (pm.hasTimestamp()) {
                metric.put("timestamp", pm.getTimestamp());
            }
            if (pm.getDatatype() != 0) {
                metric.put("dataType", pm.getDatatype());
            } else if (alias != null && aliasToMetricDef != null) {
                // Fallback: use stored dataType
                Map<String, Object> def = aliasToMetricDef.get(alias);
                if (def != null && def.get("dataType") != null) {
                    metric.put("dataType", def.get("dataType"));
                }
            }
            if (pm.hasIsHistorical()) {
                metric.put("isHistorical", pm.getIsHistorical());
            }
            if (pm.hasIsTransient()) {
                metric.put("isTransient", pm.getIsTransient());
            }
            if (!pm.getIsNull()) {
                metric.put("value", extractValue(pm));
            } else {
                metric.put("value", null);
            }

            result.add(metric);
        }
        return result;
    }

    /**
     * Extract the scalar value from a protobuf metric based on its datatype field.
     * Returns {@code null} for complex types (DataSet, Template, File) and unknown
     * types — the Smart Function can handle those via the raw bytes if needed.
     */
    private Object extractValue(SparkplugBProto.Payload.Metric pm) {
        int datatype = pm.getDatatype();
        // MetricDataType integer constants from SparkPlug B specification
        switch (datatype) {
            case 1:  // Int8
                return (byte) pm.getIntValue();
            case 2:  // Int16
                return (short) pm.getIntValue();
            case 3:  // Int32
                return pm.getIntValue();
            case 4:  // Int64
                return pm.getLongValue();
            case 5:  // UInt8
                return (short) (pm.getIntValue() & 0xFF);
            case 6:  // UInt16
                return pm.getIntValue() & 0xFFFF;
            case 7:  // UInt32
                return Integer.toUnsignedLong(pm.getIntValue());
            case 8:  // UInt64
                return pm.getLongValue();
            case 9:  // Float
                return pm.getFloatValue();
            case 10: // Double
                return pm.getDoubleValue();
            case 11: // Boolean
                return pm.getBooleanValue();
            case 12: // String
            case 13: // DateTime (represented as long epoch-ms)
                // datatype 13 is DateTime – return timestamp as Long
                if (datatype == 13) return pm.getLongValue();
                return pm.getStringValue();
            case 14: // Text
                return pm.getStringValue();
            case 15: // UUID
                return pm.getStringValue();
            case 16: // DataSet – return null; Smart Function can inspect raw proto
                return null;
            case 17: // Bytes
                return pm.getBytesValue().toByteArray();
            case 18: // File – return null
                return null;
            case 19: // Template – return null
                return null;
            default:
                log.debug("Unsupported SparkPlug B datatype {}, returning null", datatype);
                return null;
        }
    }

    // ─── Inner helper ─────────────────────────────────────────────────────────

    /**
     * Simple value object for the parsed components of a SparkPlug B MQTT topic.
     */
    private static class SparkplugTopic {
        private final String groupId;
        private final String messageType;
        private final String edgeNodeId;
        private final String deviceId; // null for node-level messages

        SparkplugTopic(String groupId, String messageType, String edgeNodeId, String deviceId) {
            this.groupId = groupId;
            this.messageType = messageType;
            this.edgeNodeId = edgeNodeId;
            this.deviceId = deviceId;
        }

        public String getGroupId() { return groupId; }
        public String getMessageType() { return messageType; }
        public String getEdgeNodeId() { return edgeNodeId; }
        public String getDeviceId() { return deviceId; }

        @Override
        public String toString() {
            return "SparkplugTopic{groupId='" + groupId + "', messageType='" + messageType
                    + "', edgeNodeId='" + edgeNodeId + "'"
                    + (deviceId != null ? ", deviceId='" + deviceId + "'" : "") + "}";
        }
    }
}
