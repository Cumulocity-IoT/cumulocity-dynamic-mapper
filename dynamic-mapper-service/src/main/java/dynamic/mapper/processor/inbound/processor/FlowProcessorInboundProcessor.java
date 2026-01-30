package dynamic.mapper.processor.inbound.processor;

import java.util.ArrayList;
import java.util.List;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.springframework.stereotype.Component;

import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.MappingStatus;
import dynamic.mapper.processor.AbstractFlowProcessor;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.model.CumulocityObject;
import dynamic.mapper.processor.model.DeviceMessage;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.util.JavaScriptInteropHelper;
import dynamic.mapper.service.MappingService;
import lombok.extern.slf4j.Slf4j;

/**
 * Inbound FlowProcessor that executes JavaScript smart functions
 * to transform device messages into Cumulocity objects.
 */
@Slf4j
@Component
public class FlowProcessorInboundProcessor extends AbstractFlowProcessor {

    public FlowProcessorInboundProcessor(MappingService mappingService) {
        super(mappingService);
    }

    @Override
    protected String getProcessorName() {
        return "FlowProcessorInboundProcessor";
    }

    @Override
    protected Value createInputMessage(Context graalContext, ProcessingContext<?> context) {
        // Create a DeviceMessage from the current context using builder pattern
        DeviceMessage.Builder builder = DeviceMessage.create()
            .payload(context.getPayload())
            .topic(context.getTopic());

        // Set transport information if available
        if (context.getMapping() != null) {
            builder.clientId(context.getClientId());
        }

        DeviceMessage deviceMessage = builder.build();

        // Convert to JavaScript object
        return graalContext.asValue(deviceMessage);
    }

    @Override
    protected void processResult(Value result, ProcessingContext<?> context, String tenant) {
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

    @Override
    protected void handleProcessingError(Exception e, String errorMessage,
            ProcessingContext<?> context, String tenant, Mapping mapping) {
        if (e instanceof ProcessingException) {
            context.addError((ProcessingException) e);
        } else {
            context.addError(new ProcessingException(errorMessage, e));
        }

        if (!context.getTesting()) {
            MappingStatus mappingStatus = mappingService.getMappingStatus(tenant, mapping);
            mappingStatus.errors++;
            mappingService.increaseAndHandleFailureCount(tenant, mapping, mappingStatus);
        }
    }

    /**
     * Process an array result from JavaScript function.
     */
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

    /**
     * Process a single object result from JavaScript function.
     */
    private void processSingleObjectResult(Value result, List<Object> outputMessages, String tenant) {
        log.debug("{} - Processing single object result", tenant);
        processResultElement(result, outputMessages, tenant);
    }

    /**
     * Process a single result element and add it to the output list.
     * Handles CumulocityObject types for inbound processing.
     */
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
