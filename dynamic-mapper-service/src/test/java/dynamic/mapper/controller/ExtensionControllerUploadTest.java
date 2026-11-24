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

package dynamic.mapper.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.cumulocity.microservice.context.ContextService;
import com.cumulocity.microservice.context.credentials.UserCredentials;

import dynamic.mapper.core.ExtensionManager;
import dynamic.mapper.core.ExtensionUploadException;
import dynamic.mapper.core.ExtensionUploadException.Reason;
import dynamic.mapper.mapping.MappingService;
import dynamic.mapper.model.Direction;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.extension.Extension;
import dynamic.mapper.model.extension.ExtensionEntry;

/** POST /extension: the name defaults to the file name, and each rejection reason has its own status. */
class ExtensionControllerUploadTest {

    private static final String TENANT = "t123";

    private ExtensionManager extensionManager;
    private ExtensionController controller;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        ContextService<UserCredentials> contextService = mock(ContextService.class);
        UserCredentials credentials = mock(UserCredentials.class);
        when(credentials.getTenant()).thenReturn(TENANT);
        when(contextService.getContext()).thenReturn(credentials);
        extensionManager = mock(ExtensionManager.class);
        controller = new ExtensionController(contextService, extensionManager, mock(MappingService.class));
    }

    private static MockMultipartFile file(String filename) {
        return new MockMultipartFile("file", filename, "application/java-archive", new byte[] { 1, 2, 3 });
    }

    @Test
    void usesTheFileNameWithoutJarAsTheDefaultName() throws Exception {
        Extension loaded = new Extension("1", "my-extension", true);
        when(extensionManager.uploadProcessorExtension(eq(TENANT), eq("my-extension"), any(), anyBoolean()))
                .thenReturn(loaded);

        ResponseEntity<Extension> response = controller.uploadProcessorExtension(file("my-extension.jar"), null, false);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals(loaded, response.getBody());
        verify(extensionManager).uploadProcessorExtension(TENANT, "my-extension", new byte[] { 1, 2, 3 }, false);
    }

    @Test
    void anExplicitNameAndTheOverwriteFlagArePassedOn() throws Exception {
        when(extensionManager.uploadProcessorExtension(any(), any(), any(), anyBoolean()))
                .thenReturn(new Extension("1", "chosen", true));

        controller.uploadProcessorExtension(file("whatever.jar"), " chosen ", true);

        verify(extensionManager).uploadProcessorExtension(TENANT, "chosen", new byte[] { 1, 2, 3 }, true);
    }

    @Test
    void defaultNameHandlesPathsAndMissingExtensions() {
        assertEquals("a-b", ExtensionController.defaultName("C:\\dir\\a-b.JAR"));
        assertEquals("a-b", ExtensionController.defaultName("/tmp/a-b.jar"));
        assertEquals("plain", ExtensionController.defaultName("plain"));
        assertNull(ExtensionController.defaultName(null));
    }

    @Test
    void mapsEachRejectionReasonToItsStatus() throws Exception {
        assertStatus(Reason.INVALID, HttpStatus.BAD_REQUEST);
        assertStatus(Reason.TOO_LARGE, HttpStatus.PAYLOAD_TOO_LARGE);
        assertStatus(Reason.NAME_TAKEN, HttpStatus.CONFLICT);
        assertStatus(Reason.NOT_LOADED, HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void reportsACumulocityRejectionAs502WithItsReason() throws Exception {
        doThrow(new com.cumulocity.sdk.client.SDKException(400,
                "Http status code: 400\n{message=\"Missing field in object.\"}"))
                .when(extensionManager).uploadProcessorExtension(any(), any(), any(), anyBoolean());

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller.uploadProcessorExtension(file("x.jar"), null, false));

        assertEquals(HttpStatus.BAD_GATEWAY, e.getStatusCode());
        assertEquals(true, e.getReason().contains("Missing field in object"));
    }

    private void assertStatus(Reason reason, HttpStatus expected) throws Exception {
        // doThrow: the mock was stubbed to throw by the previous reason, so when(...) would call it
        doThrow(new ExtensionUploadException(reason, "why")).when(extensionManager)
                .uploadProcessorExtension(any(), any(), any(), anyBoolean());

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller.uploadProcessorExtension(file("x.jar"), null, false));

        assertEquals(expected, e.getStatusCode());
        assertEquals("why", e.getReason());
    }

    private static Mapping mapping(String name, String id, Direction direction, boolean active, String extensionName) {
        Mapping m = new Mapping();
        m.setName(name);
        m.setIdentifier(id);
        m.setDirection(direction);
        m.setActive(active);
        m.setExtension(ExtensionEntry.builder().extensionName(extensionName).build());
        return m;
    }

    @Test
    void refusesToDeleteAnExtensionAndNamesEveryMappingThatUsesIt() {
        MappingService mappingService = mock(MappingService.class);
        ContextService<UserCredentials> contextService = mock(ContextService.class);
        UserCredentials credentials = mock(UserCredentials.class);
        when(credentials.getTenant()).thenReturn(TENANT);
        when(contextService.getContext()).thenReturn(credentials);
        Extension registered = new Extension();
        registered.setExternal(true);
        when(extensionManager.getProcessorExtension(TENANT, "ext")).thenReturn(registered);
        when(mappingService.getMappings(TENANT, null)).thenReturn(java.util.List.of(
                mapping("Zeta", "z-1", Direction.OUTBOUND, false, "ext"),
                mapping("Alpha", "a-1", Direction.INBOUND, true, "ext"),
                mapping("Other", "o-1", Direction.INBOUND, true, "different")));
        ExtensionController c = new ExtensionController(contextService, extensionManager, mappingService);

        ResponseEntity<?> response = c.deleteProcessorExtension("ext", false);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> body = (java.util.Map<String, Object>) response.getBody();
        assertEquals(2, ((java.util.List<?>) body.get("mappings")).size());
        String message = (String) body.get("message");
        assertTrue(message.contains("2 mappings"), message);
        assertTrue(message.contains("\"Alpha\" (inbound, id a-1)"), message);
        assertTrue(message.contains("\"Zeta\" (outbound, id z-1, inactive)"), message);
        assertTrue(message.indexOf("Alpha") < message.indexOf("Zeta"), "sorted by name");
        assertTrue(!message.contains("Other"), message);
        org.mockito.Mockito.verify(extensionManager, org.mockito.Mockito.never()).deleteProcessorExtension(any(), any());
    }

    @Test
    void forceDeletesAnExtensionThatMappingsStillUse() {
        MappingService mappingService = mock(MappingService.class);
        ContextService<UserCredentials> contextService = mock(ContextService.class);
        UserCredentials credentials = mock(UserCredentials.class);
        when(credentials.getTenant()).thenReturn(TENANT);
        when(contextService.getContext()).thenReturn(credentials);
        Extension registered = new Extension();
        registered.setExternal(true);
        when(extensionManager.getProcessorExtension(TENANT, "ext")).thenReturn(registered);
        when(extensionManager.deleteProcessorExtension(TENANT, "ext")).thenReturn(registered);
        when(mappingService.getMappings(TENANT, null)).thenReturn(java.util.List.of(
                mapping("Alpha", "a-1", Direction.INBOUND, true, "ext")));
        ExtensionController c = new ExtensionController(contextService, extensionManager, mappingService);

        ResponseEntity<?> response = c.deleteProcessorExtension("ext", true);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(extensionManager).deleteProcessorExtension(TENANT, "ext");
    }
}
