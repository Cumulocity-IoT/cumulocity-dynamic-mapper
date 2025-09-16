package dynamic.mapper.processor.inbound.processor;

import static com.dashjoin.jsonata.Jsonata.jsonata;
import static dynamic.mapper.model.Substitution.toPrettyJsonString;

import org.apache.camel.Exchange;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.util.Utils;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class FilterInboundProcessor extends BaseProcessor {

    @Autowired
    ConfigurationRegistry configurationRegistry;

    @Override
    public void process(Exchange exchange) throws Exception {
        ProcessingContext<Object> context = exchange.getIn().getHeader("processingContext", ProcessingContext.class);

        applyFilter(context);

    }

    private void applyFilter(ProcessingContext<Object> context) {
        String tenant = context.getTenant();
        Mapping mapping = context.getMapping();
        String mappingFilter = mapping.getFilterMapping();
        if (mappingFilter != null && !("").equals(mappingFilter)) {
            Object payloadObjectNode = context.getPayload();
            String payload = toPrettyJsonString(payloadObjectNode);
            try {
                var expr = jsonata(mappingFilter);
                Object extractedSourceContent = expr.evaluate(payloadObjectNode);
                if (!Utils.isNodeTrue(extractedSourceContent)) {
                    log.info("{} - Payload will be ignored due to filter: {}, {}", tenant, mappingFilter,
                            payload);
                    context.setIgnoreFurtherProcessing(true);
                }
            } catch (Exception e) {
                log.error("{} - Exception for: {}, {}: ", tenant, mappingFilter,
                        payload, e);
            }
        }

    }


}