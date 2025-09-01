package dynamic.mapper.processor.outbound.processor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.camel.Exchange;
import com.cumulocity.model.idtype.GId;
import com.cumulocity.rest.representation.identity.ExternalIDRepresentation;
import com.fasterxml.jackson.databind.ObjectMapper;

import dynamic.mapper.core.C8YAgent;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.model.ProcessingContext;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class EnrichmentProcessor extends BaseProcessor {
    
    private C8YAgent c8yAgent;
    
    private ObjectMapper objectMapper;
    
    @Override
    public void process(Exchange exchange) throws Exception {
        ProcessingContext<Object> context = getProcessingContextAsObject(exchange);
        
        try {
            enrichPayload(context);
        } catch (Exception e) {
            log.error("Error in outbound enrichment processor for mapping: {}", 
                context.getMapping().getName(), e);
            context.addError(new ProcessingException("Outbound enrichment failed", e));
        }
        
        exchange.getIn().setHeader("processingContext", context);
    }
    
    /**
     * EXACT copy of BaseProcessorOutbound.enrichPayload - DO NOT MODIFY!
     */
    public void enrichPayload(ProcessingContext<Object> context) {

        /*
         * step 0 patch payload with dummy property _IDENTITY_ in case the content
         * is required in the payload for a substitution
         */
        String tenant = context.getTenant();
        Object payloadObject = context.getPayload();
        Mapping mapping = context.getMapping();
        String payloadAsString = toPrettyJsonString(payloadObject);
        var sourceId = extractContent(context, mapping, payloadObject, payloadAsString,
                mapping.getTargetAPI().identifier);
        context.setSourceId(sourceId.toString());
        Map<String, String> identityFragment = new HashMap<>();
        identityFragment.put("c8ySourceId", sourceId.toString());
        identityFragment.put("externalIdType", mapping.getExternalIdType());
        if (mapping.getUseExternalId() && !("").equals(mapping.getExternalIdType())) {
            ExternalIDRepresentation externalId = c8yAgent.resolveGlobalId2ExternalId(context.getTenant(),
                    new GId(sourceId.toString()), mapping.getExternalIdType(),
                    context);
            if (externalId == null) {
                if (context.isSendPayload()) {
                    throw new RuntimeException(String.format("External id %s for type %s not found!",
                            sourceId.toString(), mapping.getExternalIdType()));
                }
            } else {
                identityFragment.put("externalId", externalId.getExternalId());
            }
        }
        if (payloadObject instanceof Map) {
            ((Map) payloadObject).put(Mapping.TOKEN_IDENTITY, identityFragment);
            List<String> splitTopicExAsList = Mapping.splitTopicExcludingSeparatorAsList(context.getTopic(), false);
            ((Map) payloadObject).put(Mapping.TOKEN_TOPIC_LEVEL, splitTopicExAsList);
        } else {
            log.warn("{} - Parsing this message as JSONArray, no elements from the topic level can be used!",
                    tenant);
        }
    }
    
    /**
     * Convert payload object to pretty JSON string for logging
     */
    private String toPrettyJsonString(Object payloadObject) {
        try {
            if (payloadObject == null) {
                return "null";
            }
            
            if (payloadObject instanceof String) {
                return (String) payloadObject;
            }
            
            // Use ObjectMapper to convert to pretty JSON
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payloadObject);
            
        } catch (Exception e) {
            log.warn("Failed to convert payload to pretty JSON string: {}", e.getMessage());
            return payloadObject != null ? payloadObject.toString() : "null";
        }
    }
    
}