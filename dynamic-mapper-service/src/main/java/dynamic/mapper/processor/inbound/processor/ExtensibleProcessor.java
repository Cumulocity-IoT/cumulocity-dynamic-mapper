package dynamic.mapper.processor.inbound.processor;

import java.util.HashMap;
import java.util.Map;

import org.apache.camel.Exchange;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import dynamic.mapper.core.C8YAgent;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.model.Extension;
import dynamic.mapper.model.ExtensionEntry;
import dynamic.mapper.model.ExtensionStatus;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.extension.ProcessorExtensionSource;
import dynamic.mapper.processor.extension.ProcessorExtensionTarget;
import dynamic.mapper.processor.model.ProcessingContext;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class ExtensibleProcessor extends BaseProcessor {

    private Map<String, Extension> extensions = new HashMap<>();

    @Autowired
    private ConfigurationRegistry configurationRegistry;

    @Autowired
    private C8YAgent c8yAgent;

    public ExtensibleProcessor() {
        // Default constructor for Spring
    }

    public ExtensibleProcessor(ConfigurationRegistry configurationRegistry) {
        this.configurationRegistry = configurationRegistry;
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        ProcessingContext<byte[]> context = getProcessingContextAsByteArray(exchange);

        try {
            // Check if this mapping uses extensions
            Mapping mapping = context.getMapping();
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
            context.addError(new ProcessingException("Extensible processing failed", e));
        }

        exchange.getIn().setHeader("processingContext", context);
    }

    /**
     * Process using extension - combines extraction and substitution logic
     */
    private void processWithExtension(ProcessingContext<byte[]> context) throws ProcessingException {
        // First: Extract from source using extension
        extractFromSourceWithExtension(context);

        // Second: Substitute and send using extension (if available)
        substituteInTargetAndSendWithExtension(context);
    }

    /**
     * EXACT copy of ExtensibleProcessorInbound.extractFromSource - DO NOT MODIFY!
     */
    public void extractFromSourceWithExtension(ProcessingContext<byte[]> context)
            throws ProcessingException {
        ProcessorExtensionSource extension = null;
        String tenant = context.getTenant();
        try {
            extension = getProcessorExtensionSource(context.getMapping().getExtension());
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

    /**
     * EXACT copy of ExtensibleProcessorInbound.substituteInTargetAndSend - DO NOT
     * MODIFY!
     */
    public void substituteInTargetAndSendWithExtension(ProcessingContext<byte[]> context) {
        ProcessorExtensionTarget extension = null;

        extension = getProcessorExtensionTarget(context.getMapping().getExtension());
        // the extension is only meant to be used on the source side, extracting. From
        // now on we can use the standard substituteInTargetAndSend
        if (extension == null) {
            // No target extension available - let subsequent processors handle this
            log.debug("No target extension found for mapping: {}, will use standard processing",
                    context.getMapping().getName());
            return;
        } else {
            extension.substituteInTargetAndSend(context, c8yAgent);
        }
        return;
    }

    /**
     * EXACT copy of ExtensibleProcessorInbound.getProcessorExtensionSource - DO NOT
     * MODIFY!
     */
    public ProcessorExtensionSource<?> getProcessorExtensionSource(ExtensionEntry extension) {
        String extensionName = extension.getExtensionName();
        String eventName = extension.getEventName();
        return extensions.get(extensionName).getExtensionEntries().get(eventName).getExtensionImplSource();
    }

    /**
     * EXACT copy of ExtensibleProcessorInbound.getProcessorExtensionTarget - DO NOT
     * MODIFY!
     */
    public ProcessorExtensionTarget<?> getProcessorExtensionTarget(ExtensionEntry extension) {
        String extensionName = extension.getExtensionName();
        String eventName = extension.getEventName();
        return extensions.get(extensionName).getExtensionEntries().get(eventName).getExtensionImplTarget();
    }

    /**
     * EXACT copy of ExtensibleProcessorInbound.getExtension - DO NOT MODIFY!
     */
    public Extension getExtension(String extensionName) {
        return extensions.get(extensionName);
    }

    /**
     * EXACT copy of ExtensibleProcessorInbound.getExtensions - DO NOT MODIFY!
     */
    public Map<String, Extension> getExtensions() {
        return extensions;
    }

    /**
     * EXACT copy of ExtensibleProcessorInbound.logExtensions - DO NOT MODIFY!
     */
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

    /**
     * EXACT copy of ExtensibleProcessorInbound.addExtensionEntry - DO NOT MODIFY!
     */
    public void addExtensionEntry(String tenant, String extensionName, ExtensionEntry entry) {
        if (!extensions.containsKey(extensionName)) {
            log.warn("{} - Cannot add extension entry. Create first an extension!", tenant);
        } else {
            extensions.get(extensionName).getExtensionEntries().put(entry.getEventName(), entry);
        }
    }

    /**
     * EXACT copy of ExtensibleProcessorInbound.addExtension - DO NOT MODIFY!
     */
    public void addExtension(String tenant, Extension extension) {
        if (extensions.containsKey(extension.getName())) {
            log.warn("{} - Extension with this name {} already exits, override existing extension!", tenant,
                    extension.getName());
        }
        extensions.put(extension.getName(), extension);
    }

    /**
     * EXACT copy of ExtensibleProcessorInbound.deleteExtension - DO NOT MODIFY!
     */
    public Extension deleteExtension(String extensionName) {
        Extension result = extensions.remove(extensionName);
        return result;
    }

    /**
     * EXACT copy of ExtensibleProcessorInbound.deleteExtensions - DO NOT MODIFY!
     */
    public void deleteExtensions() {
        extensions = new HashMap<>();
    }

    /**
     * EXACT copy of ExtensibleProcessorInbound.updateStatusExtension - DO NOT
     * MODIFY!
     */
    public void updateStatusExtension(String extName) {
        Extension ext = extensions.get(extName);
        ext.setLoaded(ExtensionStatus.COMPLETE);
        long countDefined = ext.getExtensionEntries().size();
        long countLoaded = ext.getExtensionEntries().entrySet().stream()
                .map(entry -> entry.getValue().isLoaded())
                .filter(entry -> entry).count();
        if (countLoaded == 0) {
            ext.setLoaded(ExtensionStatus.NOT_LOADED);
        } else if (countLoaded < countDefined) {
            ext.setLoaded(ExtensionStatus.PARTIALLY);
        }
    }

    @SuppressWarnings("unchecked")
    ProcessingContext<byte[]> getProcessingContextAsByteArray(Exchange exchange) {
        return exchange.getIn().getHeader("processingContext", ProcessingContext.class);
    }

}
