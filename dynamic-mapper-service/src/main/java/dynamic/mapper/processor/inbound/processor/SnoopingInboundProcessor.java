package dynamic.mapper.processor.inbound.processor;

import org.apache.camel.Exchange;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.MappingStatus;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.service.MappingService;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class SnoopingInboundProcessor extends BaseProcessor {

    @Autowired
    private MappingService mappingService;

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public void process(Exchange exchange) throws Exception {
        ProcessingContext<Object> context = getProcessingContext(exchange);
        Mapping mapping = context.getMapping();
        String tenant = context.getTenant();

        handleSnooping(tenant, mapping, context);
        // Mark context to skip further processing
        context.setIgnoreFurtherProcessing(true);

    }

    private void handleSnooping(String tenant, Mapping mapping, ProcessingContext<?> context) {
        try {
            MappingStatus mappingStatus = mappingService.getMappingStatus(tenant, mapping);

            String serializedPayload = objectMapper.writeValueAsString(context.getPayload());
            if (serializedPayload != null) {
                mapping.addSnoopedTemplate(serializedPayload);
                mappingStatus.snoopedTemplatesTotal = mapping.getSnoopedTemplates().size();
                mappingStatus.snoopedTemplatesActive++;

                log.debug("{} - Adding snoopedTemplate to map: {},{},{}",
                        tenant, mapping.getMappingTopic(), mapping.getSnoopedTemplates().size(),
                        mapping.getSnoopStatus());
                mappingService.addDirtyMapping(tenant, mapping);
            } else {
                log.warn("{} - Message could NOT be serialized for snooping", tenant);
            }
        } catch (Exception e) {
            log.warn("{} - Error during snooping: {}", tenant, e.getMessage());
            log.debug("{} - Snooping error details:", tenant, e);
            return;
        }
    }

    @SuppressWarnings("unchecked")
    private ProcessingContext<Object> getProcessingContext(Exchange exchange) {
        return exchange.getIn().getHeader("processingContext", ProcessingContext.class);
    }
}