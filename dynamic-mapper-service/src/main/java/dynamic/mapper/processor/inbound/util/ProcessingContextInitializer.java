package dynamic.mapper.processor.inbound.util;

import java.util.List;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.springframework.stereotype.Component;

import dynamic.mapper.connector.core.callback.ConnectorMessage;
import dynamic.mapper.model.Mapping;

@Component
public class ProcessingContextInitializer implements Processor {
    
    @Override
    public void process(Exchange exchange) throws Exception {
        ConnectorMessage message = exchange.getIn().getHeader("connectorMessage", ConnectorMessage.class);
        @SuppressWarnings("unchecked")
        List<Mapping> mappings = exchange.getIn().getHeader("mappings", List.class);
        
        // Store the original message and mappings for use in split processing
        exchange.getIn().setHeader("originalMessage", message);
        exchange.getIn().setHeader("allMappings", mappings);
    }
}
