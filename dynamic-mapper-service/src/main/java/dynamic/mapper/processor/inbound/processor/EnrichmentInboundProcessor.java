package dynamic.mapper.processor.inbound.processor;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.camel.Exchange;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.cumulocity.sdk.client.ProcessingMode;

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.configuration.TemplateType;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.core.InventoryEnrichmentClient;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.MappingStatus;
import dynamic.mapper.model.Qos;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.flow.SimpleFlowContext;
import dynamic.mapper.processor.flow.DataPrepContext;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.service.MappingService;
import dynamic.mapper.processor.model.TransformationType;
import lombok.extern.slf4j.Slf4j;

import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

@Component
@Slf4j
public class EnrichmentInboundProcessor extends BaseProcessor {

    @Autowired
    private ConfigurationRegistry configurationRegistry;

    @Autowired
    private MappingService mappingService;

    private Context.Builder graalContextBuilder;

    @Override
    public void process(Exchange exchange) throws Exception {
        ProcessingContext<?> context = exchange.getIn().getHeader("processingContext",
                ProcessingContext.class);

        String tenant = context.getTenant();
        Mapping mapping = context.getMapping();

        ServiceConfiguration serviceConfiguration = context.getServiceConfiguration();
        MappingStatus mappingStatus = mappingService.getMappingStatus(tenant, mapping);

        // Extract additional info from headers if available
        String connectorIdentifier = exchange.getIn().getHeader("connectorIdentifier", String.class);

        context.setQos(determineQos(connectorIdentifier));

        // Prepare GraalVM context if code exists
        if (mapping.getCode() != null
                && TransformationType.SUBSTITUTION_AS_CODE.equals(mapping.getTransformationType())) {
            try {
                var graalEngine = configurationRegistry.getGraalEngine(tenant);
                var graalContext = createGraalContext(graalEngine);
                context.setGraalContext(graalContext);
                context.setSharedCode(serviceConfiguration.getCodeTemplates()
                        .get(TemplateType.SHARED.name()).getCode());
                context.setSystemCode(serviceConfiguration.getCodeTemplates()
                        .get(TemplateType.SYSTEM.name()).getCode());
            } catch (Exception e) {
                handleGraalVMError(tenant, mapping, e, context);
                return;
            }
        } else if (mapping.getCode() != null &&
                TransformationType.SMART_FUNCTION.equals(mapping.getTransformationType())) {
            try {
                var graalEngine = configurationRegistry.getGraalEngine(tenant);
                var graalContext = createGraalContext(graalEngine);
                // processingContext.setSystemCode(serviceConfiguration.getCodeTemplates()
                // .get(TemplateType.SMART.name()).getCode());
                context.setGraalContext(graalContext);
                context.setFlowState(new HashMap<String, Object>());
                context.setFlowContext(new SimpleFlowContext(graalContext, tenant,
                        (InventoryEnrichmentClient) configurationRegistry.getC8yAgent(),
                        context.getTesting()));
            } catch (Exception e) {
                handleGraalVMError(tenant, mapping, e, context);
                return;
            }
        }

        mappingStatus.messagesReceived++;
        logInboundMessageReceived(tenant, mapping, connectorIdentifier, context, serviceConfiguration);

        // Now call the enrichment logic
        try {
            enrichPayload(context);
        } catch (Exception e) {
            String errorMessage = String.format("%s - Error in enrichment phase for mapping: %s", tenant,
                    mapping.getName());
            log.error(errorMessage, e);
            if(e instanceof ProcessingException)
                context.addError((ProcessingException) e);
            else
                context.addError(new ProcessingException(errorMessage, e));
            mappingStatus.errors++;
            mappingService.increaseAndHandleFailureCount(tenant, mapping, mappingStatus);
            return;
        }
    }

    private Qos determineQos(String connectorIdentifier) {
        // Determine QoS based on connector type
        if ("mqtt".equalsIgnoreCase(connectorIdentifier)) {
            return Qos.AT_LEAST_ONCE;
        } else if ("kafka".equalsIgnoreCase(connectorIdentifier)) {
            return Qos.EXACTLY_ONCE;
        } else {
            return Qos.AT_MOST_ONCE; // Default for HTTP, etc.
        }
    }

    private Context createGraalContext(Engine graalEngine)
            throws Exception {
        if (graalContextBuilder == null)
            graalContextBuilder = Context.newBuilder("js");

        Context graalContext = graalContextBuilder
                .engine(graalEngine)
                // .option("engine.WarnInterpreterOnly", "false")
                .allowHostAccess(configurationRegistry.getHostAccess())
                .allowHostClassLookup(className ->
                // Allow only the specific SubstitutionContext class
                className.equals("dynamic.mapper.processor.model.SubstitutionContext")
                        || className.equals("dynamic.mapper.processor.model.SubstitutionResult")
                        || className.equals("dynamic.mapper.processor.model.SubstituteValue")
                        || className.equals("dynamic.mapper.processor.model.SubstituteValue$TYPE")
                        || className.equals("dynamic.mapper.processor.model.RepairStrategy")
                        // Allow base collection classes needed for return values
                        || className.equals("java.util.ArrayList") ||
                        className.equals("java.util.HashMap") ||
                        className.equals("java.util.HashSet"))
                .build();
        return graalContext;
    }

