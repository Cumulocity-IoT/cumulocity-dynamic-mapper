/*
 * Copyright (c) 2022-2026 Cumulocity GmbH.
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.cumulocity.model.idtype.GId;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.cumulocity.sdk.client.inventory.BinariesApi;

import dynamic.mapper.configuration.ExtensionConfiguration;
import dynamic.mapper.core.ExtensionUploadException.Reason;
import dynamic.mapper.model.extension.Extension;
import dynamic.mapper.model.extension.ExtensionStatus;
import dynamic.mapper.processor.extension.ExtensionsComponent;

/** The upload stores the JAR, registers it under a name, loads it, and rolls a new upload back when it cannot load. */
class ExtensionManagerUploadTest {

    private static final String TENANT = "testTenant";

    private BinariesApi binaryApi;
    private ExtensionsComponent extensionsComponent;
    private ExtensionInboundRegistry registry;
    private ExtensionManager manager;
    /** What the inventory holds: the managed objects the mocked binary API created. */
    private List<ManagedObjectRepresentation> stored;
    /** What the binary repository serves when the manager downloads the file. */
    private byte[] storedJar;

    @BeforeEach
    void setUp() {
        binaryApi = mock(BinariesApi.class);
        extensionsComponent = mock(ExtensionsComponent.class);
        registry = new ExtensionInboundRegistry();
        ExtensionConfiguration configuration = new ExtensionConfiguration();
        configuration.setExternalExtensionsAllowedPackage("dynamic.mapper");
        manager = new ExtensionManager(binaryApi, extensionsComponent, registry, configuration);

        stored = new ArrayList<>();
        when(extensionsComponent.get()).thenAnswer(invocation -> List.copyOf(stored));
        when(binaryApi.uploadFile(any(ManagedObjectRepresentation.class), any(byte[].class))).thenAnswer(invocation -> {
            ManagedObjectRepresentation mo = invocation.getArgument(0);
            mo.setId(GId.asGId("100"));
            storedJar = invocation.getArgument(1);
            stored.add(mo);
            return mo;
        });
        when(binaryApi.downloadFile(any(GId.class))).thenAnswer(invocation -> new ByteArrayInputStream(storedJar));
    }

    private static byte[] jarWith(String className) throws Exception {
        String yaml = """
                extensions:
                  - eventName: testEvent
                    className: '%s'
                    description: test
                    version: '1.0.0'
                """.formatted(className);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("extension-external.yaml"));
            zip.write(yaml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return out.toByteArray();
    }

    private static byte[] validJar() throws Exception {
        return jarWith(ExtensionManagerClassLoadingTest.ValidInboundExtension.class.getName());
    }

    @Test
    void storesRegistersAndLoadsTheExtension() throws Exception {
        Extension result = manager.uploadProcessorExtension(TENANT, "my-extension", validJar(), false);

        assertEquals("my-extension", result.getName());
        assertEquals(ExtensionStatus.COMPLETE, result.getLoaded());
        assertNotNull(registry.getExtension(TENANT, "my-extension"));

        // The binaries API refuses an object without name and type (HTTP 400 on a tenant)
        assertEquals("my-extension", stored.get(0).getName());
        assertEquals("application/java-archive", stored.get(0).getType());

        // The record carries the fragment the loader and the UI read: name and external
        Object fragment = stored.get(0).get(ExtensionsComponent.PROCESSOR_EXTENSION_TYPE);
        assertEquals(Map.of("name", "my-extension", "external", true), fragment);
    }

    @Test
    void rejectsAnUnusableNameWithoutTouchingTheRepository() throws Exception {
        for (String name : new String[] { null, "", "has space", "../escape", "-leading", "a".repeat(101) }) {
            ExtensionUploadException e = assertThrows(ExtensionUploadException.class,
                    () -> manager.uploadProcessorExtension(TENANT, name, validJar(), false));
            assertEquals(Reason.INVALID, e.getReason(), "name: " + name);
        }
        verify(binaryApi, never()).uploadFile(any(ManagedObjectRepresentation.class), any(byte[].class));
    }

    @Test
    void rejectsAnEmptyFileAndAFileWithoutTheExtensionManifest() throws Exception {
        assertEquals(Reason.INVALID, assertThrows(ExtensionUploadException.class,
                () -> manager.uploadProcessorExtension(TENANT, "ext", new byte[0], false)).getReason());

        ExtensionUploadException notAJar = assertThrows(ExtensionUploadException.class,
                () -> manager.uploadProcessorExtension(TENANT, "ext", "not a jar".getBytes(), false));
        assertEquals(Reason.INVALID, notAJar.getReason());
        assertTrue(notAJar.getMessage().contains("extension-external.yaml"));

        verify(binaryApi, never()).uploadFile(any(ManagedObjectRepresentation.class), any(byte[].class));
    }

    @Test
    void refusesToReplaceAnExistingExtensionUnlessAsked() throws Exception {
        manager.uploadProcessorExtension(TENANT, "my-extension", validJar(), false);

        ExtensionUploadException e = assertThrows(ExtensionUploadException.class,
                () -> manager.uploadProcessorExtension(TENANT, "my-extension", validJar(), false));

        assertEquals(Reason.NAME_TAKEN, e.getReason());
        verify(binaryApi, never()).replaceFile(any(GId.class), anyString(), any());
    }

    @Test
    void replacesTheFileOfAnExistingExtensionWhenAskedTo() throws Exception {
        manager.uploadProcessorExtension(TENANT, "my-extension", validJar(), false);

        Extension result = manager.uploadProcessorExtension(TENANT, "my-extension", validJar(), true);

        assertEquals(ExtensionStatus.COMPLETE, result.getLoaded());
        verify(binaryApi).replaceFile(any(GId.class), anyString(), any());
        assertEquals(1, stored.size(), "No second record is created");
    }

    @Test
    void refusesANameThatBelongsToABuiltInExtension() throws Exception {
        registry.replaceExtensions(TENANT, Map.of("built-in", new Extension("1", "built-in", false)));

        ExtensionUploadException e = assertThrows(ExtensionUploadException.class,
                () -> manager.uploadProcessorExtension(TENANT, "built-in", validJar(), true));

        assertEquals(Reason.NAME_TAKEN, e.getReason());
        verify(binaryApi, never()).uploadFile(any(ManagedObjectRepresentation.class), any(byte[].class));
    }

    @Test
    void rollsBackANewUploadThatCannotBeLoaded() throws Exception {
        // The class is outside the allowed package, so the extension loads as NOT_LOADED
        byte[] unloadable = jarWith("java.lang.Object");

        ExtensionUploadException e = assertThrows(ExtensionUploadException.class,
                () -> manager.uploadProcessorExtension(TENANT, "broken", unloadable, false));

        assertEquals(Reason.NOT_LOADED, e.getReason());
        verify(binaryApi).deleteFile(any(GId.class));
    }
}
