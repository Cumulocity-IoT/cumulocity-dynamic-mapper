package dynamic.mapper.processor.inbound.processor;

import org.apache.camel.Exchange;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.configuration.TemplateType;
import dynamic.mapper.connector.core.callback.ConnectorMessage;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.model.API;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.Qos;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.model.ProcessingType;

import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.Context;

@Component
public class MappingContextProcessor extends BaseProcessor {

    @Autowired
    private ConfigurationRegistry configurationRegistry;

    private Context.Builder graalContextBuilder;

    @Override
    public void process(Exchange exchange) throws Exception {
        ConnectorMessage message = exchange.getIn().getHeader("connectorMessage", ConnectorMessage.class);
        Mapping currentMapping = exchange.getIn().getBody(Mapping.class);
        ServiceConfiguration serviceConfiguration = exchange.getIn().getHeader("serviceConfiguration",
                ServiceConfiguration.class);

        // Extract additional info from headers if available
        String connectorIdentifier = exchange.getIn().getHeader("connectorIdentifier", String.class);

        ProcessingContext<byte[]> contextAsByteArray = ProcessingContext.<byte[]>builder()
                .mapping(currentMapping)
                .topic(message.getTopic())
                .client(message.getClient())
                .tenant(message.getTenant())
                .serviceConfiguration(serviceConfiguration)
                .sendPayload(message.isSendPayload())
                .supportsMessageContext(message.isSupportsMessageContext())
                .key(message.getKey())
                .rawPayload(message.getPayload()) // Store original byte array
                // Set defaults
                .processingType(ProcessingType.INBOUND) // Assuming this exists
                .qos(determineQos(connectorIdentifier)) // Helper method to determine QoS
                .api(API.MEASUREMENT) // Default API, will be updated during processing
                .build();

        // TODO update mapping statistics ang log incoming message: logInboundMessageReceived
        // Prepare GraalVM context if code exists
        if (currentMapping.code != null || currentMapping.substitutionsAsCode) {
            try {
                // contextSemaphore.acquire();
                var graalEngine = configurationRegistry.getGraalEngine(message.getTenant());
                var graalContext = createGraalContext(graalEngine);
                contextAsByteArray.setGraalContext(graalContext);
                // context.setSharedSource(configurationRegistry.getGraalsSourceShared(tenant));
                // context.setSystemSource(configurationRegistry.getGraalsSourceSystem(tenant));
                // context.setMappingSource(configurationRegistry.getGraalsSourceMapping(tenant,
                // mapping.id));
                contextAsByteArray.setSharedCode(serviceConfiguration.getCodeTemplates()
                        .get(TemplateType.SHARED.name()).getCode());
                contextAsByteArray.setSystemCode(serviceConfiguration.getCodeTemplates()
                        .get(TemplateType.SYSTEM.name()).getCode());
            } catch (Exception e) {
                // TODO handle error
            }
        }

        exchange.getIn().setHeader("processingContextAsByteArray", contextAsByteArray);

        ProcessingContext<Object> contextAsObject = ProcessingContext.<Object>builder()
                .mapping(currentMapping)
                .topic(message.getTopic())
                .client(message.getClient())
                .tenant(message.getTenant())
                .serviceConfiguration(serviceConfiguration)
                .sendPayload(message.isSendPayload())
                .supportsMessageContext(message.isSupportsMessageContext())
                .key(message.getKey())
                .rawPayload(message.getPayload()) // Store original byte array
                // Set defaults
                .processingType(ProcessingType.INBOUND) // Assuming this exists
                .qos(determineQos(connectorIdentifier)) // Helper method to determine QoS
                .api(API.MEASUREMENT) // Default API, will be updated during processing
                .build();

        exchange.getIn().setHeader("processingContextAsObject", contextAsObject);

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
}
