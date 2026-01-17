package dynamic.mapper.processor.inbound.processor;

import static com.dashjoin.jsonata.Jsonata.jsonata;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.joda.time.DateTime;
import org.springframework.stereotype.Component;

import dynamic.mapper.model.API;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.MappingStatus;
import dynamic.mapper.model.Substitution;
import dynamic.mapper.processor.AbstractJSONataExtractionProcessor;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.model.RepairStrategy;
import dynamic.mapper.processor.model.SubstituteValue;
import dynamic.mapper.processor.model.SubstituteValue.TYPE;
import dynamic.mapper.service.MappingService;
import lombok.extern.slf4j.Slf4j;

/**
 * Inbound JSONata extraction processor that extracts and processes substitutions
 * from device payloads using JSONata expressions.
 *
 * Includes special handling for time substitutions - if no time substitution is provided
 * and the target API requires time, system time is automatically added.
 */
@Slf4j
@Component
public class JSONataExtractionInboundProcessor extends AbstractJSONataExtractionProcessor {

    public JSONataExtractionInboundProcessor(MappingService mappingService) {
        super(mappingService);
    }

    @Override
    protected Object extractContentFromPayload(ProcessingContext<?> context,
                                              Substitution substitution,
                                              Object payloadObject,
                                              String payloadAsString) {
        Object extractedSourceContent = null;
        try {
            var expr = jsonata(substitution.getPathSource());
            extractedSourceContent = expr.evaluate(payloadObject);
        } catch (Exception e) {
            log.error("{} - Exception for: {}, {}: ", context.getTenant(),
                    substitution.getPathSource(), payloadAsString, e);
        }
        return extractedSourceContent;
    }

    @Override
    protected void postProcessSubstitutions(ProcessingContext<?> context,
                                           Map<String, List<SubstituteValue>> processingCache)
            throws ProcessingException {
        Mapping mapping = context.getMapping();

        // Check if a time substitution exists
        boolean substitutionTimeExists = processingCache.containsKey(Mapping.KEY_TIME);

        // If no substitution for the time property exists, then use the system time
        // (for APIs that require time: MEASUREMENT, EVENT, ALARM)
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
    }

    @Override
    protected void handleProcessingError(Exception e, ProcessingContext<?> context, String tenant, Mapping mapping) {
        String errorMessage = String.format(
                "%s - Error in JSONataExtractionInboundProcessor for mapping: %s,",
                tenant, mapping.getName());
        log.error(errorMessage, e);

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

}