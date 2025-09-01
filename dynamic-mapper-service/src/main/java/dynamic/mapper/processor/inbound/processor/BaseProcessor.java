package dynamic.mapper.processor.inbound.processor;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;


import lombok.extern.slf4j.Slf4j;

@Slf4j
public abstract class BaseProcessor implements Processor {

    public abstract void process(Exchange exchange) throws Exception;

}
