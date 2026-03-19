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
import dynamic.mapper.processor.model.OutputCollector;
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
        // Use InputMessage (no Lombok) so GraalVM's allowPublicAccess(true) reliably exposes
        // getPayload(), getTopic(), getClientId() to JavaScript as msg.getPayload() etc.
        return graalContext.asValue(new dynamic.mapper.processor.model.InputMessage(
                context.getPayload(),
                context.getTopic(),
                context.getClientId(),
                null));
    }

    @Override
    protected void processResult(Value result, ProcessingContext<?> context, String tenant) {
        // Use thread-safe OutputCollector internally
        OutputCollector output = new OutputCollector();

        // Extract warnings and logs using thread-safe methods
        extractWarnings(context.getFlowContext(), output, tenant);
        extractLogs(context.getFlowContext(), output, tenant);

        // Check if result is null or undefined
        if (result == null || result.isNull()) {
            log.warn("{} - onMessage function did not return any transformation result (null)", tenant);
            output.addWarning("onMessage function did not return any transformation result");
            context.setFlowResult(new ArrayList<>());
            context.setIgnoreFurtherProcessing(true);

            // Sync back to context for backward compatibility
            syncOutputToContext(output, context);
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
                output.addWarning("onMessage function returned unexpected result type: " + result.getMetaObject());
                context.setFlowResult(new ArrayList<>());
                context.setIgnoreFurtherProcessing(true);

                // Sync back to context for backward compatibility
                syncOutputToContext(output, context);
                return;
            }
        } catch (Exception e) {
            log.error("{} - Error processing onMessage result: {}", tenant, e.getMessage(), e);
            output.addWarning("Error processing onMessage result: " + e.getMessage());
            context.setFlowResult(new ArrayList<>());
            context.setIgnoreFurtherProcessing(true);

            // Sync back to context for backward compatibility
            syncOutputToContext(output, context);
            return;
        }

        // Always set flow result, even if empty
        context.setFlowResult(outputMessages);

        if (outputMessages.isEmpty()) {
            log.info("{} - No valid messages produced from onMessage function", tenant);
            output.addWarning("No valid messages produced from onMessage function");
            context.setIgnoreFurtherProcessing(true);

            // Sync back to context for backward compatibility
            syncOutputToContext(output, context);
            return;
        }

        if (context.getMapping().getDebug() || context.getServiceConfiguration().getLogPayload()) {
            log.info("{} - onMessage function returned {} complete message(s)", tenant, outputMessages.size());
        }

        // Sync back to context for backward compatibility
        syncOutputToContext(output, context);
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

    /**
     * Sync OutputCollector contents back to ProcessingContext for backward compatibility.
     * Can be removed once all callers migrate to reading from OutputCollector directly.
     */
    private void syncOutputToContext(OutputCollector output, ProcessingContext<?> context) {
        if (!output.getWarnings().isEmpty()) {
            context.getWarnings().addAll(output.getWarnings());
        }
        if (!output.getLogs().isEmpty()) {
            context.getLogs().addAll(output.getLogs());
        }
    }
}
