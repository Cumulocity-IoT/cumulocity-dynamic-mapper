package dynamic.mapper.processor.inbound.processor;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.springframework.stereotype.Component;

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.connector.core.callback.ConnectorMessage;
import dynamic.mapper.model.API;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.Qos;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.model.ProcessingType;

@Component 
public class MappingContextProcessor implements Processor {
    
    @Override
    public void process(Exchange exchange) throws Exception {
        ConnectorMessage message = exchange.getIn().getHeader("originalMessage", ConnectorMessage.class);
        Mapping currentMapping = exchange.getIn().getBody(Mapping.class);
        ServiceConfiguration serviceConfiguration = exchange.getIn().getHeader("serviceConfiguration",ServiceConfiguration.class);
        
        // Extract additional info from headers if available
        String connectorIdentifier = exchange.getIn().getHeader("connectorIdentifier", String.class);
        
        // Create processing context using builder pattern
        ProcessingContext<Object> context = ProcessingContext.<Object>builder()
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
            
        exchange.getIn().setHeader("processingContext", context);
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
}
