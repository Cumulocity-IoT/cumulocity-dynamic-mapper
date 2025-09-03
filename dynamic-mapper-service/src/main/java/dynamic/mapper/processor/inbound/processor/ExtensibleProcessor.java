package dynamic.mapper.processor.inbound.processor;

import java.util.HashMap;
import java.util.Map;

import org.apache.camel.Exchange;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import dynamic.mapper.model.Extension;
import dynamic.mapper.model.ExtensionEntry;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.MappingStatus;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.extension.ProcessorExtensionSource;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.service.ExtensionInboundRegistry;
import dynamic.mapper.service.MappingService;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class ExtensibleProcessor extends BaseProcessor {

    private Map<String, Extension> extensions = new HashMap<>();

    @Autowired
    private MappingService mappingService;

    @Autowired
    private ExtensionInboundRegistry extensionInboundRegistry;

    @Override
    public void process(Exchange exchange) throws Exception {
        ProcessingContext<byte[]> context = getProcessingContextAsByteArray(exchange);
        Mapping mapping = context.getMapping();
        String tenant = context.getTenant();

        try {
            // Check if this mapping uses extensions
            if (mapping.getExtension() != null) {
                processWithExtension(context);
            } else {
                // No extension, skip processing
                log.debug("No extension defined for mapping: {}, skipping extensible processing",
                        mapping.getName());
            }
        } catch (Exception e) {
            log.error("Error in extensible processor for mapping: {}",
                    context.getMapping().getName(), e);
            MappingStatus mappingStatus = mappingService.getMappingStatus(tenant, mapping);
            context.addError(new ProcessingException("Extensible processing failed", e));
            mappingStatus.errors++;
            mappingService.increaseAndHandleFailureCount(tenant, mapping, mappingStatus);
        }

    }

    public void processWithExtension(ProcessingContext<byte[]> context)
            throws ProcessingException {
        String tenant = context.getTenant();
        ProcessorExtensionSource extension = null;
        try {
            extension = getProcessorExtensionSource(tenant, context.getMapping().extension);
            if (extension == null) {
                log.info("{} - extractFromSource ******* {}", tenant, this);
                logExtensions(tenant);
                String message = String.format("Tenant %s - Extension %s:%s could not be found!", tenant,
                        context.getMapping().getExtension().getExtensionName(),
                        context.getMapping().getExtension().getEventName());
                log.warn(message);
                throw new ProcessingException(message);
            }
        } catch (Exception ex) {
            String message = String.format("Tenant %s - Extension %s:%s could not be found!", tenant,
                    context.getMapping().getExtension().getExtensionName(),
                    context.getMapping().getExtension().getEventName());
            log.warn(message);
            throw new ProcessingException(message);
        }
        extension.extractFromSource(context);
    }

    public ProcessorExtensionSource<?> getProcessorExtensionSource(String tenant, ExtensionEntry extension) {
        String extensionName = extension.getExtensionName();
        String eventName = extension.getEventName();
        return extensionInboundRegistry.getExtension(tenant,extensionName).getExtensionEntries().get(eventName).getExtensionImplSource();
    }

    private void logExtensions(String tenant) {
        log.info("{} - Logging content ...", tenant);
        for (Map.Entry<String, Extension> entryExtension : extensions.entrySet()) {
            String extensionKey = entryExtension.getKey();
            Extension extension = entryExtension.getValue();
            log.info("{} - Extension {}: {} found contains: ", tenant, extensionKey,
                    extension.getName());
            for (Map.Entry<String, ExtensionEntry> entryExtensionEntry : extension.getExtensionEntries().entrySet()) {
                String extensionEntryKey = entryExtensionEntry.getKey();
                ExtensionEntry extensionEntry = entryExtensionEntry.getValue();
                log.info("{} - ExtensionEntry {}: {} found : ", tenant, extensionEntryKey,
                        extensionEntry.getEventName());
            }
        }
    }

    @SuppressWarnings("unchecked")
    ProcessingContext<byte[]> getProcessingContextAsByteArray(Exchange exchange) {
        return exchange.getIn().getHeader("processingContext", ProcessingContext.class);
    }

}
