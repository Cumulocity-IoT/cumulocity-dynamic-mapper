package dynamic.mapper.processor.inbound.processor;

import static dynamic.mapper.model.Substitution.toPrettyJsonString;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import org.apache.camel.Exchange;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.springframework.stereotype.Component;

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.MappingStatus;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.flow.CumulocityObject;
import dynamic.mapper.processor.flow.DeviceMessage;
import dynamic.mapper.processor.flow.JavaScriptConsole;
import dynamic.mapper.processor.flow.DataPrepContext;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.util.JavaScriptInteropHelper;
import dynamic.mapper.service.MappingService;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class FlowProcessorInboundProcessor extends BaseProcessor {

    private final MappingService mappingService;

    public FlowProcessorInboundProcessor(MappingService mappingService) {
        this.mappingService = mappingService;
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        ProcessingContext<?> context = exchange.getIn().getHeader("processingContext", ProcessingContext.class);

        String tenant = context.getTenant();
        Mapping mapping = context.getMapping();
        Boolean testing = context.getTesting();

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

            if (e instanceof ProcessingException) {
                context.addError((ProcessingException) e);
            } else {
                context.addError(new ProcessingException(errorMessage, e));
            }

            if (!testing) {
                MappingStatus mappingStatus = mappingService.getMappingStatus(tenant, mapping);
                mappingStatus.errors++;
                mappingService.increaseAndHandleFailureCount(tenant, mapping, mappingStatus);
            }
        } finally {
            // Close the Context completely
            if (context != null) {
                try {
                    context.close();
                } catch (Exception e) {
                    log.warn("{} - Error closing context in finally block: {}", tenant, e.getMessage());
                }
            }
        }
    }

    public void processSmartMapping(ProcessingContext<?> context) throws ProcessingException {
        String tenant = context.getTenant();
        Mapping mapping = context.getMapping();
        ServiceConfiguration serviceConfiguration = context.getServiceConfiguration();

        Object payloadObject = context.getPayload();

        if (serviceConfiguration.getLogPayload() || mapping.getDebug()) {
            String payload = toPrettyJsonString(payloadObject); // is this and this required?
            log.info("{} - Incoming payload (patched) in onMessage(): {} {} {} {}", tenant,
                    payload,
                    serviceConfiguration.getLogPayload(), mapping.getDebug(),
                    serviceConfiguration.getLogPayload() || mapping.getDebug());
        }

        if (mapping.getCode() != null) {
            Context graalContext = context.getGraalContext();

            // Explicitly null out GraalVM Value references
            Value bindings = null;
            Value onMessageFunction = null;
            Value inputMessage = null;
            Value result = null;

            try {
                // Task 1: Invoking JavaScript function
                String identifier = Mapping.SMART_FUNCTION_NAME + "_" + mapping.getIdentifier();
                bindings = graalContext.getBindings("js");

                if (context.getFlowContext() != null && context.getFlowContext().getTesting()) {
                    JavaScriptConsole console = new JavaScriptConsole(context.getFlowContext(), tenant);
                    bindings.putMember("console", console);
                }

                // Load and execute the JavaScript code
                byte[] decodedBytes = Base64.getDecoder().decode(mapping.getCode());
                String decodedCode = new String(decodedBytes);
                String decodedCodeAdapted = decodedCode.replaceFirst("onMessage", identifier);

                Source source = Source.newBuilder("js", decodedCodeAdapted, identifier + ".js")
                        .buildLiteral();
                graalContext.eval(source);

                // Load shared and system code if available
                loadSharedCode(graalContext, context);
                loadSystemCode(graalContext, context);

                onMessageFunction = bindings.getMember(identifier);
                inputMessage = createInputMessage(graalContext, context);

                // Execute the JavaScript function
                result = onMessageFunction.execute(inputMessage, context.getFlowContext());

                // Task 2: Extracting the result
                processResult(result, context, tenant);

            } finally {
                // Explicitly null out GraalVM Value references
                bindings = null;
                onMessageFunction = null;
                inputMessage = null;
                result = null;
            }
        }
    }

    private void loadSharedCode(Context graalContext, ProcessingContext<?> context) {
        if (context.getSharedCode() != null) {
            Source sharedSource = null;
            try {
                byte[] decodedSharedCodeBytes = Base64.getDecoder().decode(context.getSharedCode());
                String decodedSharedCode = new String(decodedSharedCodeBytes);
                sharedSource = Source.newBuilder("js", decodedSharedCode, "sharedCode.js")
                        .buildLiteral();
                graalContext.eval(sharedSource);
            } finally {
                // CRITICAL FIX: Clear source reference
                sharedSource = null;
            }
        }
    }

    private void loadSystemCode(Context graalContext, ProcessingContext<?> context) {
        if (context.getSystemCode() != null) {
            Source systemSource = null;
            try {
                byte[] decodedSystemCodeBytes = Base64.getDecoder().decode(context.getSystemCode());
                String decodedSystemCode = new String(decodedSystemCodeBytes);
                systemSource = Source.newBuilder("js", decodedSystemCode, "systemCode.js")
                        .buildLiteral();
                graalContext.eval(systemSource);
            } finally {
                // CRITICAL FIX: Clear source reference
                systemSource = null;
            }
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

        extractWarnings(context, tenant);

        extractLogs(context, tenant);

        // Check if result is null or undefined
        if (result == null || result.isNull()) {
            log.warn("{} - onMessage function did not return any transformation result (null)", tenant);
            context.getWarnings().add("onMessage function did not return any transformation result");
            context.setFlowResult(new ArrayList<>());
            context.setIgnoreFurtherProcessing(true);
            return;
        }

        List<Object> outputMessages = new ArrayList<>();

        try {
            // Handle both array and single object returns
            if (result.hasArrayElements()) {
                processArrayResult(result, outputMessages, tenant);
            } else if (result.hasMembers()) {
                processSingleObjectResult(result, outputMessages, tenant);
            } else {
                log.warn("{} - onMessage function returned unexpected result type: {} ({})",
                        tenant, result.getClass().getSimpleName(), result.getMetaObject());
                context.getWarnings()
                        .add("onMessage function returned unexpected result type: " + result.getMetaObject());
                context.setFlowResult(new ArrayList<>());
                context.setIgnoreFurtherProcessing(true);
                return;
            }
        } catch (Exception e) {
            log.error("{} - Error processing onMessage result: {}", tenant, e.getMessage(), e);
            context.getWarnings().add("Error processing onMessage result: " + e.getMessage());
            context.setFlowResult(new ArrayList<>());
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

        if (context.getMapping().getDebug() || context.getServiceConfiguration().getLogPayload()) {
            log.info("{} - onMessage function returned {} complete message(s)", tenant, outputMessages.size());
        }
    }

    /**
     * Extract warnings from the flow context.
     */
    private void extractWarnings(ProcessingContext<?> context, String tenant) {
        Value warnings = null;
        try {
            warnings = context.getFlowContext().getState(DataPrepContext.WARNINGS);
            if (warnings != null && warnings.hasArrayElements()) {
                List<String> warningList = new ArrayList<>();
                long size = warnings.getArraySize();

                for (long i = 0; i < size; i++) {
                    Value warningElement = null;
                    try {
                        warningElement = warnings.getArrayElement(i);
                        if (warningElement != null && warningElement.isString()) {
                            warningList.add(warningElement.asString());
                        }
                    } finally {
                        warningElement = null;
                    }
                }

                context.setWarnings(warningList);
                log.debug("{} - Collected {} warning(s) from flow execution", tenant, warningList.size());
            }
        } finally {
            warnings = null;
        }
    }

    /**
     * Extract warnings from the flow context.
     */
    private void extractLogs(ProcessingContext<?> context, String tenant) {
        Value logs = null;
        try {
            logs = context.getFlowContext().getState(DataPrepContext.LOGS);
            if (logs != null && logs.hasArrayElements()) {
                List<String> logList = new ArrayList<>();
                long size = logs.getArraySize();

                for (long i = 0; i < size; i++) {
                    Value logElement = null;
                    try {
                        logElement = logs.getArrayElement(i);
                        if (logElement != null && logElement.isString()) {
                            logList.add(logElement.asString());
                        }
                    } finally {
                        logElement = null;
                    }
                }

                context.setLogs(logList);
                log.debug("{} - Collected {} logs from flow execution", tenant, logList.size());
            }
        } finally {
            logs = null;
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
            Value element = null;
            try {
                element = result.getArrayElement(i);
                processResultElement(element, outputMessages, tenant);
            } finally {
                // CRITICAL FIX: Clear element reference in each iteration
                element = null;
            }
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
            CumulocityObject cumulocityObj = JavaScriptInteropHelper.convertToCumulocityObject(element);
            outputMessages.add(cumulocityObj);
            log.debug("{} - Processed CumulocityObject: type={}",
                    tenant, cumulocityObj.getCumulocityType());
        } catch (Exception e) {
            log.error("{} - Error processing result element: {}", tenant, e.getMessage(), e);
        }
    }

}