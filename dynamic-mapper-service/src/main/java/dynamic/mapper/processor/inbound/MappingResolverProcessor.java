package dynamic.mapper.processor.inbound;

import java.util.List;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.springframework.stereotype.Component;

import dynamic.mapper.connector.core.callback.ConnectorMessage;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.service.MappingService;

@Component
public class MappingResolverProcessor implements Processor {
    
    private final MappingService mappingService;
    
    public MappingResolverProcessor(MappingService mappingService) {
        this.mappingService = mappingService;
    }
    
    @Override
    public void process(Exchange exchange) throws Exception {
        ConnectorMessage connectorMessage = exchange.getIn().getBody(ConnectorMessage.class);
        String tenant = exchange.getIn().getHeader("tenant", String.class);
        String topic = exchange.getIn().getHeader("topic", String.class);
        
        List<Mapping> mappings = mappingService.resolveMappingInbound(tenant, topic);
        
        exchange.getIn().setHeader("mappings", mappings);
        exchange.getIn().setHeader("connectorMessage", connectorMessage);
    }
}
