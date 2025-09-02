package dynamic.mapper.processor.outbound.processor;

import org.apache.camel.Exchange;
import org.springframework.stereotype.Component;

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.processor.model.C8YMessage;
import dynamic.mapper.processor.model.ProcessingContext;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class DeserializationOutboundProcessor extends BaseProcessor {

    @Override
    public void process(Exchange exchange) throws Exception {
        C8YMessage c8yMessage = exchange.getIn().getHeader("c8yMessage", C8YMessage.class);
        Mapping mapping = exchange.getIn().getBody(Mapping.class);
        ServiceConfiguration serviceConfiguration = exchange.getIn().getHeader("serviceConfiguration",
        ServiceConfiguration.class);
        
        String tenant = c8yMessage.getTenant();

        ProcessingContext<Object> context = createProcessingContextAsObject(tenant, mapping, c8yMessage,
                serviceConfiguration);

        exchange.getIn().setHeader("processingContext", context);

    }

}
