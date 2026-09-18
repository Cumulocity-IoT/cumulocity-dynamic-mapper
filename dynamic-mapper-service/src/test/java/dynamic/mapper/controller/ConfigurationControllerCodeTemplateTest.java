/*
 * Copyright (c) 2025 Cumulocity GmbH
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * @authors Christof Strack
 */

package dynamic.mapper.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import com.cumulocity.microservice.context.ContextService;
import com.cumulocity.microservice.context.credentials.UserCredentials;
import com.fasterxml.jackson.databind.ObjectMapper;

import dynamic.mapper.configuration.CodeTemplate;
import dynamic.mapper.configuration.ConnectorConfigurationService;
import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.configuration.ServiceConfigurationService;
import dynamic.mapper.configuration.TemplateType;
import dynamic.mapper.connector.core.registry.ConnectorRegistry;
import dynamic.mapper.core.BootstrapService;
import dynamic.mapper.core.ServiceRegistry;
import dynamic.mapper.mapping.MappingService;

/**
 * Covers the code-template endpoints' status codes, which were documented but not reachable.
 *
 * <p>{@code deleteCodeTemplate} dereferenced the looked-up template before null-checking it, and
 * wrapped the whole body in {@code catch (Exception)} — so a missing template produced a 500 from
 * the NPE (making the method's own 404 branch dead code), and the deliberate 406 for internal
 * templates was flattened to a 500 because {@link ResponseStatusException} is a
 * {@link RuntimeException}. {@code updateCodeTemplate} had no read-only check at all: the UI's
 * disabled save button was the only thing preventing SYSTEM from being overwritten.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ConfigurationControllerCodeTemplateTest {

    private static final String TENANT = "t123";

    @Mock
    private ServiceConfigurationService serviceConfigurationService;
    @Mock
    private ServiceRegistry serviceRegistry;

    private ConfigurationController controller;
    private ServiceConfiguration serviceConfiguration;
    private Map<String, CodeTemplate> codeTemplates;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        ContextService<UserCredentials> contextService = mock(ContextService.class);
        UserCredentials credentials = mock(UserCredentials.class);
        when(credentials.getTenant()).thenReturn(TENANT);
        when(contextService.getContext()).thenReturn(credentials);

        controller = new ConfigurationController(
                mock(ConnectorRegistry.class),
                mock(MappingService.class),
                mock(ConnectorConfigurationService.class),
                serviceConfigurationService,
                mock(BootstrapService.class),
                contextService,
                serviceRegistry,
                new ObjectMapper());

        codeTemplates = new HashMap<>();
        serviceConfiguration = new ServiceConfiguration();
        serviceConfiguration.setCodeTemplates(codeTemplates);
        when(serviceConfigurationService.getServiceConfiguration(anyString())).thenReturn(serviceConfiguration);
    }

    private CodeTemplate template(String id, boolean internal, boolean readonly) {
        CodeTemplate t = new CodeTemplate();
        t.id = id;
        t.name = id;
        t.description = "";
        t.templateType = TemplateType.INBOUND_SMART_FUNCTION;
        t.code = "Y29kZQ==";
        t.internal = internal;
        t.readonly = readonly;
        return t;
    }

    // ---- delete ----

    @Test
    void deleteReturns404ForAnUnknownTemplate() {
        codeTemplates.put("known", template("known", false, false));

        ResponseEntity<CodeTemplate> response = controller.deleteCodeTemplate("does-not-exist");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void deleteReturns406ForAnInternalTemplateAndKeepsIt() {
        codeTemplates.put(TemplateType.SYSTEM.name(), template(TemplateType.SYSTEM.name(), true, true));

        ResponseStatusException thrown = assertThrows(ResponseStatusException.class,
                () -> controller.deleteCodeTemplate(TemplateType.SYSTEM.name()));

        assertEquals(HttpStatus.NOT_ACCEPTABLE, thrown.getStatusCode());
        assertNotNull(codeTemplates.get(TemplateType.SYSTEM.name()), "the template must not be removed");
    }

    @Test
    void deleteRemovesACustomTemplateAndReturnsIt() throws Exception {
        codeTemplates.put("custom", template("custom", false, false));

        ResponseEntity<CodeTemplate> response = controller.deleteCodeTemplate("custom");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("custom", response.getBody().id);
        assertEquals(0, codeTemplates.size());
    }

    // ---- update ----

    /**
     * Deliberately uses a read-only template whose id is NOT {@code SYSTEM}: the SYSTEM id also
     * triggers a GraalVM source refresh, and against a mocked ServiceRegistry that throws on its
     * own — which would let this test pass for the wrong reason even with no read-only check.
     */
    @Test
    void updateRefusesAReadOnlyTemplate() throws Exception {
        codeTemplates.put("locked", template("locked", false, true));
        CodeTemplate replacement = template("locked", false, true);
        replacement.code = "b3RoZXI=";

        ResponseStatusException thrown = assertThrows(ResponseStatusException.class,
                () -> controller.updateCodeTemplate("locked", replacement));

        assertEquals(HttpStatus.NOT_ACCEPTABLE, thrown.getStatusCode());
        assertEquals("Y29kZQ==", codeTemplates.get("locked").code, "the stored template must be untouched");
        verify(serviceConfigurationService, never()).saveServiceConfiguration(anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void updateAllowsAnEditableTemplate() {
        codeTemplates.put("custom", template("custom", false, false));
        CodeTemplate replacement = template("custom", false, false);
        replacement.code = "b3RoZXI=";

        ResponseEntity<HttpStatus> response = controller.updateCodeTemplate("custom", replacement);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("b3RoZXI=", codeTemplates.get("custom").code);
    }

    @Test
    void updateRejectsAMismatchBetweenPathAndBodyId() {
        CodeTemplate replacement = template("other", false, false);

        ResponseStatusException thrown = assertThrows(ResponseStatusException.class,
                () -> controller.updateCodeTemplate("custom", replacement));

        assertEquals(HttpStatus.BAD_REQUEST, thrown.getStatusCode());
    }
}
