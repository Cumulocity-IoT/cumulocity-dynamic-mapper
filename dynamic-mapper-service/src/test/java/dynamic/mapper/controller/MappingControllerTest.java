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

package dynamic.mapper.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.cumulocity.microservice.context.ContextService;
import com.cumulocity.microservice.context.credentials.UserCredentials;

import dynamic.mapper.connector.core.registry.ConnectorRegistry;
import dynamic.mapper.model.Direction;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.MappingVersion;
import dynamic.mapper.model.MappingVersionCount;
import dynamic.mapper.model.ValidationError;
import dynamic.mapper.model.ValidationErrorResponse;
import dynamic.mapper.service.MappingService;
import dynamic.mapper.service.MappingValidationException;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;

/**
 * Covers the mapping-validation error path in {@link MappingController}: the endpoints must let
 * {@link MappingValidationException} propagate unchanged (not re-wrap it in a doubled-prefixed
 * {@code ResponseStatusException} message, as they previously did) so
 * {@link MappingController#handleMappingValidationException} produces a single, structured 422
 * body carrying the raw {@link ValidationError} codes for the frontend to translate.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MappingControllerTest {

    private static final String TENANT = "testTenant";

    @Mock
    private ConnectorRegistry connectorRegistry;

    @Mock
    private MappingService mappingService;

    @Mock
    private ContextService<UserCredentials> contextService;

    private MappingController controller;

    @BeforeEach
    void setUp() {
        controller = new MappingController(connectorRegistry, mappingService, contextService);

        UserCredentials creds = mock(UserCredentials.class);
        when(creds.getTenant()).thenReturn(TENANT);
        when(contextService.getContext()).thenReturn(creds);
    }

    private Mapping makeMapping() {
        Mapping mapping = new Mapping();
        mapping.setId("m1");
        mapping.setName("test-mapping");
        mapping.setDirection(Direction.INBOUND);
        return mapping;
    }

    @Test
    void handleMappingValidationException_returns422WithStructuredErrors() {
        MappingValidationException ex = new MappingValidationException(
                List.of(ValidationError.Source_Template_Must_Be_Valid_JSON, ValidationError.Only_One_Multi_Level_Wildcard));

        ResponseEntity<ValidationErrorResponse> response = controller.handleMappingValidationException(ex);

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, response.getStatusCode());
        assertEquals("Mapping validation failed", response.getBody().getMessage());
        assertEquals(
                List.of(ValidationError.Source_Template_Must_Be_Valid_JSON, ValidationError.Only_One_Multi_Level_Wildcard),
                response.getBody().getErrors());
    }

    @Test
    void createMapping_letsValidationExceptionPropagateUnwrapped() {
        MappingValidationException ex = new MappingValidationException(List.of(ValidationError.Source_Template_Must_Be_Valid_JSON));
        when(mappingService.createMapping(anyString(), any(Mapping.class))).thenThrow(ex);

        MappingValidationException thrown = assertThrows(MappingValidationException.class,
                () -> controller.createMapping(makeMapping()));

        // Not re-wrapped into a ResponseStatusException with a doubled "Mapping validation
        // failed: Mapping validation failed: ..." message — the same exception (and its
        // structured error list) propagates for handleMappingValidationException to handle.
        assertEquals(List.of(ValidationError.Source_Template_Must_Be_Valid_JSON), thrown.getErrors());
    }

    @Test
    void updateMapping_letsValidationExceptionPropagateUnwrapped() {
        MappingValidationException ex = new MappingValidationException(List.of(ValidationError.Only_One_Multi_Level_Wildcard));
        when(mappingService.updateMapping(anyString(), any(Mapping.class), anyBoolean(), anyBoolean())).thenThrow(ex);

        MappingValidationException thrown = assertThrows(MappingValidationException.class,
                () -> controller.updateMapping("m1", makeMapping()));

        assertEquals(List.of(ValidationError.Only_One_Multi_Level_Wildcard), thrown.getErrors());
    }

    @Test
    void publishDraft_letsValidationExceptionPropagateUnwrapped() {
        MappingValidationException ex = new MappingValidationException(List.of(ValidationError.FilterOutbound_Must_Be_Unique));
        when(mappingService.publishDraft(anyString(), anyString(), anyString(), any())).thenThrow(ex);

        MappingValidationException thrown = assertThrows(MappingValidationException.class,
                () -> controller.publishDraft("m1", "1.0.0", null));

        assertEquals(List.of(ValidationError.FilterOutbound_Must_Be_Unique), thrown.getErrors());
    }

    // ========== GET /{id}/draft ==========

    @Test
    void getDraft_returns204WhenNoDraftExists() {
        when(mappingService.getDraftMapping(TENANT, "m1")).thenReturn(null);

        ResponseEntity<Mapping> response = controller.getDraft("m1");

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        assertNull(response.getBody());
    }

    @Test
    void getDraft_returnsDraftBodyWhenOneExists() {
        Mapping draft = makeMapping();
        draft.setName("draft-copy");
        when(mappingService.getDraftMapping(TENANT, "m1")).thenReturn(draft);

        ResponseEntity<Mapping> response = controller.getDraft("m1");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("draft-copy", response.getBody().getName());
    }

    // ========== PATCH /{id}/version/{version} ==========

    @Test
    void updateVersionNote_updatesTheNote() {
        MappingVersion updated = MappingVersion.builder()
                .identifier("m1").version("1.0.0").note("new note").build();
        when(mappingService.updateVersionNote(TENANT, "m1", "1.0.0", "new note")).thenReturn(updated);

        ResponseEntity<MappingVersion> response = controller.updateVersionNote("m1", "1.0.0", "new note");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("new note", response.getBody().getNote());
        verify(mappingService).updateVersionNote(TENANT, "m1", "1.0.0", "new note");
    }

    // ========== DELETE /{id}/version/{version} ==========

    @Test
    void deleteVersion_deletesAnInactiveVersion() {
        ResponseEntity<Void> response = controller.deleteVersion("m1", "1.0.0");

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(mappingService).deleteVersion(TENANT, "m1", "1.0.0");
    }

    // ========== GET /version-counts ==========

    @Test
    void getVersionCounts_returnsCountsFromService() {
        List<MappingVersionCount> counts = List.of(
                new MappingVersionCount("m1", 3L),
                new MappingVersionCount("m2", 0L));
        when(mappingService.getVersionCounts(TENANT, null)).thenReturn(counts);

        ResponseEntity<List<MappingVersionCount>> response = controller.getVersionCounts(null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(counts, response.getBody());
        assertTrue(response.getBody().stream().anyMatch(c -> c.id().equals("m1") && c.versionCount() == 3L));
    }
}
