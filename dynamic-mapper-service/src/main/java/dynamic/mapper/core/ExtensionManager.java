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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.commons.io.IOUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.cumulocity.sdk.client.inventory.BinariesApi;

import dynamic.mapper.App;
import dynamic.mapper.core.facade.InventoryFacade;
import dynamic.mapper.model.Extension;
import dynamic.mapper.model.ExtensionEntry;
import dynamic.mapper.model.ExtensionType;
import dynamic.mapper.processor.extension.ExtensionsComponent;
import dynamic.mapper.processor.extension.ProcessorExtensionSource;
import dynamic.mapper.processor.extension.ProcessorExtensionTarget;
import dynamic.mapper.service.ExtensionInboundRegistry;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class ExtensionManager {

    private static final String EXTENSION_INTERNAL_FILE = "extension-internal.properties";
    private static final String EXTENSION_EXTERNAL_FILE = "extension-external.properties";
    private static final String PACKAGE_MAPPING_PROCESSOR_EXTENSION_EXTERNAL = "dynamic.mapper.processor.extension.external";

    @Autowired
    private BinariesApi binaryApi;

    private ExtensionsComponent extensionsComponent;

    @Autowired
    public void setExtensionsComponent(ExtensionsComponent extensionsComponent) {
        this.extensionsComponent = extensionsComponent;
    }

    @Autowired
    private ExtensionInboundRegistry extensionInboundRegistry;

    public void loadProcessorExtensions(String tenant) {
        ClassLoader internalClassloader = ExtensionManager.class.getClassLoader();
        ClassLoader externalClassLoader = null;

        for (ManagedObjectRepresentation extension : extensionsComponent.get()) {
            Map<?, ?> props = (Map<?, ?>) (extension.get(ExtensionsComponent.PROCESSOR_EXTENSION_TYPE));
            String extName = props.get("name").toString();
            boolean external = (Boolean) props.get("external");
            log.debug("{} - Trying to load extension id: {}, name: {}", tenant, extension.getId().getValue(),
                    extName);
            InputStream downloadInputStream = null;
            FileOutputStream outputStream = null;
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
                    registerExtensionInProcessor(tenant, extension.getId().getValue(), extName, externalClassLoader,
                            external);
                } else {
                    registerExtensionInProcessor(tenant, extension.getId().getValue(), extName, internalClassloader,
                            external);
                }
            } catch (IOException e) {
                log.error("{} - IO Exception occurred when loading extension: ", tenant, e);
            } catch (SecurityException e) {
                log.error("{} - Security Exception occurred when loading extension: ", tenant, e);
            } catch (IllegalArgumentException e) {
                log.error("{} - Invalid argument Exception occurred when loading extension: ", tenant, e);
            } finally {
                // Consider cleaning up resources here
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

    private void registerExtensionInProcessor(String tenant, String id, String extensionName, ClassLoader dynamicLoader,
            boolean external)
            throws IOException {
        // manage extensions for Camel routes
        extensionInboundRegistry.addExtension(tenant, new Extension(id, extensionName, external));

        String resource = external ? EXTENSION_EXTERNAL_FILE : EXTENSION_INTERNAL_FILE;
        InputStream resourceAsStream = dynamicLoader.getResourceAsStream(resource);
        InputStreamReader in = null;
        try {
            in = new InputStreamReader(resourceAsStream);
        } catch (Exception e) {
            log.error("{} - Registration file: {} missing, ignoring to load extensions from: {}", tenant,
                    resource,
                    (external ? "EXTERNAL" : "INTERNAL"));
            throw new IOException("Registration file: " + resource + " missing, ignoring to load extensions from:"
                    + (external ? "EXTERNAL" : "INTERNAL"));
        }
        BufferedReader buffered = new BufferedReader(in);
        Properties newExtensions = new Properties();

        if (buffered != null)
            newExtensions.load(buffered);
        log.debug("{} - Preparing to load extensions:" + newExtensions.toString(), tenant);

        Enumeration<?> extensionEntries = newExtensions.propertyNames();
        while (extensionEntries.hasMoreElements()) {
            String key = (String) extensionEntries.nextElement();
            Class<?> clazz;
            ExtensionEntry extensionEntry = ExtensionEntry.builder().eventName(key).extensionName(extensionName)
                    .fqnClassName(newExtensions.getProperty(key)).loaded(true).message("OK").build();

            // manage extensions for Camel routes
            extensionInboundRegistry.addExtensionEntry(tenant, extensionName, extensionEntry);

            try {
                clazz = dynamicLoader.loadClass(newExtensions.getProperty(key));

                if (external && !clazz.getPackageName().startsWith(PACKAGE_MAPPING_PROCESSOR_EXTENSION_EXTERNAL)) {
                    extensionEntry.setMessage(
                            "Implementation must be in package: 'dynamic.mapper.processor.extension.external' instead of: "
                                    + clazz.getPackageName());
                    extensionEntry.setLoaded(false);
                } else {
                    Object object = clazz.getDeclaredConstructor().newInstance();
                    if (object instanceof ProcessorExtensionSource) {
                        ProcessorExtensionSource<?> extensionImpl = (ProcessorExtensionSource<?>) clazz
                                .getDeclaredConstructor()
                                .newInstance();
                        extensionEntry.setExtensionImplSource(extensionImpl);
                        extensionEntry.setExtensionType(ExtensionType.EXTENSION_SOURCE);
                        log.debug("{} - Successfully registered extensionImplSource : {} for key: {}",
                                tenant,
                                newExtensions.getProperty(key),
                                key);
                    }
                    if (object instanceof ProcessorExtensionTarget) {
                        ProcessorExtensionTarget<?> extensionImpl = (ProcessorExtensionTarget<?>) clazz
                                .getDeclaredConstructor()
                                .newInstance();
                        extensionEntry.setExtensionImplTarget(extensionImpl);
                        // overwrite type since it implements both
                        extensionEntry.setExtensionType(ExtensionType.EXTENSION_SOURCE_TARGET);
                        log.debug("{} - Successfully registered extensionImplTarget : {} for key: {}",
                                tenant,
                                newExtensions.getProperty(key),
                                key);
                    }
                    if (!(object instanceof ProcessorExtensionSource)
                            && !(object instanceof ProcessorExtensionTarget)) {
                        String msg = String.format(
                                "Extension: %s=%s is not instance of ProcessorExtension, does not extend ProcessorExtensionSource!",
                                key,
                                newExtensions.getProperty(key));
                        log.warn(msg);
                        extensionEntry.setMessage(msg);
                        extensionEntry.setLoaded(false);
                    }
                }
            } catch (Exception e) {
                String exceptionMsg = e.getCause() == null ? e.getMessage() : e.getCause().getMessage();
                String msg = String.format("Could not load extension: %s:%s: %s!", key,
                        newExtensions.getProperty(key), exceptionMsg);
                log.warn(msg);
                e.printStackTrace();
                extensionEntry.setMessage(msg);
                extensionEntry.setLoaded(false);
            } catch (Error e) {
                String msg = String.format("Could not load extension: %s:%s: %s!", key,
                        newExtensions.getProperty(key), e.getMessage());
                log.warn(msg);
                e.printStackTrace();
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
        // manage extensions for Camel routes
        return extensionInboundRegistry.deleteExtension(tenant, extensionName);
    }

    public void reloadExtensions(String tenant) {
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
            ie = inventoryApi.create(ie, null);
        } else {
            ie = internalExtension.get(0);
        }
        log.debug("{} - Internal extension: {} registered: {}", tenant,
                ExtensionsComponent.PROCESSOR_EXTENSION_INTERNAL_NAME,
                ie.getId().getValue(), ie);
    }
}