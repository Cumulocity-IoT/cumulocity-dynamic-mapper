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
import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;
import java.util.Map;

import org.yaml.snakeyaml.Yaml;

import org.apache.commons.io.IOUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.cumulocity.sdk.client.inventory.BinariesApi;

import dynamic.mapper.App;
import dynamic.mapper.configuration.ExtensionConfiguration;
import dynamic.mapper.core.facade.InventoryFacade;
import dynamic.mapper.model.Direction;
import dynamic.mapper.model.Extension;
import dynamic.mapper.model.ExtensionConfig;
import dynamic.mapper.model.ExtensionEntry;
import dynamic.mapper.model.ExtensionType;
import dynamic.mapper.processor.extension.ExtensionsComponent;
import dynamic.mapper.processor.extension.ProcessorExtensionInbound;
import dynamic.mapper.processor.extension.ProcessorExtensionOutbound;
import dynamic.mapper.service.ExtensionInboundRegistry;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class ExtensionManager {

    private static final String EXTENSION_INTERNAL_FILE = "extension-internal.yaml";
    private static final String EXTENSION_EXTERNAL_FILE = "extension-external.yaml";

    @Autowired
    private BinariesApi binaryApi;

    private ExtensionsComponent extensionsComponent;

    @Autowired
    public void setExtensionsComponent(ExtensionsComponent extensionsComponent) {
        this.extensionsComponent = extensionsComponent;
    }

    @Autowired
    private ExtensionInboundRegistry extensionInboundRegistry;

    @Autowired
    private ExtensionConfiguration extensionConfiguration;

    // Track classloaders for proper cleanup
    private final Map<String, Map<String, URLClassLoader>> tenantExtensionClassLoaders = new java.util.concurrent.ConcurrentHashMap<>();

    public void loadProcessorExtensions(String tenant) {
        ClassLoader internalClassloader = ExtensionManager.class.getClassLoader();

        for (ManagedObjectRepresentation extension : extensionsComponent.get()) {
            Map<?, ?> props = (Map<?, ?>) (extension.get(ExtensionsComponent.PROCESSOR_EXTENSION_TYPE));
            String extName = props.get("name").toString();
            boolean external = (Boolean) props.get("external");
            log.debug("{} - Trying to load extension id: {}, name: {}", tenant, extension.getId().getValue(),
                    extName);
            InputStream downloadInputStream = null;
            FileOutputStream outputStream = null;
            URLClassLoader externalClassLoader = null;
            try {
                if (external) {
                    // step 1 download extension for binary repository
                    downloadInputStream = binaryApi.downloadFile(extension.getId());

                    // step 2 create temporary file,because classloader needs a url resource
                    File tempFile = File.createTempFile(extName, "jar");
                    tempFile.deleteOnExit();
                    String canonicalPath = tempFile.getCanonicalPath();
                    String path = tempFile.getPath();
                    String pathWithProtocol = "file://".concat(tempFile.getPath());
                    log.debug("{} - CanonicalPath: {}, Path: {}, PathWithProtocol: {}", tenant, canonicalPath,
                            path,
                            pathWithProtocol);
                    outputStream = new FileOutputStream(tempFile);
                    IOUtils.copy(downloadInputStream, outputStream);

                    // step 3 parse list of extensions
                    URL[] urls = { tempFile.toURI().toURL() };
                    externalClassLoader = new URLClassLoader(urls, App.class.getClassLoader());

                    // Track the classloader for cleanup
                    tenantExtensionClassLoaders.computeIfAbsent(tenant, k -> new java.util.concurrent.ConcurrentHashMap<>())
                            .put(extName, externalClassLoader);

                    registerExtensionInProcessor(tenant, extension.getId().getValue(), extName, externalClassLoader,
                            external);
                } else {
                    registerExtensionInProcessor(tenant, extension.getId().getValue(), extName, internalClassloader,
                            external);
                }
            } catch (IOException e) {
                log.error("{} - IO Exception occurred when loading extension: ", tenant, e);
                // Close classloader if registration failed
                closeClassLoader(externalClassLoader, tenant, extName);
            } catch (SecurityException e) {
                log.error("{} - Security Exception occurred when loading extension: ", tenant, e);
                closeClassLoader(externalClassLoader, tenant, extName);
            } catch (IllegalArgumentException e) {
                log.error("{} - Invalid argument Exception occurred when loading extension: ", tenant, e);
                closeClassLoader(externalClassLoader, tenant, extName);
            } finally {
                if (downloadInputStream != null) {
                    try {
                        downloadInputStream.close();
                    } catch (IOException e) {
                        log.warn("{} - Failed to close download stream", tenant, e);
                    }
                }
                if (outputStream != null) {
                    try {
                        outputStream.close();
                    } catch (IOException e) {
                        log.warn("{} - Failed to close output stream", tenant, e);
                    }
                }
            }
        }
    }

    private void closeClassLoader(URLClassLoader classLoader, String tenant, String extName) {
        if (classLoader != null) {
            try {
                classLoader.close();
                log.debug("{} - Closed classloader for extension: {}", tenant, extName);
            } catch (IOException e) {
                log.warn("{} - Failed to close classloader for extension: {}", tenant, extName, e);
            }
        }
    }

    private void registerExtensionInProcessor(String tenant, String id, String extensionName, ClassLoader dynamicLoader,
            boolean external)
            throws IOException {
        // manage extensions for Camel routes
        extensionInboundRegistry.addExtension(tenant, new Extension(id, extensionName, external));

        String resource = external ? EXTENSION_EXTERNAL_FILE : EXTENSION_INTERNAL_FILE;
        InputStream resourceAsStream = dynamicLoader.getResourceAsStream(resource);

        if (resourceAsStream == null) {
            log.error("{} - Registration file: {} missing, ignoring to load extensions from: {}", tenant,
                    resource,
                    (external ? "EXTERNAL" : "INTERNAL"));
            throw new IOException("Registration file: " + resource + " missing, ignoring to load extensions from:"
                    + (external ? "EXTERNAL" : "INTERNAL"));
        }

        // Parse YAML configuration
        Yaml yaml = new Yaml();
        ExtensionConfig extensionConfig;
        try {
            extensionConfig = yaml.loadAs(resourceAsStream, ExtensionConfig.class);
        } catch (Exception e) {
            log.error("{} - Failed to parse YAML file: {}", tenant, resource, e);
            throw new IOException("Failed to parse YAML file: " + resource, e);
        }

        if (extensionConfig == null || extensionConfig.getExtensions() == null) {
            log.warn("{} - No extensions found in: {}", tenant, resource);
            return;
        }

        log.debug("{} - Preparing to load {} extensions from {}", tenant,
                extensionConfig.getExtensions().size(), resource);

        for (ExtensionConfig.ExtensionDefinition definition : extensionConfig.getExtensions()) {
            Class<?> clazz;
            ExtensionEntry extensionEntry = ExtensionEntry.builder()
                    .eventName(definition.getEventName())
                    .extensionName(extensionName)
                    .fqnClassName(definition.getClassName())
                    .description(definition.getDescription())
                    .version(definition.getVersion())
                    .loaded(true)
                    .message("Loaded successfully")
                    .direction(Direction.UNSPECIFIED)
                    .build();

            // manage extensions for Camel routes
            extensionInboundRegistry.addExtensionEntry(tenant, extensionName, extensionEntry);

            try {
                clazz = dynamicLoader.loadClass(definition.getClassName());

                if (external && !clazz.getPackageName().startsWith(extensionConfiguration.getExternalExtensionsAllowedPackage())) {
                    extensionEntry.setMessage(
                            "Implementation must be in package: '" + extensionConfiguration.getExternalExtensionsAllowedPackage() + "' instead of: "
                                    + clazz.getPackageName());
                    extensionEntry.setLoaded(false);
                } else {
                    // Instantiate once and reuse the same instance
                    Object extensionInstance = clazz.getDeclaredConstructor().newInstance();

                    boolean isValidExtension = false;

                    // Auto-detect direction from marker interfaces
                    if (extensionInstance instanceof ProcessorExtensionInbound) {
                        extensionEntry.setDirection(Direction.INBOUND);
                        log.debug("{} - Extension auto-detected as INBOUND via InboundExtension", tenant);
                    } else if (extensionInstance instanceof ProcessorExtensionOutbound) {
                        extensionEntry.setDirection(Direction.OUTBOUND);
                        log.debug("{} - Extension auto-detected as OUTBOUND via OutboundExtension", tenant);
                    }

                    // Determine extension type - check specific types first
                    if (extensionInstance instanceof ProcessorExtensionInbound) {
                        // Complete inbound processing with C8YAgent
                        extensionEntry.setExtensionImplInbound((ProcessorExtensionInbound<?>) extensionInstance);
                        extensionEntry.setExtensionType(ExtensionType.EXTENSION_INBOUND);
                        isValidExtension = true;
                        log.debug("{} - Registered ProcessorExtensionInbound: {} for key: {}",
                                tenant, definition.getClassName(), definition.getEventName());
                    } else if (extensionInstance instanceof ProcessorExtensionOutbound) {
                        // Complete outbound processing with request preparation
                        extensionEntry.setExtensionImplOutbound((ProcessorExtensionOutbound<?>) extensionInstance);
                        extensionEntry.setExtensionType(ExtensionType.EXTENSION_OUTBOUND);
                        isValidExtension = true;
                        log.debug("{} - Registered ProcessorExtensionOutbound: {} for key: {}",
                                tenant, definition.getClassName(), definition.getEventName());
                    }

                    if (!isValidExtension) {
                        String msg = String.format(
                                "Extension %s=%s must implement InboundExtension, OutboundExtension, ProcessorExtensionInbound, or ProcessorExtensionOutbound!",
                                definition.getEventName(),
                                definition.getClassName());
                        log.warn("{} - {}", tenant, msg);
                        extensionEntry.setMessage(msg);
                        extensionEntry.setLoaded(false);
                    }
                }
            } catch (ClassNotFoundException e) {
                String msg = String.format("Could not load extension class %s:%s: %s", definition.getEventName(),
                        definition.getClassName(), e.getMessage());
                log.warn("{} - {}", tenant, msg);
                log.debug("{} - Exception details: ", tenant, e);
                extensionEntry.setMessage(msg);
                extensionEntry.setLoaded(false);
            } catch (NoClassDefFoundError e) {
                String msg = String.format("Missing dependency for extension class %s:%s: %s", definition.getEventName(),
                        definition.getClassName(), e.getMessage());
                log.warn("{} - {}", tenant, msg);
                log.debug("{} - Exception details: ", tenant, e);
                extensionEntry.setMessage(msg);
                extensionEntry.setLoaded(false);
            } catch (NoSuchMethodException e) {
                String msg = String.format("Extension class %s:%s must have a public no-arg constructor", definition.getEventName(),
                        definition.getClassName());
                log.warn("{} - {}", tenant, msg);
                log.debug("{} - Exception details: ", tenant, e);
                extensionEntry.setMessage(msg);
                extensionEntry.setLoaded(false);
            } catch (InstantiationException | IllegalAccessException e) {
                String msg = String.format("Could not instantiate extension class %s:%s: %s", definition.getEventName(),
                        definition.getClassName(), e.getMessage());
                log.warn("{} - {}", tenant, msg);
                log.debug("{} - Exception details: ", tenant, e);
                extensionEntry.setMessage(msg);
                extensionEntry.setLoaded(false);
            } catch (java.lang.reflect.InvocationTargetException e) {
                String exceptionMsg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
                String msg = String.format("Extension constructor threw exception for %s:%s: %s", definition.getEventName(),
                        definition.getClassName(), exceptionMsg);
                log.warn("{} - {}", tenant, msg);
                log.debug("{} - Exception details: ", tenant, e);
                extensionEntry.setMessage(msg);
                extensionEntry.setLoaded(false);
            } catch (Exception e) {
                String exceptionMsg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
                String msg = String.format("Unexpected error loading extension %s:%s: %s", definition.getEventName(),
                        definition.getClassName(), exceptionMsg);
                log.error("{} - {}", tenant, msg, e);
                extensionEntry.setMessage(msg);
                extensionEntry.setLoaded(false);
            }
        }
        // manage extensions for Camel routes
        extensionInboundRegistry.updateStatusExtension(tenant, extensionName);
    }

    public Map<String, Extension> getProcessorExtensions(String tenant) {
        return extensionInboundRegistry.getExtensions(tenant);
    }

    public Extension getProcessorExtension(String tenant, String extension) {
        return extensionInboundRegistry.getExtension(tenant, extension);
    }

    public Extension deleteProcessorExtension(String tenant, String extensionName) {
        for (ManagedObjectRepresentation extensionRepresentation : extensionsComponent.get()) {
            if (extensionName.equals(extensionRepresentation.getName())) {
                binaryApi.deleteFile(extensionRepresentation.getId());
                log.info("{} - Deleted extension: {} permanently!", tenant, extensionName);
            }
        }

        // Close and remove classloader for this extension
        Map<String, URLClassLoader> tenantClassLoaders = tenantExtensionClassLoaders.get(tenant);
        if (tenantClassLoaders != null) {
            URLClassLoader classLoader = tenantClassLoaders.remove(extensionName);
            closeClassLoader(classLoader, tenant, extensionName);
        }

        // manage extensions for Camel routes
        return extensionInboundRegistry.deleteExtension(tenant, extensionName);
    }

    public void reloadExtensions(String tenant) {
        // Close all classloaders for this tenant before reloading
        Map<String, URLClassLoader> tenantClassLoaders = tenantExtensionClassLoaders.remove(tenant);
        if (tenantClassLoaders != null) {
            for (Map.Entry<String, URLClassLoader> entry : tenantClassLoaders.entrySet()) {
                closeClassLoader(entry.getValue(), tenant, entry.getKey());
            }
        }

        // manage extensions for Camel routes
        extensionInboundRegistry.deleteExtensions(tenant);
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
        log.debug("{} - Internal extension: {} registered: {}", tenant,
                ExtensionsComponent.PROCESSOR_EXTENSION_INTERNAL_NAME,
                ie.getId().getValue(), ie);
    }

    /**
     * Clean up all classloaders for a tenant. Should be called during tenant offboarding.
     */
    public void cleanupTenantExtensions(String tenant) {
        Map<String, URLClassLoader> tenantClassLoaders = tenantExtensionClassLoaders.remove(tenant);
        if (tenantClassLoaders != null) {
            for (Map.Entry<String, URLClassLoader> entry : tenantClassLoaders.entrySet()) {
                closeClassLoader(entry.getValue(), tenant, entry.getKey());
            }
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