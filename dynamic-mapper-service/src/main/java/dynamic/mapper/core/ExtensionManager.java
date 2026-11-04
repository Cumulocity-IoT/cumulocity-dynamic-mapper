/*
 * Copyright (c) 2022-2025 Cumulocity GmbH.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *  @authors Christof Strack, Stefan Witschel
 *
 */

package dynamic.mapper.core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.yaml.snakeyaml.Yaml;

import org.apache.commons.io.IOUtils;
import org.springframework.stereotype.Component;

import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.cumulocity.sdk.client.inventory.BinariesApi;

import dynamic.mapper.App;
import dynamic.mapper.configuration.ExtensionConfiguration;
import dynamic.mapper.core.facade.InventoryFacade;
import dynamic.mapper.model.Direction;
import dynamic.mapper.model.extension.Extension;
import dynamic.mapper.model.extension.ExtensionConfig;
import dynamic.mapper.model.extension.ExtensionEntry;
import dynamic.mapper.model.extension.ExtensionStatus;
import dynamic.mapper.model.extension.ExtensionType;
import dynamic.mapper.processor.extension.ExtensionsComponent;
import dynamic.mapper.processor.extension.ProcessorExtensionInbound;
import dynamic.mapper.processor.extension.ProcessorExtensionOutbound;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class ExtensionManager {

    private static final String EXTENSION_INTERNAL_FILE = "extension-internal.yaml";
    private static final String EXTENSION_EXTERNAL_FILE = "extension-external.yaml";

    private final BinariesApi binaryApi;

    private final ExtensionsComponent extensionsComponent;

    private final ExtensionInboundRegistry extensionInboundRegistry;

    private final ExtensionConfiguration extensionConfiguration;

    public ExtensionManager(BinariesApi binaryApi, ExtensionsComponent extensionsComponent,
            ExtensionInboundRegistry extensionInboundRegistry, ExtensionConfiguration extensionConfiguration) {
        this.binaryApi = binaryApi;
        this.extensionsComponent = extensionsComponent;
        this.extensionInboundRegistry = extensionInboundRegistry;
        this.extensionConfiguration = extensionConfiguration;
    }

    /**
     * Holds a loaded external extension's classloader together with the temporary
     * jar file backing it, so both can be released together when the extension is
     * closed/replaced/deleted.
     */
    private static final class ExtensionClassLoaderHolder {
        private final URLClassLoader classLoader;
        private final File tempFile;

        private ExtensionClassLoaderHolder(URLClassLoader classLoader, File tempFile) {
            this.classLoader = classLoader;
            this.tempFile = tempFile;
        }
    }

    // Track classloaders (and their backing temp jar files) for proper cleanup
    private final Map<String, Map<String, ExtensionClassLoaderHolder>> tenantExtensionClassLoaders = new ConcurrentHashMap<>();

    // Serializes load/reload/delete per tenant so two admins cannot interleave a swap
    private final Map<String, Object> tenantLocks = new ConcurrentHashMap<>();

    private Object lockFor(String tenant) {
        return tenantLocks.computeIfAbsent(tenant, t -> new Object());
    }

    /**
     * (Re-)load all extensions of a tenant. The new set is built off to the side and swapped into
     * the registry in one step, so mappings that process messages during a reload keep using the
     * previous extensions instead of failing with "Extension not found". The previous classloaders
     * are closed after the swap.
     */
    public void loadProcessorExtensions(String tenant) {
        synchronized (lockFor(tenant)) {
            Map<String, ExtensionClassLoaderHolder> newLoaders = new ConcurrentHashMap<>();
            Map<String, Extension> loaded = new LinkedHashMap<>();
            ClassLoader internalClassloader = ExtensionManager.class.getClassLoader();

            for (ManagedObjectRepresentation extension : extensionsComponent.get()) {
                Object fragment = extension.get(ExtensionsComponent.PROCESSOR_EXTENSION_TYPE);
                Object name = fragment instanceof Map<?, ?> props ? props.get("name") : null;
                if (name == null) {
                    // One malformed managed object must not stop the remaining extensions from loading
                    log.warn("{} - Skipping extension managed object {}: fragment {} has no name", tenant,
                            extension.getId() != null ? extension.getId().getValue() : null,
                            ExtensionsComponent.PROCESSOR_EXTENSION_TYPE);
                    continue;
                }
                String extName = name.toString();
                boolean external = Boolean.TRUE.equals(((Map<?, ?>) fragment).get("external"));
                String id = extension.getId().getValue();
                log.debug("{} - Trying to load extension id: {}, name: {}", tenant, id, extName);

                if (external && !extensionConfiguration.isExternalExtensionsEnabled()) {
                    log.info("{} - Skipping loading of external extension: {} because external extensions are disabled via configuration (app.externalExtensionsEnabled=false)",
                            tenant, extName);
                    continue;
                }

                Extension loadedExtension = external
                        ? loadExternalExtension(tenant, extension, extName, newLoaders)
                        : loadInternalExtension(tenant, id, extName, internalClassloader);
                if (loaded.put(extName, loadedExtension) != null) {
                    log.warn("{} - More than one extension is named {}, the last one overrides the others", tenant,
                            extName);
                }
            }

            Map<String, ExtensionClassLoaderHolder> oldLoaders = tenantExtensionClassLoaders.put(tenant, newLoaders);
            extensionInboundRegistry.replaceExtensions(tenant, loaded);
            closeAllClassLoaders(oldLoaders, tenant);
        }
    }

    private Extension loadInternalExtension(String tenant, String id, String extName, ClassLoader classLoader) {
        try {
            return buildExtension(tenant, id, extName, classLoader, false);
        } catch (IOException | RuntimeException e) {
            log.error("{} - Failed to load internal extension {}: ", tenant, extName, e);
            return failedExtension(id, extName, false, e);
        }
    }

    /**
     * Download the jar of an external extension, load it with its own classloader and register its
     * entries. A jar that cannot be read yields an extension with status NOT_LOADED and the reason in
     * {@link Extension#getMessage()} instead of aborting the load of the other extensions.
     */
    private Extension loadExternalExtension(String tenant, ManagedObjectRepresentation extension, String extName,
            Map<String, ExtensionClassLoaderHolder> newLoaders) {
        String id = extension.getId().getValue();
        File tempFile = null;
        URLClassLoader classLoader = null;
        try (InputStream downloadInputStream = binaryApi.downloadFile(extension.getId())) {
            // The classloader needs a url resource, so the jar goes to a temporary file
            tempFile = File.createTempFile("dm-ext-" + extName.replaceAll("[^A-Za-z0-9._-]", "_") + "-", ".jar");
            tempFile.deleteOnExit();
            try (OutputStream outputStream = new FileOutputStream(tempFile)) {
                IOUtils.copy(downloadInputStream, outputStream);
            }

            classLoader = new URLClassLoader(new URL[] { tempFile.toURI().toURL() }, App.class.getClassLoader());
            Extension result = buildExtension(tenant, id, extName, classLoader, true);

            ExtensionClassLoaderHolder previous = newLoaders.put(extName,
                    new ExtensionClassLoaderHolder(classLoader, tempFile));
            if (previous != null) {
                closeClassLoader(previous, tenant, extName);
            }
            return result;
        } catch (IOException | RuntimeException e) {
            log.error("{} - Failed to load extension {}: ", tenant, extName, e);
            closeClassLoaderAndDeleteFile(classLoader, tempFile, tenant, extName);
            return failedExtension(id, extName, true, e);
        }
    }

    private static Extension failedExtension(String id, String extName, boolean external, Exception cause) {
        Extension failed = new Extension(id, extName, external);
        failed.setLoaded(ExtensionStatus.NOT_LOADED);
        failed.setMessage(cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName());
        return failed;
    }

    private void closeClassLoader(ExtensionClassLoaderHolder holder, String tenant, String extName) {
        if (holder == null) {
            return;
        }
        closeClassLoaderAndDeleteFile(holder.classLoader, holder.tempFile, tenant, extName);
    }

    private void closeAllClassLoaders(Map<String, ExtensionClassLoaderHolder> loaders, String tenant) {
        if (loaders == null) {
            return;
        }
        loaders.forEach((extName, holder) -> closeClassLoader(holder, tenant, extName));
    }

    /**
     * Close the classloader (if any) and delete its backing temporary jar file (if any).
     * {@code deleteOnExit()} is still set on the temp file as a safety net, but we no longer
     * rely on it exclusively since it only fires at JVM shutdown and would otherwise let
     * temp files accumulate for the life of the process.
     */
    private void closeClassLoaderAndDeleteFile(URLClassLoader classLoader, File tempFile, String tenant, String extName) {
        if (classLoader != null) {
            try {
                classLoader.close();
                log.debug("{} - Closed classloader for extension: {}", tenant, extName);
            } catch (IOException e) {
                log.warn("{} - Failed to close classloader for extension: {}", tenant, extName, e);
            }
        }
        if (tempFile != null && tempFile.exists()) {
            try {
                if (!tempFile.delete()) {
                    log.warn("{} - Failed to delete temp jar file for extension: {}: {}", tenant, extName, tempFile);
                } else {
                    log.debug("{} - Deleted temp jar file for extension: {}: {}", tenant, extName, tempFile);
                }
            } catch (SecurityException e) {
                log.warn("{} - Failed to delete temp jar file for extension: {}: {}", tenant, extName, tempFile, e);
            }
        }
    }

    private void registerExtensionInProcessor(String tenant, String id, String extensionName, ClassLoader dynamicLoader,
            boolean external)
            throws IOException {
        extensionInboundRegistry.addExtension(tenant, buildExtension(tenant, id, extensionName, dynamicLoader, external));
    }

    /**
     * Read the registration file of the extension and instantiate every class it lists.
     * Failures of a single entry are recorded on that entry (loaded=false, message=reason);
     * failures that affect the whole jar (missing or unparsable registration file) are thrown.
     */
    private Extension buildExtension(String tenant, String id, String extensionName, ClassLoader dynamicLoader,
            boolean external)
            throws IOException {
        String resource = external ? EXTENSION_EXTERNAL_FILE : EXTENSION_INTERNAL_FILE;
        ExtensionConfig extensionConfig;
        try (InputStream resourceAsStream = dynamicLoader.getResourceAsStream(resource)) {
            if (resourceAsStream == null) {
                log.error("{} - Registration file: {} missing, ignoring to load extensions from: {}", tenant,
                        resource, (external ? "EXTERNAL" : "INTERNAL"));
                throw new IOException("Registration file: " + resource + " missing, ignoring to load extensions from:"
                        + (external ? "EXTERNAL" : "INTERNAL"));
            }
            extensionConfig = new Yaml().loadAs(resourceAsStream, ExtensionConfig.class);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            log.error("{} - Failed to parse YAML file: {}", tenant, resource, e);
            throw new IOException("Failed to parse YAML file: " + resource + ": " + e.getMessage(), e);
        }

        Extension extension = new Extension(id, extensionName, external);

        if (extensionConfig == null || extensionConfig.getExtensions() == null
                || extensionConfig.getExtensions().isEmpty()) {
            log.warn("{} - No extensions found in: {}", tenant, resource);
            extension.setLoaded(ExtensionStatus.NOT_LOADED);
            extension.setMessage("No extensions defined in " + resource);
            return extension;
        }

        log.debug("{} - Preparing to load {} extensions from {}", tenant,
                extensionConfig.getExtensions().size(), resource);

        for (ExtensionConfig.ExtensionDefinition definition : extensionConfig.getExtensions()) {
            ExtensionEntry extensionEntry = ExtensionEntry.builder()
                    .eventName(definition.getEventName())
                    .extensionName(extensionName)
                    .fqnClassName(definition.getClassName())
                    .description(definition.getDescription())
                    .version(definition.getVersion())
                    .parameter(definition.getParameter())
                    .loaded(true)
                    .message("Loaded successfully")
                    .direction(Direction.UNSPECIFIED)
                    .build();

            if (extension.getExtensionEntries().put(definition.getEventName(), extensionEntry) != null) {
                log.warn("{} - Extension entry with eventName {} is defined more than once in {}, the last one wins",
                        tenant, definition.getEventName(), extensionName);
            }
            loadEntry(tenant, dynamicLoader, external, definition, extensionEntry);
        }

        extension.setLoaded(ExtensionInboundRegistry.computeStatus(extension));
        return extension;
    }

    private void loadEntry(String tenant, ClassLoader dynamicLoader, boolean external,
            ExtensionConfig.ExtensionDefinition definition, ExtensionEntry extensionEntry) {
        try {
            Class<?> clazz = dynamicLoader.loadClass(definition.getClassName());

            if (external && !clazz.getPackageName().startsWith(extensionConfiguration.getExternalExtensionsAllowedPackage())) {
                extensionEntry.setMessage(
                        "Implementation must be in package: '" + extensionConfiguration.getExternalExtensionsAllowedPackage() + "' instead of: "
                                + clazz.getPackageName());
                extensionEntry.setLoaded(false);
                return;
            }

            // Instantiate once and reuse the same instance: implementations must be thread-safe
            Object extensionInstance = clazz.getDeclaredConstructor().newInstance();

            if (extensionInstance instanceof ProcessorExtensionInbound) {
                extensionEntry.setDirection(Direction.INBOUND);
                extensionEntry.setExtensionImplInbound((ProcessorExtensionInbound<?>) extensionInstance);
                extensionEntry.setExtensionType(ExtensionType.EXTENSION_INBOUND);
                log.debug("{} - Registered ProcessorExtensionInbound: {} for key: {}",
                        tenant, definition.getClassName(), definition.getEventName());
            } else if (extensionInstance instanceof ProcessorExtensionOutbound) {
                extensionEntry.setDirection(Direction.OUTBOUND);
                extensionEntry.setExtensionImplOutbound((ProcessorExtensionOutbound<?>) extensionInstance);
                extensionEntry.setExtensionType(ExtensionType.EXTENSION_OUTBOUND);
                log.debug("{} - Registered ProcessorExtensionOutbound: {} for key: {}",
                        tenant, definition.getClassName(), definition.getEventName());
            } else {
                failEntry(tenant, extensionEntry, false, String.format(
                        "Extension %s=%s must implement ProcessorExtensionInbound or ProcessorExtensionOutbound!",
                        definition.getEventName(), definition.getClassName()), null);
            }
        } catch (ClassNotFoundException e) {
            failEntry(tenant, extensionEntry, false, String.format("Could not load extension class %s:%s: %s",
                    definition.getEventName(), definition.getClassName(), e.getMessage()), e);
        } catch (LinkageError e) {
            // NoClassDefFoundError (missing dependency), ExceptionInInitializerError (static init failed), ...
            failEntry(tenant, extensionEntry, false, String.format("Missing dependency or invalid class for extension %s:%s: %s",
                    definition.getEventName(), definition.getClassName(), e), e);
        } catch (NoSuchMethodException e) {
            failEntry(tenant, extensionEntry, false, String.format("Extension class %s:%s must have a public no-arg constructor",
                    definition.getEventName(), definition.getClassName()), e);
        } catch (InstantiationException | IllegalAccessException e) {
            failEntry(tenant, extensionEntry, false, String.format("Could not instantiate extension class %s:%s: %s",
                    definition.getEventName(), definition.getClassName(), e.getMessage()), e);
        } catch (java.lang.reflect.InvocationTargetException e) {
            String exceptionMsg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
            failEntry(tenant, extensionEntry, false, String.format("Extension constructor threw exception for %s:%s: %s",
                    definition.getEventName(), definition.getClassName(), exceptionMsg), e);
        } catch (Exception e) {
            String exceptionMsg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
            failEntry(tenant, extensionEntry, true, String.format("Unexpected error loading extension %s:%s: %s",
                    definition.getEventName(), definition.getClassName(), exceptionMsg), e);
        }
    }

    private void failEntry(String tenant, ExtensionEntry entry, boolean asError, String message, Throwable cause) {
        if (asError) {
            log.error("{} - {}", tenant, message, cause);
        } else {
            log.warn("{} - {}", tenant, message);
            if (cause != null) {
                log.debug("{} - Exception details: ", tenant, cause);
            }
        }
        entry.setMessage(message);
        entry.setLoaded(false);
    }

    public Map<String, Extension> getProcessorExtensions(String tenant) {
        return extensionInboundRegistry.getExtensions(tenant);
    }

    public Extension getProcessorExtension(String tenant, String extension) {
        return extensionInboundRegistry.getExtension(tenant, extension);
    }

    /**
     * Delete an external extension: removes its jar from the binary repository, closes its
     * classloader and drops it from the registry. Built-in extensions are never deleted.
     *
     * @return the removed extension, or null if no such extension is registered
     */
    public Extension deleteProcessorExtension(String tenant, String extensionName) {
        synchronized (lockFor(tenant)) {
            Extension registered = extensionInboundRegistry.getExtension(tenant, extensionName);
            if (registered == null) {
                return null;
            }
            if (!registered.isExternal()) {
                throw new IllegalArgumentException("Built-in extension " + extensionName + " cannot be deleted");
            }
            for (ManagedObjectRepresentation extensionRepresentation : extensionsComponent.get()) {
                // Match against the same "d11r_processorExtension.name" fragment property used
                // during loading/registration, not the top-level managed object name, since these
                // can differ.
                Object fragment = extensionRepresentation.get(ExtensionsComponent.PROCESSOR_EXTENSION_TYPE);
                Object name = fragment instanceof Map<?, ?> props ? props.get("name") : null;
                if (extensionName.equals(name)
                        && Boolean.TRUE.equals(((Map<?, ?>) fragment).get("external"))) {
                    binaryApi.deleteFile(extensionRepresentation.getId());
                    log.info("{} - Deleted extension: {} permanently!", tenant, extensionName);
                }
            }

            // Close and remove classloader (and backing temp file) for this extension
            Map<String, ExtensionClassLoaderHolder> tenantClassLoaders = tenantExtensionClassLoaders.get(tenant);
            if (tenantClassLoaders != null) {
                closeClassLoader(tenantClassLoaders.remove(extensionName), tenant, extensionName);
            }

            return extensionInboundRegistry.deleteExtension(tenant, extensionName);
        }
    }

    /** Reload all extensions of the tenant from the binary repository; see {@link #loadProcessorExtensions}. */
    public void reloadExtensions(String tenant) {
        loadProcessorExtensions(tenant);
    }

    public void createExtensibleProcessor(String tenant, InventoryFacade inventoryApi) {
        log.debug("{} - Create ExtensibleProcessor", tenant);

        // check if managedObject for internal mapping extension exists
        List<ManagedObjectRepresentation> internalExtension = extensionsComponent.getInternal();
        ManagedObjectRepresentation ie = new ManagedObjectRepresentation();
        if (internalExtension == null || internalExtension.size() == 0) {
            Map<String, ?> props = Map.of("name",
                    ExtensionsComponent.PROCESSOR_EXTENSION_INTERNAL_NAME,
                    "external", false);
            ie.setProperty(ExtensionsComponent.PROCESSOR_EXTENSION_TYPE,
                    props);
            ie.setName(ExtensionsComponent.PROCESSOR_EXTENSION_INTERNAL_NAME);
            ie = inventoryApi.create(ie, false);
        } else {
            ie = internalExtension.get(0);
        }
        log.debug("{} - Internal extension: {} registered: {} ({})", tenant,
                ExtensionsComponent.PROCESSOR_EXTENSION_INTERNAL_NAME,
                ie.getId().getValue(), ie);
    }

    /**
     * Clean up all classloaders for a tenant. Should be called during tenant offboarding.
     */
    public void cleanupTenantExtensions(String tenant) {
        Map<String, ExtensionClassLoaderHolder> tenantClassLoaders = tenantExtensionClassLoaders.remove(tenant);
        if (tenantClassLoaders != null) {
            closeAllClassLoaders(tenantClassLoaders, tenant);
            log.info("{} - Cleaned up {} extension classloaders", tenant, tenantClassLoaders.size());
        }
    }

    /**
     * Clean up all classloaders for all tenants. Should be called during application shutdown.
     */
    public void cleanupAllExtensions() {
        for (String tenant : tenantExtensionClassLoaders.keySet()) {
            cleanupTenantExtensions(tenant);
        }
        log.info("Cleaned up all extension classloaders");
    }
}