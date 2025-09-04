package dynamic.mapper.processor.inbound.processor;

import static dynamic.mapper.model.Substitution.toPrettyJsonString;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.camel.Exchange;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.MappingStatus;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.flow.CumulocityMessage;
import dynamic.mapper.processor.flow.DeviceMessage;
import dynamic.mapper.processor.flow.FlowContext;
import dynamic.mapper.processor.flow.JavaScriptInteropHelper;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.service.MappingService;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class FlowProcessorInboundProcessor extends BaseProcessor implements FlowContext {

    @Autowired
    private MappingService mappingService;

    private ProcessingContext<?> currentContext; // Store current context for FlowContext methods

    @Override
    public void process(Exchange exchange) throws Exception {
        ProcessingContext<?> context = exchange.getIn().getHeader("processingContext", ProcessingContext.class);
        this.currentContext = context; // Set for FlowContext methods

        Mapping mapping = context.getMapping();
        String tenant = context.getTenant();

        try {
            processSmartMapping(context);
        } catch (Exception e) {
            int lineNumber = 0;
            if (e.getStackTrace().length > 0) {
                lineNumber = e.getStackTrace()[0].getLineNumber();
            }
            String errorMessage = String.format(
                    "Tenant %s - Error in FlowProcessorInboundProcessor: %s for mapping: %s, line %s",
                    tenant, mapping.name, e.getMessage(), lineNumber);
            log.error(errorMessage, e);

            MappingStatus mappingStatus = mappingService.getMappingStatus(tenant, mapping);
            context.addError(new ProcessingException(errorMessage, e));
            mappingStatus.errors++;
            mappingService.increaseAndHandleFailureCount(tenant, mapping, mappingStatus);
            return;
        } finally {
            this.currentContext = null; // Clear context
        }
    }

    public void processSmartMapping(ProcessingContext<?> context) throws ProcessingException {
        String tenant = context.getTenant();
        Mapping mapping = context.getMapping();
        ServiceConfiguration serviceConfiguration = context.getServiceConfiguration();

        Object payloadObject = context.getPayload();

        String payload = toPrettyJsonString(payloadObject);
        if (serviceConfiguration.logPayload || mapping.debug) {
            log.info("{} - Processing payload: {}", tenant, payload);
        }

        if (mapping.code != null) {
            Context graalContext = context.getGraalContext();

            // Task 1: Invoking JavaScript function
            String identifier = "onMessage_" + mapping.identifier;
            Value bindings = graalContext.getBindings("js");

            // Load and execute the JavaScript code
            byte[] decodedBytes = Base64.getDecoder().decode(mapping.code);
            String decodedCode = new String(decodedBytes);
            String decodedCodeAdapted = decodedCode.replaceFirst(
                    "onMessage",
                    identifier);

            Source source = Source.newBuilder("js", decodedCodeAdapted, identifier + ".js")
                    .buildLiteral();
            graalContext.eval(source);

            // Load shared and system code if available
            loadSharedCode(graalContext, context);
            loadSystemCode(graalContext, context);

            Value onMessageFunction = bindings.getMember(identifier);

            // Create input message (DeviceMessage or CumulocityMessage)
            Value inputMessage = createInputMessage(graalContext, context);

            // Execute the JavaScript function
            final Value result = onMessageFunction.execute(inputMessage, this);

            // Task 2: Extracting the result
            processResult(result, context, tenant);
        }
    }

    private void loadSharedCode(Context graalContext, ProcessingContext<?> context) {
        if (context.getSharedCode() != null) {
            byte[] decodedSharedCodeBytes = Base64.getDecoder().decode(context.getSharedCode());
            String decodedSharedCode = new String(decodedSharedCodeBytes);
            Source sharedSource = Source.newBuilder("js", decodedSharedCode, "sharedCode.js")
                    .buildLiteral();
            graalContext.eval(sharedSource);
        }
    }

    private void loadSystemCode(Context graalContext, ProcessingContext<?> context) {
        if (context.getSystemCode() != null) {
            byte[] decodedSystemCodeBytes = Base64.getDecoder().decode(context.getSystemCode());
            String decodedSystemCode = new String(decodedSystemCodeBytes);
            Source systemSource = Source.newBuilder("js", decodedSystemCode, "systemCode.js")
                    .buildLiteral();
            graalContext.eval(systemSource);
        }
    }

    private Value createInputMessage(Context graalContext, ProcessingContext<?> context) {
        // Create a DeviceMessage from the current context
        DeviceMessage deviceMessage = new DeviceMessage();

        // Set payload - convert to proper Java object first
        deviceMessage.setPayload(context.getPayload());

        // Set topic
        deviceMessage.setTopic(context.getTopic());

        // Set transport information if available
        if (context.getMapping() != null) {
            deviceMessage.setTransportId("mqtt"); // Default, could be configurable
            deviceMessage.setClientId(context.getMapping().getGenericDeviceIdentifier());
        }

        // Convert to JavaScript object
        return graalContext.asValue(deviceMessage);
    }

    private void processResult(Value result, ProcessingContext<?> context, String tenant) {
        if (!result.hasArrayElements()) {
            log.warn("{} - onMessage function did not return an array", tenant);
            context.setIgnoreFurtherProcessing(true);
            return;
        }

        long arraySize = result.getArraySize();
        if (arraySize == 0) {
            log.info("{} - onMessage function returned empty array", tenant);
            context.setIgnoreFurtherProcessing(true);
            return;
        }

        List<Object> outputMessages = new ArrayList<>();

        for (int i = 0; i < arraySize; i++) {
            Value element = result.getArrayElement(i);

            if (JavaScriptInteropHelper.isDeviceMessage(element)) {
                DeviceMessage deviceMsg = JavaScriptInteropHelper.convertToDeviceMessage(element);
                outputMessages.add(deviceMsg);
                log.debug("{} - Processed DeviceMessage: topic={}", tenant, deviceMsg.getTopic());

            } else if (JavaScriptInteropHelper.isCumulocityMessage(element)) {
                CumulocityMessage cumulocityMsg = JavaScriptInteropHelper.convertToCumulocityMessage(element);
                outputMessages.add(cumulocityMsg);
                log.debug("{} - Processed CumulocityMessage: type={}, action={}",
                        tenant, cumulocityMsg.getCumulocityType(), cumulocityMsg.getAction());
            } else {
                log.warn("{} - Unknown message type returned from onMessage function", tenant);
            }
        }

        context.setFlowResult(outputMessages);

        if (context.getMapping().getDebug() || context.getServiceConfiguration().logPayload) {
            log.info("{} - onMessage function returned {} complete messages", tenant, outputMessages.size());
        }
    }

    // FlowContext implementation
    @Override
    public void setState(String key, Value value) {
        if (currentContext != null) {
            Object javaValue = JavaScriptInteropHelper.convertValueToJavaObject(value);
            currentContext.getFlowState().put(key, javaValue);
            log.debug("{} - Flow state set: {}={}",
                    currentContext.getTenant(), key, javaValue);
        } else {
            log.warn("Cannot set state - no current context available");
        }
    }

    @Override
    public Value getState(String key) {
        if (currentContext != null && currentContext.getGraalContext() != null) {

            if (currentContext.getFlowState() != null && currentContext.getFlowState().containsKey(key)) {
                Object value = currentContext.getFlowState().get(key);
                // Convert back to GraalJS Value
                Value graalValue = currentContext.getGraalContext().asValue(value);

                log.debug("{} - Flow state retrieved: {}={}",
                        currentContext.getTenant(), key, value);
                return graalValue;
            } else {
                log.debug("{} - Flow state not found for key: {}",
                        currentContext.getTenant(), key);
            }
        } else {
            log.warn("Cannot get state - no current context or GraalJS context available");
        }
        return null;
    }

    @Override
    public Value getConfig() {
        if (currentContext != null && currentContext.getGraalContext() != null) {
            // Convert mapping configuration to JavaScript object
            Map<String, Object> config = new HashMap<>();
            Mapping mapping = currentContext.getMapping();

            if (mapping != null) {
                config.put("mappingName", mapping.name);
                config.put("targetAPI", mapping.targetAPI != null ? mapping.targetAPI.toString() : null);
                config.put("deviceIdentifier", mapping.getGenericDeviceIdentifier());
                config.put("mappingId", mapping.id);
                config.put("mappingTopic", mapping.mappingTopic);
                config.put("currentTopic", currentContext.getTopic());
            }

            // Add service configuration
            ServiceConfiguration serviceConfig = currentContext.getServiceConfiguration();
            if (serviceConfig != null) {
                config.put("logPayload", serviceConfig.logPayload);
                config.put("tenant", currentContext.getTenant());
            }

            // Add logger object for context.logger.info() support
            Map<String, Object> logger = new HashMap<>();
            logger.put("info", new LogFunction("info"));
            logger.put("debug", new LogFunction("debug"));
            logger.put("warn", new LogFunction("warn"));
            logger.put("error", new LogFunction("error"));

            config.put("logger", logger);

            Value configValue = currentContext.getGraalContext().asValue(config);

            log.debug("{} - Flow config provided with {} properties",
                    currentContext.getTenant(), config.size());

            return configValue;
        } else {
            log.warn("Cannot get config - no current context or GraalJS context available");
            return null;
        }
    }

    @Override
    public void logMessage(Value msg) {
        logInfo("JS Log", msg);
    }

    @Override
    public Value lookupDTMAssetProperties(String assetId) {
        String tenant = currentContext != null ? currentContext.getTenant() : "unknown";

        // TODO: Implement actual DTM asset lookup logic here
        // This is a placeholder implementation
        log.debug("{} - DTM asset lookup requested for: {}", tenant, assetId);

        Map<String, Object> properties = new HashMap<>();
        properties.put("assetId", assetId);
        properties.put("found", false);
        properties.put("message", "DTM lookup not yet implemented");

        // In a real implementation, you would:
        // 1. Call your DTM service/repository
        // 2. Look up the asset properties
        // 3. Return the actual properties

        if (currentContext != null && currentContext.getGraalContext() != null) {
            return currentContext.getGraalContext().asValue(properties);
        } else {
            log.warn("Cannot lookup DTM properties - no current context available");
            return null;
        }
    }

    // Helper logging methods
    private void logInfo(String prefix, Object msg) {
        String tenant = currentContext != null ? currentContext.getTenant() : "unknown";
        if (msg instanceof Value) {
            Value valueMsg = (Value) msg;
            if (valueMsg.isString()) {
                log.info("{} - {}: {}", tenant, prefix, valueMsg.asString());
            } else {
                log.info("{} - {}: {}", tenant, prefix, valueMsg.toString());
            }
        } else {
            log.info("{} - {}: {}", tenant, prefix, msg.toString());
        }
    }

    private void logDebug(String prefix, Object msg) {
        String tenant = currentContext != null ? currentContext.getTenant() : "unknown";
        if (msg instanceof Value) {
            Value valueMsg = (Value) msg;
            if (valueMsg.isString()) {
                log.debug("{} - {}: {}", tenant, prefix, valueMsg.asString());
            } else {
                log.debug("{} - {}: {}", tenant, prefix, valueMsg.toString());
            }
        } else {
            log.debug("{} - {}: {}", tenant, prefix, msg.toString());
        }
    }

    private void logWarn(String prefix, Object msg) {
        String tenant = currentContext != null ? currentContext.getTenant() : "unknown";
        if (msg instanceof Value) {
            Value valueMsg = (Value) msg;
            if (valueMsg.isString()) {
                log.warn("{} - {}: {}", tenant, prefix, valueMsg.asString());
            } else {
                log.warn("{} - {}: {}", tenant, prefix, valueMsg.toString());
            }
        } else {
            log.warn("{} - {}: {}", tenant, prefix, msg.toString());
        }
    }

    private void logError(String prefix, Object msg) {
        String tenant = currentContext != null ? currentContext.getTenant() : "unknown";
        if (msg instanceof Value) {
            Value valueMsg = (Value) msg;
            if (valueMsg.isString()) {
                log.error("{} - {}: {}", tenant, prefix, valueMsg.asString());
            } else {
                log.error("{} - {}: {}", tenant, prefix, valueMsg.toString());
            }
        } else {
            log.error("{} - {}: {}", tenant, prefix, msg.toString());
        }
    }

    // Helper class for JavaScript-callable logging functions
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
                if (arg instanceof Value) {
                    Value valueArg = (Value) arg;
                    if (valueArg.isString()) {
                        message.append(valueArg.asString());
                    } else {
                        message.append(valueArg.toString());
                    }
                } else {
                    message.append(arg != null ? arg.toString() : "null");
                }
            }

            switch (level) {
                case "info":
                    logInfo("JS", message.toString());
                    break;
                case "debug":
                    logDebug("JS", message.toString());
                    break;
                case "warn":
                    logWarn("JS", message.toString());
                    break;
                case "error":
                    logError("JS", message.toString());
                    break;
                default:
                    logInfo("JS", message.toString());
                    break;
            }
        }
    }
}