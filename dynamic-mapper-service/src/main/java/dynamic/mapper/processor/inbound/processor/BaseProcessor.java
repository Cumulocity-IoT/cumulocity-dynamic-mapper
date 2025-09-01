package dynamic.mapper.processor.inbound.processor;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;

import dynamic.mapper.processor.model.ProcessingContext;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public abstract class BaseProcessor implements Processor {

    public abstract void process(Exchange exchange) throws Exception;

    @SuppressWarnings("unchecked")
    ProcessingContext<Object> getProcessingContextAsObject(Exchange exchange) {
        return exchange.getIn().getHeader("processingContext", ProcessingContext.class);
    }
        @SuppressWarnings("unchecked")
    ProcessingContext<byte[]> getProcessingContextAsByteArray(Exchange exchange) {
        return exchange.getIn().getHeader("processingContext", ProcessingContext.class);
    }
}
