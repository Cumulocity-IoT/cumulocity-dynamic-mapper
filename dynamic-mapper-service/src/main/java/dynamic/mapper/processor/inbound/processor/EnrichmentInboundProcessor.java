package dynamic.mapper.processor.inbound.processor;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.camel.Exchange;
import org.springframework.stereotype.Component;

import com.cumulocity.sdk.client.ProcessingMode;

import dynamic.mapper.model.Mapping;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.model.ProcessingContext;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class EnrichmentInboundProcessor extends BaseProcessor {
    
    @Override
    public void process(Exchange exchange) throws Exception {
        ProcessingContext<Object> context = exchange.getIn().getHeader("processingContextAsObject", ProcessingContext.class);
        
        try {
            enrichPayload(context);
        } catch (Exception e) {
            log.error("Error in enrichment processor for mapping: {}", 
                context.getMapping().getName(), e);
            context.addError(new ProcessingException("Enrichment failed", e));
        }
        
        exchange.getIn().setHeader("processingContext", context);
    }
    
    /**
     * EXACT copy of BaseProcessorInbound.enrichPayload - DO NOT MODIFY!
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public void enrichPayload(ProcessingContext<Object> context) {
        /*
         * step 0 patch payload with dummy property _TOPIC_LEVEL_ in case the content
         * is required in the payload for a substitution
         */
        String tenant = context.getTenant();
        Object payloadObject = context.getPayload();

        List<String> splitTopicAsList = Mapping.splitTopicExcludingSeparatorAsList(context.getTopic(), false);
        if (payloadObject instanceof Map) {
            ((Map) payloadObject).put(Mapping.TOKEN_TOPIC_LEVEL, splitTopicAsList);
            if (context.isSupportsMessageContext() && context.getKey() != null) {
                String keyString = new String(context.getKey(), StandardCharsets.UTF_8);
                Map contextData = new HashMap<String, String>() {
                    {
                        put(Mapping.CONTEXT_DATA_KEY_NAME, keyString);
                        put("api",
                                context.getMapping().getTargetAPI().toString());
                        put("processingMode",
                                ProcessingMode.PERSISTENT.toString());
                        if (context.getMapping().getCreateNonExistingDevice()) {
                            put("deviceName", context.getDeviceName());
                            put("deviceType", context.getDeviceType());
                        }
                    }
                };
                ((Map) payloadObject).put(Mapping.TOKEN_CONTEXT_DATA, contextData);
            }
            // Handle attachment properties independently
            if (context.getMapping().getEventWithAttachment()) {
                // Get or create the context data map
                Map<String, String> contextData;
                if (((Map) payloadObject).containsKey(Mapping.TOKEN_CONTEXT_DATA)) {
                    contextData = (Map<String, String>) ((Map) payloadObject).get(Mapping.TOKEN_CONTEXT_DATA);
                } else {
                    contextData = new HashMap<>();
                    ((Map) payloadObject).put(Mapping.TOKEN_CONTEXT_DATA, contextData);
                }

                // Add attachment properties
                contextData.put("attachment_Name", "");
                contextData.put("attachment_Type", "");
                contextData.put("attachment_Data", "");
            }
        } else {
            log.info(
                    "{} - This message is not parsed by Base Inbound Processor, will be potentially parsed by extension due to custom format.",
                    tenant);
        }
    }
    

}