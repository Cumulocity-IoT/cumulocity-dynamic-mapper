package dynamic.mapper.processor.inbound.processor;

import org.springframework.stereotype.Component;

import dynamic.mapper.processor.AbstractSnoopingProcessor;
import lombok.extern.slf4j.Slf4j;

/**
 * Inbound Snooping processor that captures device payloads
 * during snooping mode to help with mapping configuration.
 */
@Slf4j
@Component
public class SnoopingInboundProcessor extends AbstractSnoopingProcessor {
    // All functionality inherited from AbstractSnoopingProcessor
}