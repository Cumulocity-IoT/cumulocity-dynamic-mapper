package dynamic.mapper.processor.outbound.processor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.apache.camel.Exchange;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.MappingStatus;
import dynamic.mapper.model.Substitution;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.model.SubstituteValue;
import dynamic.mapper.service.MappingService;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class JSONataExtractionOutboundProcessor extends BaseProcessor {

    @Autowired
    private MappingService mappingService;

    @Override
    public void process(Exchange exchange) throws Exception {
        ProcessingContext<Object> context = getProcessingContextAsObject(exchange);

        String tenant = context.getTenant();
        Mapping mapping = context.getMapping();

        try {
            extractFromSource(context);
        } catch (Exception e) {
            String errorMessage = String.format(
                    "Tenant %s - Error in JSONataExtractionOutboundProcessor for mapping: %s,",
                    tenant, mapping.name);
            log.error(errorMessage, e);
            MappingStatus mappingStatus = mappingService.getMappingStatus(tenant, mapping);
            context.addError(new ProcessingException(errorMessage, e));
            mappingStatus.errors++;
            mappingService.increaseAndHandleFailureCount(tenant, mapping, mappingStatus);
            return;
        }
    }

    /**
     * EXACT copy of BaseProcessorInbound.extractFromSource - DO NOT MODIFY!
     */
    public void extractFromSource(ProcessingContext<Object> context)
            throws ProcessingException {
        Mapping mapping = context.getMapping();
        String tenant = context.getTenant();
        ServiceConfiguration serviceConfiguration = context.getServiceConfiguration();

        Object payloadObject = context.getPayload();

        Map<String, List<SubstituteValue>> processingCache = context.getProcessingCache();
        String payloadAsString = toPrettyJsonString(payloadObject);

        if (serviceConfiguration.logPayload || mapping.debug) {
            log.info("{} - Incoming payload (patched) in extractFromSource(): {} {} {} {}", tenant,
                    payloadAsString,
                    serviceConfiguration.logPayload, mapping.debug,
                    serviceConfiguration.logPayload || mapping.debug);
        }

        for (Substitution substitution : mapping.substitutions) {
            Object extractedSourceContent = null;

            /*
             * step 1 extract content from inbound payload
             */
            extractedSourceContent = extractContent(context, mapping, payloadObject, payloadAsString,
                    substitution.pathSource);
            /*
             * step 2 analyse extracted content: textual, array
             */
            List<SubstituteValue> processingCacheEntry = processingCache.getOrDefault(
                    substitution.pathTarget,
                    new ArrayList<>());

            if (dynamic.mapper.processor.model.SubstitutionEvaluation.isArray(extractedSourceContent)
                    && substitution.expandArray) {
                var extractedSourceContentCollection = (Collection) extractedSourceContent;
                // extracted result from sourcePayload is an array, so we potentially have to
                // iterate over the result, e.g. creating multiple devices
                for (Object jn : extractedSourceContentCollection) {
                    dynamic.mapper.processor.model.SubstitutionEvaluation.processSubstitute(tenant,
                            processingCacheEntry, jn,
                            substitution, mapping);
                }
            } else {
                dynamic.mapper.processor.model.SubstitutionEvaluation.processSubstitute(tenant,
                        processingCacheEntry, extractedSourceContent,
                        substitution, mapping);
            }
            processingCache.put(substitution.pathTarget, processingCacheEntry);

            if (context.getServiceConfiguration().logSubstitution || mapping.debug) {
                String contentAsString = extractedSourceContent != null ? extractedSourceContent.toString()
                        : "null";
                log.debug("{} - Evaluated substitution (pathSource:substitute)/({}: {}), (pathTarget)/({})",
                        context.getTenant(),
                        substitution.pathSource, contentAsString, substitution.pathTarget);
            }
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

}