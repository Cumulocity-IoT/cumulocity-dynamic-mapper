package dynamic.mapper.processor.inbound.processor;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.apache.camel.Exchange;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.configuration.TemplateType;
import dynamic.mapper.connector.core.callback.ConnectorMessage;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.MappingStatus;
import dynamic.mapper.model.Qos;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.flow.SimpleFlowContext;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.service.MappingService;
import dynamic.mapper.processor.model.TransformationType;
import lombok.extern.slf4j.Slf4j;

import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.Context;

@Component
@Slf4j
public class MappingContextInboundProcessor extends BaseProcessor {

    @Autowired
    private ConfigurationRegistry configurationRegistry;

    @Autowired
    private MappingService mappingService;

    private Context.Builder graalContextBuilder;

    @Override
    public void process(Exchange exchange) throws Exception {
        ConnectorMessage message = exchange.getIn().getHeader("connectorMessage", ConnectorMessage.class);
        Mapping mapping = exchange.getIn().getBody(Mapping.class);
        ProcessingContext<?> processingContext = exchange.getIn().getHeader("processingContext",
                ProcessingContext.class);

        ServiceConfiguration serviceConfiguration = processingContext.getServiceConfiguration();
        String tenant = message.getTenant();
        MappingStatus mappingStatus = mappingService.getMappingStatus(tenant, mapping);

        // Extract additional info from headers if available
        String connectorIdentifier = exchange.getIn().getHeader("connectorIdentifier", String.class);

        processingContext.setQos(determineQos(connectorIdentifier));

        // Prepare GraalVM context if code exists
        if (mapping.code != null && (mapping.substitutionsAsCode
                || TransformationType.SUBSTITUTION_AS_CODE.equals(mapping.transformationType))) {
            try {
                var graalEngine = configurationRegistry.getGraalEngine(message.getTenant());
                var graalContext = createGraalContext(graalEngine);
                processingContext.setGraalContext(graalContext);
                processingContext.setSharedCode(serviceConfiguration.getCodeTemplates()
                        .get(TemplateType.SHARED.name()).getCode());
                processingContext.setSystemCode(serviceConfiguration.getCodeTemplates()
                        .get(TemplateType.SYSTEM.name()).getCode());
            } catch (Exception e) {
                handleGraalVMError(tenant, mapping, e, processingContext);
                return;
            }
        } else if (mapping.code != null && 
                 TransformationType.SMART_FUNCTION.equals(mapping.transformationType)) {
            try {
                var graalEngine = configurationRegistry.getGraalEngine(message.getTenant());
                var graalContext = createGraalContext(graalEngine);
                // processingContext.setSystemCode(serviceConfiguration.getCodeTemplates()
                //         .get(TemplateType.SMART.name()).getCode());
                processingContext.setGraalContext(graalContext);
                processingContext.setFlowState(new HashMap<String, Object>());
                processingContext.setFlowContext(new SimpleFlowContext(graalContext, tenant));
            } catch (Exception e) {
                handleGraalVMError(tenant, mapping, e, processingContext);
                return;
            }
        }
        
        mappingStatus.messagesReceived++;
        logInboundMessageReceived(tenant, mapping, connectorIdentifier, processingContext, serviceConfiguration);

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
        if (serviceConfiguration.logPayload || mapping.debug) {
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
}
