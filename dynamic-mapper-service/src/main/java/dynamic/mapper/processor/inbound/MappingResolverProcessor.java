package dynamic.mapper.processor.inbound;

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
