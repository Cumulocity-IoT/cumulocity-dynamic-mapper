package dynamic.mapper.processor.inbound.processor;

import static dynamic.mapper.model.Substitution.toPrettyJsonString;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.camel.Exchange;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.joda.time.DateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.dashjoin.jsonata.Functions;

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.model.API;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.MappingStatus;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.model.RepairStrategy;
import dynamic.mapper.processor.model.SubstituteValue;
import dynamic.mapper.processor.model.SubstituteValue.TYPE;
import dynamic.mapper.processor.model.SubstitutionContext;
import dynamic.mapper.processor.model.SubstitutionEvaluation;
import dynamic.mapper.processor.model.SubstitutionResult;
import dynamic.mapper.service.MappingService;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class CodeExtractionInboundProcessor extends BaseProcessor {

    @Autowired
    private MappingService mappingService;

    @Override
    public void process(Exchange exchange) throws Exception {
        ProcessingContext<?> context = exchange.getIn().getHeader("processingContext", ProcessingContext.class);
        Mapping mapping = context.getMapping();
        String tenant = context.getTenant();
        Boolean testing = context.isTesting();

        try {
            extractFromSource(context);
        } catch (Exception e) {
            int lineNumber = 0;
            if (e.getStackTrace().length > 0) {
                lineNumber = e.getStackTrace()[0].getLineNumber();
            }
            String errorMessage = String.format(
                    "%s - Error in CodeExtractionInboundProcessor: %s for mapping: %s, line %s",
                    tenant, mapping.getName(), e.getMessage(), lineNumber);
            log.error(errorMessage, e);
            if(e instanceof ProcessingException)
                context.addError((ProcessingException) e);
            else
                context.addError(new ProcessingException(errorMessage, e));

            if ( !testing) {
                MappingStatus mappingStatus = mappingService.getMappingStatus(tenant, mapping);
                mappingStatus.errors++;
                mappingService.increaseAndHandleFailureCount(tenant, mapping, mappingStatus);
            } 
            return;
        }
    }

    /**
     * EXACT copy of BaseProcessorInbound.extractFromSource - DO NOT MODIFY!
     */
    public void extractFromSource(ProcessingContext<?> context)
            throws ProcessingException {
        String tenant = context.getTenant();
        Mapping mapping = context.getMapping();
        ServiceConfiguration serviceConfiguration = context.getServiceConfiguration();

        Object payloadObject = context.getPayload();
        Map<String, List<SubstituteValue>> processingCache = context.getProcessingCache();

        String payload = toPrettyJsonString(payloadObject);
        if (serviceConfiguration.isLogPayload() || mapping.getDebug()) {
            log.info("{} - Patched payload: {}", tenant, payload);
        }

        boolean substitutionTimeExists = false;

        if (mapping.getCode() != null) {

            Context graalContext = context.getGraalContext();

            String identifier = Mapping.EXTRACT_FROM_SOURCE + "_" + mapping.getIdentifier();
            Value bindings = graalContext.getBindings("js");

            byte[] decodedBytes = Base64.getDecoder().decode(mapping.getCode());
            String decodedCode = new String(decodedBytes);
            String decodedCodeAdapted = decodedCode.replaceFirst(
                    Mapping.EXTRACT_FROM_SOURCE,
                    identifier);
            Source source = Source.newBuilder("js", decodedCodeAdapted, identifier +
                    ".js")
                    .buildLiteral();
            graalContext.eval(source);
            Value sourceValue = bindings
                    .getMember(identifier);

            if (context.getSharedCode() != null) {
                byte[] decodedSharedCodeBytes = Base64.getDecoder().decode(context.getSharedCode());
                String decodedSharedCode = new String(decodedSharedCodeBytes);
                Source sharedSource = Source.newBuilder("js", decodedSharedCode,
                        "sharedCode.js")
                        .buildLiteral();
                graalContext.eval(sharedSource);
            }

            if (context.getSystemCode() != null) {
                byte[] decodedSystemCodeBytes = Base64.getDecoder().decode(context.getSystemCode());
                String decodedSystemCode = new String(decodedSystemCodeBytes);
                Source systemSource = Source.newBuilder("js", decodedSystemCode,
                        "systemCode.js")
                        .buildLiteral();
                graalContext.eval(systemSource);
            }

            Map jsonObject = (Map) context.getPayload();
            String payloadAsString = Functions.string(context.getPayload(), false);

            // add topic levels as metadata
            List<String> splitTopicAsList = Mapping.splitTopicExcludingSeparatorAsList(context.getTopic(), false);
            ((Map) jsonObject).put(Mapping.TOKEN_TOPIC_LEVEL, splitTopicAsList);
            Map contextData = new HashMap<String, String>() {
                {
                    put("api", mapping.getTargetAPI().toString());
                }
            };
            ((Map) jsonObject).put(Mapping.TOKEN_CONTEXT_DATA, contextData);

            final Value result = sourceValue
                    .execute(new SubstitutionContext(context.getMapping().getGenericDeviceIdentifier(),
                            payloadAsString, context.getTopic()));

            // Convert the JavaScript result to Java objects before closing the context
            final SubstitutionResult typedResult = result.as(SubstitutionResult.class);

            if (typedResult == null || typedResult.substitutions == null || typedResult.substitutions.size() == 0) {
                context.setIgnoreFurtherProcessing(true);
                log.info(
                        "{} - Extraction of source in CodeBasedProcessorInbound.extractFromSource returned no result, payload: {}",
                        context.getTenant(),
                        jsonObject);
            } else { // Now use the copied objects
                Set<String> keySet = typedResult.getSubstitutions().keySet();
                for (String key : keySet) {
                    List<SubstituteValue> processingCacheEntry = new ArrayList<>();
                    List<SubstituteValue> values = typedResult.getSubstitutions().get(key);
                    if (values != null && values.size() > 0
                            && values.get(0).expandArray) {
                        // extracted result from sourcePayload is an array, so we potentially have to
                        // iterate over the result, e.g. creating multiple devices
                        for (SubstituteValue substitutionValue : values) {
                            SubstitutionEvaluation.processSubstitute(tenant, processingCacheEntry,
                                    substitutionValue.value,
                                    substitutionValue, mapping);
                        }
                    } else if (values != null) {
                        SubstitutionEvaluation.processSubstitute(tenant, processingCacheEntry, values.getFirst().value,
                                values.getFirst(), mapping);
                    }
                    processingCache.put(key, processingCacheEntry);

                    if (key.equals(Mapping.KEY_TIME)) {
                        substitutionTimeExists = true;
                    }
                }
                if (typedResult.alarms != null && !typedResult.alarms.isEmpty()) {
                    for (String alarm : typedResult.alarms) {
                        context.getAlarms().add(alarm);
                        log.debug("{} - Alarm added: {}", context.getTenant(), alarm);
                    }
                }
                if (context.getMapping().getDebug() || context.getServiceConfiguration().isLogPayload()) {
                    log.info(
                            "{} - Extraction of source in CodeBasedProcessorInbound.extractFromSource returned {} results, payload: {} ",
                            context.getTenant(),
                            keySet == null ? 0 : keySet.size(), jsonObject);
                }
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
    }

}