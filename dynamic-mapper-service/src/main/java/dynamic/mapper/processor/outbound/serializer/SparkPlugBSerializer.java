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
package dynamic.mapper.processor.outbound.serializer;

import java.util.List;
import java.util.Map;

import org.eclipse.tahu.protobuf.SparkplugBProto;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Serializes an outbound SparkPlug B payload map (as returned by a Smart Function's
 * {@code onMessage()} call) into a SparkPlug B protobuf binary payload.
 *
 * <p>The Smart Function is expected to return an object whose {@code payload} field has the shape:
 * <pre>{@code
 * {
 *   "timestamp": 1234567890000,     // optional epoch-ms; defaults to System.currentTimeMillis()
 *   "metrics": [
 *     { "name": "Output/Bool",  "value": true,    "type": "Boolean" },
 *     { "name": "Output/Int",   "value": 42,      "type": "Int32"   },
 *     { "name": "Sensor/Temp",  "value": 23.5,    "type": "Double"  },
 *     { "name": "Tag/Label",    "value": "hello", "type": "String"  }
 *   ]
 * }
 * }</pre>
 *
 * <p>Supported {@code type} strings (case-insensitive):
 * <ul>
 *   <li>Int8 (1), Int16 (2), Int32 (3), Int64 (4)</li>
 *   <li>UInt8 (5), UInt16 (6), UInt32 (7), UInt64 (8)</li>
 *   <li>Float (9), Double (10)</li>
 *   <li>Boolean (11)</li>
 *   <li>String (12), Text (14), UUID (15)</li>
 *   <li>DateTime (13) — treated as epoch-ms long</li>
 * </ul>
 *
 * <p>The {@code seq} field (sequence number, 0–255 rolling) is set from {@code payload.seq} when
 * present; otherwise it defaults to 0.
 */
@Slf4j
@Component
public class SparkPlugBSerializer {

