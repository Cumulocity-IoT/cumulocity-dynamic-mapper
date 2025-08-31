package dynamic.mapper.processor.inbound.processor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.joda.time.DateTime;
import org.springframework.stereotype.Component;

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.model.API;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.Substitution;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.inbound.SubstitutionsAsCode;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.model.RepairStrategy;
import dynamic.mapper.processor.model.SubstituteValue;
import dynamic.mapper.processor.model.SubstituteValue.TYPE;
import dynamic.mapper.processor.model.SubstitutionEvaluation;
import lombok.extern.slf4j.Slf4j;

import static com.dashjoin.jsonata.Jsonata.jsonata;

@Slf4j
@Component
public class JSONataExtractionProcessor implements Processor {
    
    @Override
    public void process(Exchange exchange) throws Exception {
        ProcessingContext<Object> context = getProcessingContext(exchange);
        
        try {
            extractFromSource(context);
        } catch (Exception e) {
            log.error("Error in extraction processor for mapping: {}", 
                context.getMapping().getName(), e);
            context.addError(new ProcessingException("Extraction failed", e));
        }
        
        exchange.getIn().setHeader("processingContext", context);
    }
    
    /**
     * EXACT copy of BaseProcessorInbound.extractFromSource - DO NOT MODIFY!
     */
    public void extractFromSource(ProcessingContext<Object> context)
            throws ProcessingException {
        Mapping mapping = context.getMapping();
        if (!mapping.isSubstitutionsAsCode()) {
            String tenant = context.getTenant();
            ServiceConfiguration serviceConfiguration = context.getServiceConfiguration();

            Object payloadObject = context.getPayload();
            Map<String, List<SubstituteValue>> processingCache = context.getProcessingCache();

            String payload = toPrettyJsonString(payloadObject);
            if (serviceConfiguration.isLogPayload() || mapping.getDebug()) {
                log.info("{} - Patched payload: {}", tenant, payload);
            }

            boolean substitutionTimeExists = false;
            for (Substitution substitution : mapping.getSubstitutions()) {
                Object extractedSourceContent = null;
                /*
                 * step 1 extract content from inbound payload
                 */
                try {
                    var expr = jsonata(substitution.getPathSource());
                    extractedSourceContent = expr.evaluate(payloadObject);
                } catch (Exception e) {
                    log.error("{} - Exception for: {}, {}: ", tenant, substitution.getPathSource(),
                            payload, e);
                }
                /*
                 * step 2 analyze extracted content: textual, array
                 */
                List<SubstituteValue> processingCacheEntry = processingCache.getOrDefault(
                        substitution.getPathTarget(),
                        new ArrayList<>());

                if (extractedSourceContent != null && SubstitutionEvaluation.isArray(extractedSourceContent)
                        && substitution.isExpandArray()) {
                    // extracted result from sourcePayload is an array, so we potentially have to
                    // iterate over the result, e.g. creating multiple devices
                    for (Object jn : (Collection) extractedSourceContent) {
                        SubstitutionEvaluation.processSubstitute(tenant, processingCacheEntry, jn,
                                substitution, mapping);
                    }
                } else {
                    SubstitutionEvaluation.processSubstitute(tenant, processingCacheEntry, extractedSourceContent,
                            substitution, mapping);
                }
                processingCache.put(substitution.getPathTarget(), processingCacheEntry);
                if (serviceConfiguration.isLogSubstitution() || mapping.getDebug()) {
                    log.debug("{} - Evaluated substitution (pathSource:substitute)/({}: {}), (pathTarget)/({})",
                            tenant,
                            substitution.getPathSource(),
                            extractedSourceContent == null ? null : extractedSourceContent.toString(),
                            substitution.getPathTarget());
                }

                if (substitution.getPathTarget().equals(Mapping.KEY_TIME)) {
                    substitutionTimeExists = true;
                }
            }

            // no substitution for the time property exists, then use the system time
            if (!substitutionTimeExists && mapping.getTargetAPI() != API.INVENTORY && mapping.getTargetAPI() != API.OPERATION) {
                List<SubstituteValue> processingCacheEntry = processingCache.getOrDefault(
                        Mapping.KEY_TIME,
                        new ArrayList<>());
                processingCacheEntry.add(
                        new SubstituteValue(new DateTime().toString(),
                                TYPE.TEXTUAL, RepairStrategy.CREATE_IF_MISSING, false));
                processingCache.put(Mapping.KEY_TIME, processingCacheEntry);
            }
        } else {
            SubstitutionsAsCode.extractFromSource(context);
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
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(payloadObject);
            
        } catch (Exception e) {
            log.warn("Failed to convert payload to pretty JSON string: {}", e.getMessage());
            return payloadObject != null ? payloadObject.toString() : "null";
        }
    }
    
    @SuppressWarnings("unchecked")
    private ProcessingContext<Object> getProcessingContext(Exchange exchange) {
        return exchange.getIn().getHeader("processingContext", ProcessingContext.class);
    }
}