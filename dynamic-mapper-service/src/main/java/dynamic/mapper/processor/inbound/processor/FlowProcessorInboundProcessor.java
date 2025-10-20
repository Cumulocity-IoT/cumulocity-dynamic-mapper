package dynamic.mapper.processor.inbound.processor;

import static dynamic.mapper.model.Substitution.toPrettyJsonString;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

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
public class FlowProcessorInboundProcessor extends BaseProcessor {

    @Autowired
    private MappingService mappingService;

    @Override
    public void process(Exchange exchange) throws Exception {
        ProcessingContext<?> context = exchange.getIn().getHeader("processingContext", ProcessingContext.class);

        Mapping mapping = context.getMapping();
        String tenant = context.getTenant();
        Boolean testing = context.isTesting();

        try {
            processSmartMapping(context);
        } catch (Exception e) {
            int lineNumber = 0;
            if (e.getStackTrace().length > 0) {
                lineNumber = e.getStackTrace()[0].getLineNumber();
            }
            String errorMessage = String.format(
                    "%s - Error in FlowProcessorInboundProcessor: %s for mapping: %s, line %s",
                    tenant, mapping.getName(), e.getMessage(), lineNumber);
            log.error(errorMessage, e);
            if (e instanceof ProcessingException)
                context.addError((ProcessingException) e);
            else
                context.addError(new ProcessingException(errorMessage, e));
            if (!testing) {
                MappingStatus mappingStatus = mappingService.getMappingStatus(tenant, mapping);
                mappingStatus.errors++;
                mappingService.increaseAndHandleFailureCount(tenant, mapping, mappingStatus);
            }
            return;
        }
    }

    public void processSmartMapping(ProcessingContext<?> context) throws ProcessingException {
        String tenant = context.getTenant();
        Mapping mapping = context.getMapping();
        ServiceConfiguration serviceConfiguration = context.getServiceConfiguration();

        Object payloadObject = context.getPayload();

        String payload = toPrettyJsonString(payloadObject);
        if (serviceConfiguration.isLogPayload() || mapping.getDebug()) {
            log.info("{} - Processing payload: {}", tenant, payload);
        }

        if (mapping.getCode() != null) {
            Context graalContext = context.getGraalContext();

            // Task 1: Invoking JavaScript function
            String identifier = Mapping.SMART_FUNCTION_NAME + "_" + mapping.getIdentifier();
            Value bindings = graalContext.getBindings("js");

            // Load and execute the JavaScript code
            byte[] decodedBytes = Base64.getDecoder().decode(mapping.getCode());
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
            final Value result = onMessageFunction.execute(inputMessage, context.getFlowContext());

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
            deviceMessage.setClientId(context.getClientId());
        }

        // Convert to JavaScript object
        return graalContext.asValue(deviceMessage);
    }

    private void processResult(Value result, ProcessingContext<?> context, String tenant) {
        // Collect warnings
        Value warnings = context.getFlowContext().getState(FlowContext.WARNINGS);
        if (warnings != null && warnings.hasArrayElements()) {
            List<String> warningList = new ArrayList<>();
            long size = warnings.getArraySize();
            for (long i = 0; i < size; i++) {
                Value warningElement = warnings.getArrayElement(i);
                if (warningElement != null && warningElement.isString()) {
                    warningList.add(warningElement.asString());
                }
            }
            context.setWarnings(warningList);
            log.debug("{} - Collected {} warnings from flow execution", tenant, warningList.size());
        }

        // Collect logs
        Value logs = context.getFlowContext().getState(FlowContext.LOGS);
        if (logs != null && logs.hasArrayElements()) {
            List<String> logList = new ArrayList<>();
            long size = logs.getArraySize();
            for (long i = 0; i < size; i++) {
                Value logElement = logs.getArrayElement(i);
                if (logElement != null && logElement.isString()) {
                    logList.add(logElement.asString());
                }
            }
            context.setLogs(logList);
            log.debug("{} - Collected {} logs from flow execution", tenant, logList.size());
        }

        // Check if result is null or undefined
        if (result == null || result.isNull()) {
            log.warn("{} - onMessage function did not return any transformation result (null)", tenant);
            context.getWarnings().add("onMessage function did not return any transformation result");
            context.setFlowResult(new ArrayList<>()); // Set empty list
            context.setIgnoreFurtherProcessing(true);
            return;
        }

        List<Object> outputMessages = new ArrayList<>();

        try {
            // Handle both array and single object returns
            if (result.hasArrayElements()) {
                // Result is an array: [{...}, {...}]
                processArrayResult(result, outputMessages, tenant);

            } else if (result.hasMembers()) {
                // Result is a single object: {...}
                processSingleObjectResult(result, outputMessages, tenant);

            } else {
                // Unexpected type (primitive, function, etc.)
                log.warn("{} - onMessage function returned unexpected result type: {} ({})",
                        tenant, result.getClass().getSimpleName(), result.getMetaObject());
                context.getWarnings()
                        .add("onMessage function returned unexpected result type: " + result.getMetaObject());
                context.setFlowResult(new ArrayList<>()); // Set empty list
                context.setIgnoreFurtherProcessing(true);
                return;
            }

        } catch (Exception e) {
            log.error("{} - Error processing onMessage result: {}", tenant, e.getMessage(), e);
            context.getWarnings().add("Error processing onMessage result: " + e.getMessage());
            context.setFlowResult(new ArrayList<>()); // Set empty list
            context.setIgnoreFurtherProcessing(true);
            return;
        }

        // Always set flow result, even if empty
        context.setFlowResult(outputMessages);

        if (outputMessages.isEmpty()) {
            log.info("{} - No valid messages produced from onMessage function", tenant);
            context.getWarnings().add("No valid messages produced from onMessage function");
            context.setIgnoreFurtherProcessing(true);
            return;
        }

        if (context.getMapping().getDebug() || context.getServiceConfiguration().isLogPayload()) {
            log.info("{} - onMessage function returned {} complete message(s)", tenant, outputMessages.size());
        }
    }

    private void processArrayResult(Value result, List<Object> outputMessages, String tenant) {
        long arraySize = result.getArraySize();

        if (arraySize == 0) {
            log.info("{} - onMessage function returned empty array", tenant);
            return;
        }

        log.debug("{} - Processing array result with {} elements", tenant, arraySize);

        for (int i = 0; i < arraySize; i++) {
            Value element = result.getArrayElement(i);
            processResultElement(element, outputMessages, tenant);
        }
    }

    private void processSingleObjectResult(Value result, List<Object> outputMessages, String tenant) {
        log.debug("{} - Processing single object result", tenant);
        processResultElement(result, outputMessages, tenant);
    }

    private void processResultElement(Value element, List<Object> outputMessages, String tenant) {
        if (element == null || element.isNull()) {
            log.debug("{} - Skipping null element", tenant);
            return;
        }

        try {
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
                log.warn("{} - Unknown message type returned from onMessage: {} with members: {}",
                        tenant, element.getMetaObject(),
                        element.hasMembers() ? element.getMemberKeys() : "N/A");
            }
        } catch (Exception e) {
            log.error("{} - Error processing result element: {}", tenant, e.getMessage(), e);
        }
    }

}