package dynamic.mapper.processor.inbound.processor;

import org.springframework.stereotype.Component;

import dynamic.mapper.processor.AbstractJSONataExtractionProcessor;
import dynamic.mapper.service.MappingService;
import lombok.extern.slf4j.Slf4j;

/**
 * Inbound JSONata extraction processor that extracts and processes substitutions
 * from device payloads using JSONata expressions.
 *
 * <p>Extraction and error handling are identical to the outbound side and live in
 * {@link AbstractJSONataExtractionProcessor}; this class exists only to give the
 * inbound route its own bean to wire up.
 */
@Slf4j
@Component
public class JSONataInboundProcessor extends AbstractJSONataExtractionProcessor {

    public JSONataInboundProcessor(MappingService mappingService) {
        super(mappingService);
    }

}