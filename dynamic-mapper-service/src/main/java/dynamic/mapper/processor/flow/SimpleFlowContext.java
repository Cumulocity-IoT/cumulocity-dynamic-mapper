package dynamic.mapper.processor.flow;

import java.util.HashMap;
import java.util.Map;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

import dynamic.mapper.core.InventoryEnrichmentClient;
import lombok.extern.slf4j.Slf4j;

/**
 * Simple implementation of FlowContext for JavaScript execution
 */
@Slf4j
public class SimpleFlowContext implements FlowContext {

    private final Map<String, Object> state;
    private final Context graalContext;
    private final String tenant;
    private final InventoryEnrichmentClient inventoryEnrichmentClient;
    private Boolean testing;

    public SimpleFlowContext(Context graalContext, String tenant, InventoryEnrichmentClient inventoryEnrichmentClient, Boolean testing) {
        this.state = new HashMap<>();
        this.graalContext = graalContext;
        this.tenant = tenant != null ? tenant : "unknown";
        this.inventoryEnrichmentClient = inventoryEnrichmentClient;
        this.testing = testing;
    }

    @Override
    public void setState(String key, Value value) {
        if (key == null) {
            log.warn("{} - Cannot set state with null key", tenant);
            return;
        }

        // Convert Value to Java object for safe storage
        Object javaValue = convertValueToJavaObject(value);
        state.put(key, javaValue);

        log.debug("{} - Flow state set: {}={}", tenant, key, javaValue);
    }

    @Override
    public Value getState(String key) {
        if (key == null || graalContext == null) {
            return null;
        }

        Object javaValue = state.get(key);
        if (javaValue == null) {
            return null;
        }

        // Convert back to GraalJS Value
        return graalContext.asValue(javaValue);
    }

    @Override
    public Value getConfig() {
        if (graalContext == null) {
            return null;
        }

        // Create a basic configuration
        Map<String, Object> config = new HashMap<>();
        config.put("tenant", tenant);
        config.put("timestamp", System.currentTimeMillis());

        // Add logger
        Map<String, Object> logger = new HashMap<>();
        logger.put("info", new LogFunction("info"));
        logger.put("debug", new LogFunction("debug"));
        logger.put("warn", new LogFunction("warn"));
        logger.put("error", new LogFunction("error"));
        config.put("logger", logger);

        return graalContext.asValue(config);
    }

    @Override
    public void logMessage(Value msg) {
        if (msg == null) {
            log.info("{} - JS Log: null", tenant);
            return;
        }

        if (msg.isString()) {
            log.info("{} - JS Log: {}", tenant, msg.asString());
        } else {
            log.info("{} - JS Log: {}", tenant, msg.toString());
        }
    }

    @Override
    public Value lookupDTMAssetProperties(String assetId) {
        if (graalContext == null) {
            return null;
        }

        log.debug("{} - DTM asset lookup requested for: {}", tenant, assetId);

        // Simple placeholder implementation
        Map<String, Object> properties = new HashMap<>();
        properties.put("assetId", assetId);
        properties.put("found", false);
        properties.put("message", "DTM lookup not implemented");

        return graalContext.asValue(properties);
    }

    /**
     * Convert GraalJS Value to Java object for safe storage
     */
    private Object convertValueToJavaObject(Value value) {
        if (value == null || value.isNull()) {
            return null;
        } else if (value.isString()) {
            return value.asString();
        } else if (value.isNumber()) {
            if (value.fitsInInt()) {
                return value.asInt();
            } else if (value.fitsInLong()) {
                return value.asLong();
            } else {
                return value.asDouble();
            }
        } else if (value.isBoolean()) {
            return value.asBoolean();
        } else if (value.hasArrayElements()) {
            // Convert to simple list for basic support
            java.util.List<Object> list = new java.util.ArrayList<>();
            long size = value.getArraySize();
            for (int i = 0; i < size; i++) {
                list.add(convertValueToJavaObject(value.getArrayElement(i)));
            }
            return list;
        } else if (value.hasMembers()) {
            // Convert to simple map for basic support
            Map<String, Object> map = new HashMap<>();
            for (String key : value.getMemberKeys()) {
                map.put(key, convertValueToJavaObject(value.getMember(key)));
            }
            return map;
        } else {
            // Fallback to string representation
            return value.toString();
        }
    }

    /**
     * Helper class for JavaScript-callable logging functions
     */
    private class LogFunction {
        private final String level;

        public LogFunction(String level) {
            this.level = level;
        }

        public void apply(Object... args) {
            StringBuilder message = new StringBuilder();
            for (Object arg : args) {
                if (message.length() > 0) {
                    message.append(" ");
                }
                message.append(arg != null ? arg.toString() : "null");
            }

            switch (level) {
                case "info":
                    log.info("{} - JS: {}", tenant, message.toString());
                    break;
                case "debug":
                    log.debug("{} - JS: {}", tenant, message.toString());
                    break;
                case "warn":
                    log.warn("{} - JS: {}", tenant, message.toString());
                    break;
                case "error":
                    log.error("{} - JS: {}", tenant, message.toString());
                    break;
                default:
                    log.info("{} - JS: {}", tenant, message.toString());
                    break;
            }
        }
    }

    @Override
    public Value getStateAll() {
        if (graalContext == null) {
            return null;
        }

        Object javaValue = state;
        if (javaValue == null) {
            return null;
        }

        // Convert back to GraalJS Value
        return graalContext.asValue(javaValue);
    }

    @Override
    public Value getStateKeySet() {
        if (graalContext == null) {
            return null;
        }

        Object javaValue = state.keySet();
        if (javaValue == null) {
            return null;
        }

        // Convert back to GraalJS Value
        return graalContext.asValue(javaValue);
    }

    @Override
    public Value lookupDeviceByDeviceId(String deviceId) {
        Object javaValue = inventoryEnrichmentClient.getMOFromInventoryCache(tenant, deviceId, testing);
        return graalContext.asValue(javaValue);
    }

    @Override
    public Value lookupDeviceByExternalId(String externalId, String type) {
        Object javaValue = inventoryEnrichmentClient.getMOFromInventoryCacheByExternalId(tenant, externalId, type, testing);
        return graalContext.asValue(javaValue);
    }
}