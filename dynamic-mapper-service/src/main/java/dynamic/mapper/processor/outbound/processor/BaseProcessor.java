package dynamic.mapper.processor.outbound.processor;

import static com.dashjoin.jsonata.Jsonata.jsonata;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;

import dynamic.mapper.model.Mapping;
import dynamic.mapper.processor.model.ProcessingContext;
import jakarta.validation.constraints.NotNull;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public abstract class BaseProcessor implements Processor {

    public abstract void process(Exchange exchange) throws Exception;

    @SuppressWarnings("unchecked")
    ProcessingContext<Object> getProcessingContextAsObject(Exchange exchange) {
        return exchange.getIn().getHeader("processingContext", ProcessingContext.class);
    }

    protected Object extractContent(ProcessingContext<Object> context, Mapping mapping, Object payloadJsonNode,
            String payloadAsString, @NotNull String ps) {
        Object extractedSourceContent = null;
        try {
            // var expr = jsonata(mapping.transformGenericPath2C8YPath(ps));
            var expr = jsonata(ps);
            extractedSourceContent = expr.evaluate(payloadJsonNode);
        } catch (Exception e) {
            log.error("{} - EvaluateRuntimeException for: {}, {}: ", context.getTenant(),
                    ps,
                    payloadAsString, e);
        }
        return extractedSourceContent;
    }
}