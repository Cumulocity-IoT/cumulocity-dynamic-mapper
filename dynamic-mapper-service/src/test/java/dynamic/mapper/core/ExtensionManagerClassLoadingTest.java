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

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dynamic.mapper.configuration.ExtensionConfiguration;
import dynamic.mapper.model.Direction;
import dynamic.mapper.model.Extension;
import dynamic.mapper.model.ExtensionEntry;
import dynamic.mapper.model.ExtensionStatus;
import dynamic.mapper.model.ExtensionType;
import dynamic.mapper.processor.extension.ProcessorExtensionInbound;
import dynamic.mapper.processor.model.CumulocityObject;
import dynamic.mapper.processor.model.JavaExtensionContext;
import dynamic.mapper.processor.model.Message;
import dynamic.mapper.service.ExtensionInboundRegistry;

class ExtensionManagerClassLoadingTest {

    private static final String TENANT = "testTenant";

    private ExtensionManager extensionManager;
    private ExtensionInboundRegistry extensionInboundRegistry;
    private ExtensionConfiguration extensionConfiguration;

    @BeforeEach
    void setUp() throws Exception {
        extensionInboundRegistry = new ExtensionInboundRegistry();
        extensionConfiguration = new ExtensionConfiguration();
        extensionManager = new ExtensionManager(null, null, extensionInboundRegistry, extensionConfiguration);
    }

    @Test
    void registerExtensionInProcessor_loadsInboundExtensionFromExternalClassLoader() throws Exception {
        extensionConfiguration.setExternalExtensionsAllowedPackage("dynamic.mapper");

        String yaml = """
                extensions:
                  - eventName: testEvent
                    className: '%s'
                    description: test inbound extension
                    version: '1.0.0'
                """.formatted(ValidInboundExtension.class.getName());

        ClassLoader dynamicLoader = new YamlBackedClassLoader(
                "extension-external.yaml",
                yaml,
                ExtensionManagerClassLoadingTest.class.getClassLoader());

        invokeRegisterExtensionInProcessor("ext-1", "my-extension", dynamicLoader, true);

        Extension extension = extensionInboundRegistry.getExtension(TENANT, "my-extension");
        assertNotNull(extension, "Extension should be registered in inbound registry");
        assertEquals(ExtensionStatus.COMPLETE, extension.getLoaded(), "Extension should be marked as loaded");

        ExtensionEntry entry = extension.getExtensionEntries().get("testEvent");
        assertNotNull(entry, "Extension entry should be registered for testEvent");
        assertTrue(entry.getLoaded(), "Extension entry should be loaded");
        assertEquals(Direction.INBOUND, entry.getDirection(), "Direction should be auto-detected as inbound");
        assertEquals(ExtensionType.EXTENSION_INBOUND, entry.getExtensionType(),
                "Extension type should be inbound");
        assertNotNull(entry.getExtensionImplInbound(), "Inbound extension implementation should be set");
    }

    @Test
    void registerExtensionInProcessor_rejectsExtensionOutsideAllowedPackage() throws Exception {
        extensionConfiguration.setExternalExtensionsAllowedPackage("example.allowed.pkg");

        String yaml = """
                extensions:
                  - eventName: restrictedEvent
                    className: '%s'
                    description: disallowed package extension
                    version: '1.0.0'
                """.formatted(ValidInboundExtension.class.getName());

        ClassLoader dynamicLoader = new YamlBackedClassLoader(
                "extension-external.yaml",
                yaml,
                ExtensionManagerClassLoadingTest.class.getClassLoader());

        invokeRegisterExtensionInProcessor("ext-2", "restricted-extension", dynamicLoader, true);

        Extension extension = extensionInboundRegistry.getExtension(TENANT, "restricted-extension");
        assertNotNull(extension, "Extension should still be tracked even when class package is invalid");
        assertEquals(ExtensionStatus.NOT_LOADED, extension.getLoaded(),
                "Extension should be marked as not loaded when package check fails");

        ExtensionEntry entry = extension.getExtensionEntries().get("restrictedEvent");
        assertNotNull(entry, "Extension entry should exist");
        assertFalse(entry.getLoaded(), "Extension entry should be marked as not loaded");
        assertTrue(entry.getMessage().contains("Implementation must be in package"),
                "Failure reason should explain package restriction");
    }

    @Test
    void registerExtensionInProcessor_throwsWhenRegistrationYamlIsMissing() {
        ClassLoader noResourceClassLoader = new ClassLoader(ExtensionManagerClassLoadingTest.class.getClassLoader()) {
            @Override
            public InputStream getResourceAsStream(String name) {
                return null;
            }
        };

        IOException exception = assertThrows(IOException.class,
                () -> invokeRegisterExtensionInProcessor("ext-3", "missing-yaml-extension", noResourceClassLoader,
                        true));

        assertTrue(exception.getMessage().contains("Registration file"),
                "Exception should indicate missing registration resource");
    }

    private void invokeRegisterExtensionInProcessor(String id, String extensionName, ClassLoader dynamicLoader,
            boolean external) throws Exception {
        Method method = ExtensionManager.class.getDeclaredMethod(
                "registerExtensionInProcessor",
                String.class,
                String.class,
                String.class,
                ClassLoader.class,
                boolean.class);
        method.setAccessible(true);

        try {
            method.invoke(extensionManager, TENANT, id, extensionName, dynamicLoader, external);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw e;
        }
    }

    private void setPrivateField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    public static class ValidInboundExtension implements ProcessorExtensionInbound<Object> {
        @Override
        public CumulocityObject[] onMessage(Message<Object> message, JavaExtensionContext context) {
            return new CumulocityObject[0];
        }
    }

    private static class YamlBackedClassLoader extends ClassLoader {
        private final String resourceName;
        private final byte[] yamlBytes;

        YamlBackedClassLoader(String resourceName, String yamlContent, ClassLoader parent) {
            super(parent);
            this.resourceName = resourceName;
            this.yamlBytes = yamlContent.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            if (resourceName.equals(name)) {
                return new ByteArrayInputStream(yamlBytes);
            }
            return super.getResourceAsStream(name);
        }
    }
}
