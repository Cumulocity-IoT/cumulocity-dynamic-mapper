package dynamic.mapper.service;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import dynamic.mapper.model.Extension;
import dynamic.mapper.model.ExtensionEntry;
import dynamic.mapper.model.ExtensionStatus;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class ExtensionInboundRegistry {

    private final Map<String, Map<String, Extension>> tenantExtensionProcessors = new ConcurrentHashMap<>();

    /**
     * Get or create extension processor for tenant
     */
    public Map<String, Extension> getExtensions(String tenant) {
        return tenantExtensionProcessors.computeIfAbsent(tenant, t -> {
            log.info("Creating ExtensibleProcessorInbound for tenant: {}", t);
            return new ConcurrentHashMap<>();
        });
    }

    /**
     * Get or create extension processor for tenant
     */
    public Extension getExtension(String tenant, String extensionName) {
        return getExtensions(tenant).get(extensionName);
    }

    /**
     * Initialize extension processor for tenant (called during bootstrap)
     */
    public Map<String, Extension> initializeExtensions(String tenant) {
        Map<String, Extension> extensions = new ConcurrentHashMap<>();
        tenantExtensionProcessors.put(tenant, extensions);
        log.info("Initialized Extensions for tenant: {}", tenant);
        return extensions;
    }

    /**
     * Add extension to tenant's processor
     */
    public void addExtension(String tenant, Extension extension) {
        Map<String, Extension> extensions = getExtensions(tenant);
        if (extensions.containsKey(extension.getName())) {
            log.warn("{} - Extension with this name {} already exits, override existing extension!", tenant,
                    extension.getName());
        }
        extensions.put(extension.getName(), extension);
    }

    /**
     * Add extension entry to tenant's processor
     */
    public void addExtensionEntry(String tenant, String extensionName, ExtensionEntry entry) {
        Map<String, Extension> extensions = getExtensions(tenant);
        if (!extensions.containsKey(extensionName)) {
            log.warn("{} - Cannot add extension entry. Create first an extension!", tenant);
        } else {
            extensions.get(extensionName).getExtensionEntries().put(entry.getEventName(), entry);
        }
    }

    public Extension deleteExtension(String tenant, String extensionName) {
        Map<String, Extension> extensions = getExtensions(tenant);
        Extension result = extensions.remove(extensionName);
        return result;
    }

    public void deleteExtensions(String tenant) {
        Map<String, Extension> extensions = getExtensions(tenant);
        extensions = new HashMap<>();
    }

    public void updateStatusExtension(String tenant, String extName) {
        Map<String, Extension> extensions = getExtensions(tenant);
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

    /**
     * Get all registered tenants
     */
    public Set<String> getRegisteredTenants() {
        return tenantExtensionProcessors.keySet();
    }
}
