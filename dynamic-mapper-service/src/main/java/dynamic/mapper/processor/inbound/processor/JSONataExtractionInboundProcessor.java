package dynamic.mapper.processor.inbound.processor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.apache.camel.Exchange;
import org.joda.time.DateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import static dynamic.mapper.model.Substitution.toPrettyJsonString;
import static com.dashjoin.jsonata.Jsonata.jsonata;

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.model.API;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.MappingStatus;
import dynamic.mapper.model.Substitution;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.model.RepairStrategy;
import dynamic.mapper.processor.model.SubstituteValue;
import dynamic.mapper.processor.model.SubstituteValue.TYPE;
import dynamic.mapper.processor.model.SubstitutionEvaluation;
import dynamic.mapper.service.MappingService;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class JSONataExtractionInboundProcessor extends BaseProcessor {

    @Autowired
    private MappingService mappingService;

    @Override
    public void process(Exchange exchange) throws Exception {
        ProcessingContext<Object> context = exchange.getIn().getHeader("processingContext", ProcessingContext.class);

        String tenant = context.getTenant();
        Mapping mapping = context.getMapping();
        Boolean testing = context.isTesting();

        try {
            extractFromSource(context);
        } catch (Exception e) {
            String errorMessage = String.format(
                    "%s - Error in JSONataExtractionInboundProcessor for mapping: %s,",
                    tenant, mapping.getName());
            log.error(errorMessage, e);
            if(e instanceof ProcessingException)
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

    public void extractFromSource(ProcessingContext<Object> context)
            throws ProcessingException {
        try {
            Mapping mapping = context.getMapping();
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
            if (!substitutionTimeExists && mapping.getTargetAPI() != API.INVENTORY
                    && mapping.getTargetAPI() != API.OPERATION) {
                List<SubstituteValue> processingCacheEntry = processingCache.getOrDefault(
                        Mapping.KEY_TIME,
                        new ArrayList<>());
                processingCacheEntry.add(
                        new SubstituteValue(new DateTime().toString(),
                                TYPE.TEXTUAL, RepairStrategy.CREATE_IF_MISSING, false));
                processingCache.put(Mapping.KEY_TIME, processingCacheEntry);
            }
        } catch (Exception e) {
            throw new ProcessingException(e.getMessage());
        }
    }

}