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
import dynamic.mapper.core.CacheManager;
import dynamic.mapper.core.cache.InventoryCache;
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

    @Autowired
    @Lazy
    private CacheManager cacheManager;

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
        // For NBIRTH / DBIRTH: build inline alias map from the birth metrics themselves
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
        } else if ("NBIRTH".equals(msgType) || "DBIRTH".equals(msgType)) {
            aliasToMetricDef = buildAliasMapFromMetrics(protoPayload.getMetricsList());
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
        /**
        if (aliasToMetricDef != null) {
            String birthFragment = "NDATA".equals(msgType) || "NCMD".equals(msgType)
                    ? SPARKPLUGB_NBIRTH_FRAGMENT : SPARKPLUGB_DBIRTH_FRAGMENT;
            result.put(birthFragment, aliasToMetricDef);
        }
         **/

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

            // Try to get from inventory cache first
            Map<Long, Map<String, Object>> cachedFragment = loadFragmentFromCache(tenant, sourceId, fragmentKey);
            if (cachedFragment != null) {
                log.info("{} - Loaded '{}' fragment from cache for sourceId={}", tenant, fragmentKey, sourceId);
                return cachedFragment;
            }

            log.info("{} - '{}' fragment not found in cache for sourceId={}, falling back to direct retrieval", tenant, fragmentKey, sourceId);
            // Fallback to direct retrieval from C8YAgent
            ManagedObjectRepresentation mor = c8yAgent.getManagedObjectForId(tenant, sourceId, false);
            if (mor == null) {
                return null;
            }

            Object aliasMap = mor.get(fragmentKey);
            if (aliasMap instanceof Map) {
                return normalizeAliasMapKeys((Map<?, ?>) aliasMap);
            }
        } catch (Exception e) {
            log.warn("{} - Failed to load '{}' fragment for alias resolution (externalId={}): {}",
                    tenant, fragmentKey, externalIdValue, e.getMessage());
        }
        return null;
    }

    /**
     * Attempt to load a fragment from the inventory cache.
     *
     * @param tenant the tenant identifier
     * @param sourceId the managed object source ID
     * @param fragmentKey the fragment key to retrieve
     * @return the fragment map, or {@code null} if not found in cache or cache is unavailable
     */
    private Map<Long, Map<String, Object>> loadFragmentFromCache(String tenant, String sourceId, String fragmentKey) {
        try {
            InventoryCache inventoryCache = cacheManager.getInventoryCache(tenant);
            if (inventoryCache == null) {
                return null;
            }

            Map<String, Object> cachedMO = inventoryCache.getMOBySource(sourceId);
            if (cachedMO != null) {
                Object fragment = cachedMO.get(fragmentKey);
                if (fragment instanceof Map) {
                    return normalizeAliasMapKeys((Map<?, ?>) fragment);
                }
            }
        } catch (Exception e) {
            log.debug("{} - Failed to load fragment from inventory cache (sourceId={}): {}",
                    tenant, sourceId, e.getMessage());
        }
        return null;
    }

    /**
     * Normalize alias map keys to Long.
     * When the birth fragment is round-tripped through JSON (stored on a C8Y managed object
     * and read back), numeric keys are deserialised as Strings. This method converts any
     * String or Number key to Long so that Long-keyed lookups succeed.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Map<Long, Map<String, Object>> normalizeAliasMapKeys(Map raw) {
        Map<Long, Map<String, Object>> normalized = new LinkedHashMap<>();
        for (Object key : raw.keySet()) {
            try {
                Long longKey = key instanceof Long ? (Long) key : Long.parseLong(key.toString());
                Object val = raw.get(key);
                if (val instanceof Map) {
                    normalized.put(longKey, (Map<String, Object>) val);
                }
            } catch (NumberFormatException ignored) {
                log.debug("Skipping non-numeric alias map key: {}", key);
            }
        }
        return normalized;
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
            // Resolve datatype: proto value takes priority, fall back to birth-map entry
            int resolvedDatatype = pm.getDatatype();
            if (resolvedDatatype == 0 && alias != null && aliasToMetricDef != null) {
                Map<String, Object> def = aliasToMetricDef.get(alias);
                if (def != null && def.get("dataType") instanceof Number) {
                    resolvedDatatype = ((Number) def.get("dataType")).intValue();
                }
            }
            if (resolvedDatatype != 0) {
                metric.put("dataType", resolveDataTypeName(resolvedDatatype));
            }
            if (pm.hasIsHistorical()) {
                metric.put("isHistorical", pm.getIsHistorical());
            }
            if (pm.hasIsTransient()) {
                metric.put("isTransient", pm.getIsTransient());
            }
            if (!pm.getIsNull()) {
                metric.put("value", extractValue(pm, resolvedDatatype));
            } else {
                metric.put("value", null);
            }

            result.add(metric);
        }
        return result;
    }

    /**
     * Extract the scalar value from a protobuf metric.
     * Uses the supplied {@code resolvedDatatype} (which may come from the birth-map
     * when NDATA/DDATA metrics omit the datatype field).
     */
    private Object extractValue(SparkplugBProto.Payload.Metric pm, int resolvedDatatype) {
        switch (resolvedDatatype) {
            case 1:  return (byte) pm.getIntValue();
            case 2:  return (short) pm.getIntValue();
            case 3:  return pm.getIntValue();
            case 4:  return pm.getLongValue();
            case 5:  return (short) (pm.getIntValue() & 0xFF);
            case 6:  return pm.getIntValue() & 0xFFFF;
            case 7:  return Integer.toUnsignedLong(pm.getIntValue());
            case 8:  return pm.getLongValue();
            case 9:  return pm.getFloatValue();
            case 10: return pm.getDoubleValue();
            case 11: return pm.getBooleanValue();
            case 12: return pm.getStringValue();
            case 13: return pm.getLongValue();   // DateTime as epoch-ms
            case 14: return pm.getStringValue(); // Text
            case 15: return pm.getStringValue(); // UUID
            case 16: return null;                // DataSet
            case 17: return pm.getBytesValue().toByteArray();
            case 18: return null;                // File
            case 19: return null;                // Template
            default:
                log.debug("Unsupported SparkPlug B datatype {}, returning null", resolvedDatatype);
                return null;
        }
    }

    /** Map SparkPlug B datatype integer to its specification name. */
    private String resolveDataTypeName(int datatype) {
        switch (datatype) {
            case 1:  return "Int8";
            case 2:  return "Int16";
            case 3:  return "Int32";
            case 4:  return "Int64";
            case 5:  return "UInt8";
            case 6:  return "UInt16";
            case 7:  return "UInt32";
            case 8:  return "UInt64";
            case 9:  return "Float";
            case 10: return "Double";
            case 11: return "Boolean";
            case 12: return "String";
            case 13: return "DateTime";
            case 14: return "Text";
            case 15: return "UUID";
            case 16: return "DataSet";
            case 17: return "Bytes";
            case 18: return "File";
            case 19: return "Template";
            default: return "Unknown(" + datatype + ")";
        }
    }

    /**
     * Build an alias→definition map from the metrics of a NBIRTH/DBIRTH payload.
     * Used so that alias-only metrics within the same birth message can have their
     * name resolved in-place during {@link #convertMetrics}.
     */
    private Map<Long, Map<String, Object>> buildAliasMapFromMetrics(
            List<SparkplugBProto.Payload.Metric> metrics) {
        Map<Long, Map<String, Object>> aliasMap = new LinkedHashMap<>();
        for (SparkplugBProto.Payload.Metric pm : metrics) {
            if (!pm.hasAlias()) {
                continue;
            }
            Map<String, Object> def = new LinkedHashMap<>();
            if (pm.hasName() && !pm.getName().isEmpty()) {
                def.put("name", pm.getName());
            }
            if (pm.getDatatype() != 0) {
                def.put("dataType", pm.getDatatype());
            }
            aliasMap.put(pm.getAlias(), def);
        }
        return aliasMap;
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