    private void logInboundMessageReceived(String tenant, Mapping mapping, String connectorIdentifier,
            ProcessingContext<?> context,
            ServiceConfiguration serviceConfiguration) {
        if (serviceConfiguration.getLogPayload() || mapping.getDebug()) {
            Object pp = context.getPayload();
            String ppLog = null;

            if (pp instanceof byte[]) {
                ppLog = new String((byte[]) pp, StandardCharsets.UTF_8);
            } else if (pp != null) {
                ppLog = pp.toString();
            }
            log.info(
                    "{} - PROCESSING message on topic: [{}], on  connector: {}, for Mapping {} with QoS: {}, wrapped message: {}",
                    tenant, context.getTopic(), connectorIdentifier, mapping.getName(),
                    mapping.getQos().ordinal(), ppLog);
        } else {
            log.info(
                    "{} - PROCESSING message on topic: [{}], on  connector: {}, for Mapping {} with QoS: {}",
                    tenant, context.getTopic(), connectorIdentifier, mapping.getName(),
                    mapping.getQos().ordinal());
        }
    }

    private void handleGraalVMError(String tenant, Mapping mapping, Exception e,
            ProcessingContext<?> context) {
        MappingStatus mappingStatus = mappingService
                .getMappingStatus(tenant, mapping);
        String errorMessage = String.format("Tenant %s - Failed to set up GraalVM context: %s",
                tenant, e.getMessage());
        log.error(errorMessage, e);
        context.addError(new ProcessingException(errorMessage, e));
        mappingStatus.errors++;
        mappingService.increaseAndHandleFailureCount(tenant, mapping, mappingStatus);
    }

    // ========== ENRICHMENT LOGIC (from EnrichmentInboundProcessor) ==========

    @SuppressWarnings({ "unchecked", "rawtypes" })
    public void enrichPayload(ProcessingContext<?> context) {
        /*
         * step 0 patch payload with dummy property _TOPIC_LEVEL_ in case the content
         * is required in the payload for a substitution
         * 
         * Also add enrichment data to DataPrepContext for JavaScript Flow Functions
         */
        String tenant = context.getTenant();
        Object payloadObject = context.getPayload();
        Mapping mapping = context.getMapping();

        // Process topic levels
        List<String> splitTopicAsList = Mapping.splitTopicExcludingSeparatorAsList(context.getTopic(), false);

        // Add topic levels to DataPrepContext if available
        DataPrepContext flowContext = context.getFlowContext();
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
            addToFlowContext(flowContext, context, ProcessingContext.GENERIC_DEVICE_IDENTIFIER, mapping.getGenericDeviceIdentifier());
            addToFlowContext(flowContext, context, ProcessingContext.DEBUG, mapping.getDebug());

            if (context.getMapping().getEventWithAttachment()) {
                addToFlowContext(flowContext, context, ProcessingContext.ATTACHMENT_TYPE, "");
                addToFlowContext(flowContext, context, ProcessingContext.ATTACHMENT_NAME, "");
                addToFlowContext(flowContext, context, ProcessingContext.ATTACHMENT_DATA, "");
                addToFlowContext(flowContext, context, ProcessingContext.EVENT_WITH_ATTACHMENT, true);
            }
            if (context.getMapping().getCreateNonExistingDevice()) {
                addToFlowContext(flowContext, context, ProcessingContext.DEVICE_NAME, context.getDeviceName());
                addToFlowContext(flowContext, context, ProcessingContext.DEVICE_TYPE, context.getDeviceType());
                addToFlowContext(flowContext, context, ProcessingContext.CREATE_NON_EXISTING_DEVICE, true);
            }

        } else if (payloadObject instanceof Map) {
            // Keep original behavior - add to payload
            ((Map) payloadObject).put(Mapping.TOKEN_TOPIC_LEVEL, splitTopicAsList);

            // Process message context
            if (context.getKey() != null) {
                String keyString = context.getKey();
                Map<String, String> contextData = new HashMap<String, String>() {
                    {
                        put(Mapping.CONTEXT_DATA_KEY_NAME, keyString);
                        put("api", context.getMapping().getTargetAPI().toString());
                        put("processingMode", ProcessingMode.PERSISTENT.toString());
                        if (context.getMapping().getCreateNonExistingDevice()) {
                            put(ProcessingContext.DEVICE_NAME, context.getDeviceName());
                            put(ProcessingContext.DEVICE_TYPE, context.getDeviceType());
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
                contextData.put(ProcessingContext.ATTACHMENT_NAME, "");
                contextData.put(ProcessingContext.ATTACHMENT_NAME, "");
                contextData.put(ProcessingContext.ATTACHMENT_DATA, "");
            }

        } else {
            log.info(
                    "{} - This message is not parsed by Base Inbound Processor, will be potentially parsed by extension due to custom format.",
                    tenant);
        }

        if (flowContext != null) {
            log.debug("{} - Enriched DataPrepContext with payload enrichment data", tenant);
        }
    }

    /**
     * Helper method to safely add values to DataPrepContext
     */
    private void addToFlowContext(DataPrepContext flowContext, ProcessingContext<?> context, String key,
            Object value) {
        try {
            if (context.getGraalContext() != null && value != null) {
                Value graalValue = context.getGraalContext().asValue(value);
                flowContext.setState(key, graalValue);
            }
        } catch (Exception e) {
            log.warn("{} - Failed to add '{}' to DataPrepContext: {}", context.getTenant(), key, e.getMessage());
        }
    }
}