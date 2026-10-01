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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.cumulocity.microservice.context.ContextService;
import com.cumulocity.microservice.context.credentials.UserCredentials;
import com.fasterxml.jackson.databind.ObjectMapper;

import dynamic.mapper.connector.core.registry.ConnectorRegistry;
import dynamic.mapper.core.BootstrapService;
import dynamic.mapper.core.C8YAgent;
import dynamic.mapper.core.CacheManager;
import dynamic.mapper.core.ServiceRegistry;
import dynamic.mapper.core.ExtensionManager;
import dynamic.mapper.core.facade.IdentityFacade;
import dynamic.mapper.core.facade.InventoryFacade;
import dynamic.mapper.model.Direction;
import dynamic.mapper.model.Mapping;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import dynamic.mapper.model.Operation;
import org.springframework.web.server.ResponseStatusException;
import dynamic.mapper.model.ServiceOperation;
import dynamic.mapper.configuration.ConnectorConfigurationService;
import dynamic.mapper.mapping.MappingService;
import dynamic.mapper.configuration.ServiceConfigurationService;
import dynamic.mapper.processor.flow.FlowStateStore;
import dynamic.mapper.mapping.deployment.DeploymentMapService;
import dynamic.mapper.mapping.status.MappingStatusService;

/**
 * Unit tests for {@link OperationController}'s {@code ACTIVATE_MAPPING} handling
 * (see {@code handleActivateMapping}), which is otherwise untested. Covers both the
 * primary {@code version} parameter and the backward-compatible {@code versionNumber}
 * alias documented in {@code docs/feature/mapping-versioning.md}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OperationControllerTest {

    private static final String TENANT = "testTenant";

    @Mock private ConnectorRegistry connectorRegistry;
    @Mock private MappingService mappingService;
    @Mock private ConnectorConfigurationService connectorConfigurationService;
    @Mock private ServiceConfigurationService serviceConfigurationService;
    @Mock private BootstrapService bootstrapService;
    @Mock private C8YAgent c8YAgent;
    @Mock private ContextService<UserCredentials> contextService;
    @Mock private ServiceRegistry serviceRegistry;
    @Mock private DeploymentMapService deploymentMapService;
    @Mock private MappingStatusService mappingStatusService;
    @Mock private IdentityFacade identityFacade;
    @Mock private InventoryFacade inventoryFacade;
    @Mock private CacheManager cacheManager;
    @Mock private FlowStateStore flowStateStore;
    @Mock private ExtensionManager extensionManager;
    @Mock private ObjectMapper objectMapper;

    private OperationController controller;

    @BeforeEach
    void setUp() throws Exception {
        controller = new OperationController(connectorRegistry, mappingService, connectorConfigurationService,
                serviceConfigurationService, bootstrapService, c8YAgent, contextService, serviceRegistry,
                cacheManager, deploymentMapService, mappingStatusService, identityFacade, inventoryFacade, flowStateStore,
                extensionManager, objectMapper);

        UserCredentials creds = mock(UserCredentials.class);
        when(creds.getTenant()).thenReturn(TENANT);
        when(contextService.getContext()).thenReturn(creds);

        // ACTIVATE_MAPPING requires ROLE_DYNAMIC_MAPPER_CREATE (or ADMIN) - grant it so the
        // permission check in Utils.userHasMappingCreateRole() passes.
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "tester", "n/a", List.of(new SimpleGrantedAuthority("ROLE_DYNAMIC_MAPPER_CREATE"))));

        // No connectors registered - handleActivateMapping's post-activation subscription
        // reconciliation loop becomes a no-op, keeping the test focused on parameter parsing.
        when(connectorRegistry.getClientsForTenant(TENANT)).thenReturn(new HashMap<>());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private Mapping makeMapping() {
        Mapping mapping = new Mapping();
        mapping.setId("m1");
        mapping.setName("test-mapping");
        mapping.setDirection(Direction.INBOUND);
        return mapping;
    }

    @Test
    void activateMapping_usesVersionParameterWhenSupplied() throws Exception {
        when(mappingService.setActivationMapping(eq(TENANT), eq("m1"), eq(true), eq("2.0.0")))
                .thenReturn(makeMapping());

        Map<String, String> parameters = new HashMap<>();
        parameters.put("id", "m1");
        parameters.put("active", "true");
        parameters.put("version", "2.0.0");
        ServiceOperation operation = new ServiceOperation(Operation.ACTIVATE_MAPPING, parameters);

        ResponseEntity<?> response = controller.runOperation(operation);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        verify(mappingService).setActivationMapping(TENANT, "m1", true, "2.0.0");
    }

    @Test
    void activateMapping_fallsBackToLegacyVersionNumberAliasWhenVersionAbsent() throws Exception {
        when(mappingService.setActivationMapping(eq(TENANT), eq("m1"), eq(true), eq("1.5.0")))
                .thenReturn(makeMapping());

        Map<String, String> parameters = new HashMap<>();
        parameters.put("id", "m1");
        parameters.put("active", "true");
        parameters.put("versionNumber", "1.5.0"); // legacy alias, no "version" key present

        ServiceOperation operation = new ServiceOperation(Operation.ACTIVATE_MAPPING, parameters);

        ResponseEntity<?> response = controller.runOperation(operation);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        verify(mappingService).setActivationMapping(TENANT, "m1", true, "1.5.0");
    }

    @Test
    void activateMapping_versionParameterTakesPrecedenceOverLegacyAlias() throws Exception {
        when(mappingService.setActivationMapping(eq(TENANT), eq("m1"), eq(true), eq("3.0.0")))
                .thenReturn(makeMapping());

        Map<String, String> parameters = new HashMap<>();
        parameters.put("id", "m1");
        parameters.put("active", "true");
        parameters.put("version", "3.0.0");
        parameters.put("versionNumber", "9.9.9"); // must be ignored since "version" is present

        ServiceOperation operation = new ServiceOperation(Operation.ACTIVATE_MAPPING, parameters);

        controller.runOperation(operation);

        verify(mappingService).setActivationMapping(TENANT, "m1", true, "3.0.0");
    }

    @Test
    void activateMapping_passesNullVersionWhenNeitherParameterSupplied() throws Exception {
        when(mappingService.setActivationMapping(eq(TENANT), eq("m1"), eq(false), isNull()))
                .thenReturn(makeMapping());

        Map<String, String> parameters = new HashMap<>();
        parameters.put("id", "m1");
        parameters.put("active", "false");

        ServiceOperation operation = new ServiceOperation(Operation.ACTIVATE_MAPPING, parameters);

        controller.runOperation(operation);

        verify(mappingService).setActivationMapping(TENANT, "m1", false, null);
    }

    @Test
    void operationRequiringAdmin_isRejectedForCreateOnlyUser() {
        // setUp() grants only ROLE_DYNAMIC_MAPPER_CREATE. CONNECT is an admin operation, so the
        // central permission check in runOperation must refuse it.
        ServiceOperation operation = new ServiceOperation();
        operation.setOperation(Operation.CONNECT);
        Map<String, String> parameters = new HashMap<>();
        parameters.put("connectorIdentifier", "c1");
        operation.setParameter(parameters);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.runOperation(operation));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        assertTrue(ex.getReason().contains("ROLE_DYNAMIC_MAPPER_ADMIN"),
                "the refusal should name the role the caller is missing, got: " + ex.getReason());
    }

    @Test
    void everyOperationIsCoveredByThePermissionTable() {
        // OperationController's static initializer throws if an Operation has no entry in
        // REQUIRED_ROLES, which would otherwise mean it runs unguarded. Loading the class here
        // makes that guarantee an explicit, named test rather than an incidental side effect.
        assertDoesNotThrow(() -> Class.forName(OperationController.class.getName(), true,
                OperationController.class.getClassLoader()));
    }
}