    /**
     * Serialize the payload map returned by the Smart Function into SparkPlug B proto bytes.
     *
     * @param payloadMap the {@code payload} object from the JS return value (a deserialized JSON map)
     * @return SparkPlug B protobuf binary representation
     * @throws IllegalArgumentException if the payload map is null or contains unrecognized metric types
     */
    public byte[] serialize(Map<String, Object> payloadMap) {
        if (payloadMap == null) {
            throw new IllegalArgumentException("SparkPlugB payload map must not be null");
        }

        SparkplugBProto.Payload.Builder payloadBuilder = SparkplugBProto.Payload.newBuilder();

        // Timestamp
        long timestamp = extractLong(payloadMap, "timestamp", System.currentTimeMillis());
        payloadBuilder.setTimestamp(timestamp);

        // Sequence number (0–255 rolling)
        long seq = extractLong(payloadMap, "seq", 0L);
        payloadBuilder.setSeq(seq & 0xFFL);

        // Metrics
        Object metricsRaw = payloadMap.get("metrics");
        if (metricsRaw instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> metricsList = (List<Object>) metricsRaw;
            for (Object metricRaw : metricsList) {
                if (metricRaw instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> metricMap = (Map<String, Object>) metricRaw;
                    SparkplugBProto.Payload.Metric metric = buildMetric(metricMap);
                    if (metric != null) {
                        payloadBuilder.addMetrics(metric);
                    }
                } else {
                    log.warn("SparkPlugBSerializer: skipping non-map metric entry: {}", metricRaw);
                }
            }
        } else if (metricsRaw != null) {
            log.warn("SparkPlugBSerializer: 'metrics' field is not a list, ignoring: {}", metricsRaw.getClass().getSimpleName());
        }

        return payloadBuilder.build().toByteArray();
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private SparkplugBProto.Payload.Metric buildMetric(Map<String, Object> metricMap) {
        String name = (String) metricMap.get("name");
        Object value = metricMap.get("value");
        String typeStr = (String) metricMap.get("type");

        if (name == null || name.isEmpty()) {
            log.warn("SparkPlugBSerializer: metric has no 'name', skipping");
            return null;
        }
        if (typeStr == null || typeStr.isEmpty()) {
            log.warn("SparkPlugBSerializer: metric '{}' has no 'type', skipping", name);
            return null;
        }

        int datatype = resolveDatatype(typeStr);
        if (datatype == 0) {
            log.warn("SparkPlugBSerializer: unrecognized metric type '{}' for metric '{}', skipping", typeStr, name);
            return null;
        }

        SparkplugBProto.Payload.Metric.Builder metricBuilder = SparkplugBProto.Payload.Metric.newBuilder();
        metricBuilder.setName(name);
        metricBuilder.setDatatype(datatype);

        // Optional alias
        Object alias = metricMap.get("alias");
        if (alias instanceof Number) {
            metricBuilder.setAlias(((Number) alias).longValue());
        }

        // Optional timestamp per-metric
        Object metricTs = metricMap.get("timestamp");
        if (metricTs instanceof Number) {
            metricBuilder.setTimestamp(((Number) metricTs).longValue());
        }

        setMetricValue(metricBuilder, datatype, value, name);

        return metricBuilder.build();
    }

    @SuppressWarnings("java:S1751")
    private void setMetricValue(SparkplugBProto.Payload.Metric.Builder b, int datatype, Object value, String name) {
        try {
            switch (datatype) {
                case 1:  // Int8
                case 2:  // Int16
                case 3:  // Int32
                case 5:  // UInt8
                case 6:  // UInt16
                case 7:  // UInt32
                    b.setIntValue(toInt(value));
                    break;
                case 4:  // Int64
                case 8:  // UInt64
                case 13: // DateTime
                    b.setLongValue(toLong(value));
                    break;
                case 9:  // Float
                    b.setFloatValue(toFloat(value));
                    break;
                case 10: // Double
                    b.setDoubleValue(toDouble(value));
                    break;
                case 11: // Boolean
                    b.setBooleanValue(toBoolean(value));
                    break;
                case 12: // String
                case 14: // Text
                case 15: // UUID
                    b.setStringValue(value != null ? value.toString() : "");
                    break;
                case 17: // Bytes
                    if (value instanceof byte[]) {
                        b.setBytesValue(com.google.protobuf.ByteString.copyFrom((byte[]) value));
                    } else {
                        log.warn("SparkPlugBSerializer: metric '{}' type Bytes but value is not byte[], skipping value", name);
                    }
                    break;
                default:
                    log.warn("SparkPlugBSerializer: metric '{}' datatype {} has no value setter, skipping value", name, datatype);
            }
        } catch (Exception e) {
            log.warn("SparkPlugBSerializer: could not set value for metric '{}' (type={}): {}", name, datatype, e.getMessage());
        }
    }

    private int resolveDatatype(String type) {
        switch (type.toLowerCase()) {
            case "int8":     return 1;
            case "int16":    return 2;
            case "int32":    return 3;
            case "int64":    return 4;
            case "uint8":    return 5;
            case "uint16":   return 6;
            case "uint32":   return 7;
            case "uint64":   return 8;
            case "float":    return 9;
            case "double":   return 10;
            case "boolean":  return 11;
            case "string":   return 12;
            case "datetime": return 13;
            case "text":     return 14;
            case "uuid":     return 15;
            case "bytes":    return 17;
            default:         return 0;
        }
    }

    private long extractLong(Map<String, Object> map, String key, long defaultValue) {
        Object val = map.get(key);
        if (val instanceof Number) {
            return ((Number) val).longValue();
        }
        return defaultValue;
    }

    private int toInt(Object value) {
        if (value instanceof Number) return ((Number) value).intValue();
        if (value instanceof String) return Integer.parseInt((String) value);
        throw new IllegalArgumentException("Cannot convert to int: " + value);
    }

    private long toLong(Object value) {
        if (value instanceof Number) return ((Number) value).longValue();
        if (value instanceof String) return Long.parseLong((String) value);
        throw new IllegalArgumentException("Cannot convert to long: " + value);
    }

    private float toFloat(Object value) {
        if (value instanceof Number) return ((Number) value).floatValue();
        if (value instanceof String) return Float.parseFloat((String) value);
        throw new IllegalArgumentException("Cannot convert to float: " + value);
    }

    private double toDouble(Object value) {
        if (value instanceof Number) return ((Number) value).doubleValue();
        if (value instanceof String) return Double.parseDouble((String) value);
        throw new IllegalArgumentException("Cannot convert to double: " + value);
    }

    private boolean toBoolean(Object value) {
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof String) return Boolean.parseBoolean((String) value);
        if (value instanceof Number) return ((Number) value).intValue() != 0;
        throw new IllegalArgumentException("Cannot convert to boolean: " + value);
    }
}
