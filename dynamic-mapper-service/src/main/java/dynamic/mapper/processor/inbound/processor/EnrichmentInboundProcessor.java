package dynamic.mapper.processor.inbound.processor;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.camel.Exchange;
import org.graalvm.polyglot.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.cumulocity.sdk.client.ProcessingMode;

import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.MappingStatus;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.model.TransformationType;
import dynamic.mapper.processor.flow.FlowContext;
import dynamic.mapper.service.MappingService;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class EnrichmentInboundProcessor extends BaseProcessor {

    @Autowired
    private MappingService mappingService;

    @Override
    public void process(Exchange exchange) throws Exception {
        ProcessingContext<Object> context = exchange.getIn().getHeader("processingContext", ProcessingContext.class);
        String tenant = context.getTenant();
        Mapping mapping = context.getMapping();

        try {
            enrichPayload(context);
        } catch (Exception e) {
            String errorMessage = String.format("%s - Error in EnrichmentInboundProcessor for mapping: %s", tenant,
                    mapping.getName());
            log.error(errorMessage, e);
            MappingStatus mappingStatus = mappingService
                    .getMappingStatus(tenant, mapping);
            context.addError(new ProcessingException(errorMessage, e));
            mappingStatus.errors++;
            mappingService.increaseAndHandleFailureCount(tenant, mapping, mappingStatus);
            return;
        }
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    public void enrichPayload(ProcessingContext<Object> context) {
        /*
         * step 0 patch payload with dummy property _TOPIC_LEVEL_ in case the content
         * is required in the payload for a substitution
         * 
         * Also add enrichment data to FlowContext for JavaScript Flow Functions
         */
        String tenant = context.getTenant();
        Object payloadObject = context.getPayload();
        Mapping mapping = context.getMapping();

        // Process topic levels
        List<String> splitTopicAsList = Mapping.splitTopicExcludingSeparatorAsList(context.getTopic(), false);

        // Add topic levels to FlowContext if available
        FlowContext flowContext = context.getFlowContext();
        if (flowContext != null && context.getGraalContext() != null
                && TransformationType.SMART_FUNCTION.equals(context.getMapping().getTransformationType())) {
            addToFlowContext(flowContext, context, Mapping.TOKEN_TOPIC_LEVEL, splitTopicAsList);

            // Add basic context information
            addToFlowContext(flowContext, context, "tenant", tenant);
            addToFlowContext(flowContext, context, "topic", context.getTopic());
            addToFlowContext(flowContext, context, "client", context.getClientId());
            addToFlowContext(flowContext, context, "mappingName", mapping.getName());
            addToFlowContext(flowContext, context, "mappingId", mapping.getId());
            addToFlowContext(flowContext, context, "targetAPI", mapping.getTargetAPI().toString());
            addToFlowContext(flowContext, context, "genericDeviceIdentifier", mapping.getGenericDeviceIdentifier());
            addToFlowContext(flowContext, context, "debug", mapping.debug);

            if (context.getMapping().getEventWithAttachment()) {
                addToFlowContext(flowContext, context, "attachment_Name", "");
                addToFlowContext(flowContext, context, "attachment_Type", "");
                addToFlowContext(flowContext, context, "attachment_Data", "");
                addToFlowContext(flowContext, context, "eventWithAttachment", true);
            }
            if (context.getMapping().getCreateNonExistingDevice()) {
                addToFlowContext(flowContext, context, "deviceName", context.getDeviceName());
                addToFlowContext(flowContext, context, "deviceType", context.getDeviceType());
                addToFlowContext(flowContext, context, "createNonExistingDevice", true);
            }

        } else if (payloadObject instanceof Map) {
            // Keep original behavior - add to payload
            ((Map) payloadObject).put(Mapping.TOKEN_TOPIC_LEVEL, splitTopicAsList);

            // Process message context
            if (context.isSupportsMessageContext() && context.getKey() != null) {
                String keyString = new String(context.getKey(), StandardCharsets.UTF_8);
                Map<String, String> contextData = new HashMap<String, String>() {
                    {
                        put(Mapping.CONTEXT_DATA_KEY_NAME, keyString);
                        put("api", context.getMapping().getTargetAPI().toString());
                        put("processingMode", ProcessingMode.PERSISTENT.toString());
                        if (context.getMapping().getCreateNonExistingDevice()) {
                            put("deviceName", context.getDeviceName());
                            put("deviceType", context.getDeviceType());
                        }
                    }
                };

                // Add to payload (original behavior)
                ((Map) payloadObject).put(Mapping.TOKEN_CONTEXT_DATA, contextData);
            }

            // Handle attachment properties independently
            if (context.getMapping().getEventWithAttachment()) {
                // Get or create the context data map from payload
                Map<String, String> contextData;
                if (((Map) payloadObject).containsKey(Mapping.TOKEN_CONTEXT_DATA)) {
                    contextData = (Map<String, String>) ((Map) payloadObject).get(Mapping.TOKEN_CONTEXT_DATA);
                } else {
                    contextData = new HashMap<>();
                    ((Map) payloadObject).put(Mapping.TOKEN_CONTEXT_DATA, contextData);
                }

                // Add attachment properties to payload context data
                contextData.put("attachment_Name", "");
                contextData.put("attachment_Type", "");
                contextData.put("attachment_Data", "");
            }

        } else {
            log.info(
                    "{} - This message is not parsed by Base Inbound Processor, will be potentially parsed by extension due to custom format.",
                    tenant);
        }

        if (flowContext != null) {
            log.debug("{} - Enriched FlowContext with payload enrichment data", tenant);
        }
    }

    /**
     * Helper method to safely add values to FlowContext
     */
    private void addToFlowContext(FlowContext flowContext, ProcessingContext<Object> context, String key,
            Object value) {
        try {
            if (context.getGraalContext() != null && value != null) {
                Value graalValue = context.getGraalContext().asValue(value);
                flowContext.setState(key, graalValue);
            }
        } catch (Exception e) {
            log.warn("{} - Failed to add '{}' to FlowContext: {}", context.getTenant(), key, e.getMessage());
        }
    }
}