package dynamic.mapper.processor.outbound.processor;

import java.nio.charset.StandardCharsets;

import org.apache.camel.Exchange;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.configuration.TemplateType;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.MappingStatus;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.model.C8YMessage;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.service.MappingService;
import lombok.extern.slf4j.Slf4j;

import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.Context;

@Component
@Slf4j
public class MappingContextOutboundProcessor extends BaseProcessor {

    @Autowired
    private ConfigurationRegistry configurationRegistry;

    @Autowired
    private MappingService mappingService;

    private Context.Builder graalContextBuilder;

    @Override
    public void process(Exchange exchange) throws Exception {
        C8YMessage message = exchange.getIn().getHeader("c8yMessage", C8YMessage.class);
        Mapping currentMapping = exchange.getIn().getBody(Mapping.class);
        ProcessingContext<?> processingContext = exchange.getIn().getHeader("processingContext",
        ProcessingContext.class);

        ServiceConfiguration serviceConfiguration = processingContext.getServiceConfiguration();
        String tenant = message.getTenant();

        // Extract additional info from headers if available
        String connectorIdentifier = exchange.getIn().getHeader("connectorIdentifier", String.class);
        
        // Prepare GraalVM context if code exists
        if (currentMapping.code != null || currentMapping.substitutionsAsCode) {
            try {
                // contextSemaphore.acquire();
                var graalEngine = configurationRegistry.getGraalEngine(message.getTenant());
                var graalContext = createGraalContext(graalEngine);
                processingContext.setGraalContext(graalContext);
                processingContext.setSharedCode(serviceConfiguration.getCodeTemplates()
                        .get(TemplateType.SHARED.name()).getCode());
                processingContext.setSystemCode(serviceConfiguration.getCodeTemplates()
                        .get(TemplateType.SYSTEM.name()).getCode());
            } catch (Exception e) {
                handleGraalVMError(tenant, currentMapping, e, processingContext);
                return;
            }
        }
        logOutboundMessageReceived(tenant, currentMapping, connectorIdentifier, processingContext, serviceConfiguration);

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

    private void logOutboundMessageReceived(String tenant, Mapping mapping, String connectorIdentifier,
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
