package dynamic.mapper.processor.inbound.processor;

import dynamic.mapper.processor.util.CamelHeaders;

import static com.dashjoin.jsonata.Jsonata.jsonata;
import static dynamic.mapper.model.Substitution.toPrettyJsonString;

import org.apache.camel.Exchange;
import org.springframework.stereotype.Component;

import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.util.Utils;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class FilterInboundProcessor extends BaseProcessor {

    final ConfigurationRegistry configurationRegistry;

    public FilterInboundProcessor(ConfigurationRegistry configurationRegistry) {
        this.configurationRegistry = configurationRegistry;
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        ProcessingContext<Object> context = exchange.getIn().getHeader(CamelHeaders.PROCESSING_CONTEXT, ProcessingContext.class);

        applyFilter(context);

    }

    private void applyFilter(ProcessingContext<Object> context) {
        String tenant = context.getTenant();
        Mapping mapping = context.getMapping();
        String mappingFilter = mapping.getFilterMapping();

        if (mappingFilter != null && !mappingFilter.isBlank()) {
            Object payloadObjectNode = context.getPayload();
            String payload = toPrettyJsonString(payloadObjectNode);
            try {
                var expr = jsonata(mappingFilter);
                Object extractedSourceContent = expr.evaluate(payloadObjectNode);
                if (!Utils.isNodeTrue(extractedSourceContent)) {
                    if (mapping.getDebug()) {
                        log.info("{} - Inbound mapping {}/{} filtered out - message filter mismatch: filter={}, payload={}",
                                tenant, mapping.getName(), mapping.getIdentifier(), mappingFilter, payload);
                    }
                    context.getWarnings().add("Payload will be ignored due to filter: " + mappingFilter);
                    context.setIgnoreFurtherProcessing(true);
                }
            } catch (Exception e) {
                // A filter that fails to evaluate (e.g. malformed expression) must not silently let
                // messages through - fail closed and surface it, matching the outbound path's behavior
                // (MappingResolverService.evaluateMessageFilter/evaluateInventoryFilter treat any
                // evaluation error as "does not match").
                log.error("{} - Inbound mapping {}/{} filter evaluation failed - message will be ignored: filter={}, payload={}",
                        tenant, mapping.getName(), mapping.getIdentifier(), mappingFilter, payload, e);
                context.getWarnings().add("Payload will be ignored - filter evaluation failed: " + mappingFilter);
                context.setIgnoreFurtherProcessing(true);
            }
        }

    }

}