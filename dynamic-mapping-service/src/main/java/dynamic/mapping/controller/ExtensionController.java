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

package dynamic.mapping.controller;

import java.util.Map;
import dynamic.mapping.configuration.ConnectorConfigurationComponent;
import dynamic.mapping.configuration.ServiceConfigurationComponent;

import dynamic.mapping.connector.core.registry.ConnectorRegistry;
import dynamic.mapping.core.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.cumulocity.microservice.context.ContextService;
import com.cumulocity.microservice.context.credentials.UserCredentials;
import com.cumulocity.microservice.security.service.RoleService;
import lombok.extern.slf4j.Slf4j;
import dynamic.mapping.model.Extension;
import jakarta.validation.constraints.NotBlank;

@Slf4j
@RequestMapping("/extension")
@RestController
public class ExtensionController {

    @Autowired
    ConnectorRegistry connectorRegistry;

    @Autowired
    MappingComponent mappingComponent;

    @Autowired
    ConnectorConfigurationComponent connectorConfigurationComponent;

    @Autowired
    ServiceConfigurationComponent serviceConfigurationComponent;

    @Autowired
    BootstrapService bootstrapService;

    @Autowired
    C8YAgent c8YAgent;

    @Autowired
    private RoleService roleService;

    @Autowired
    private ContextService<UserCredentials> contextService;

    @Autowired
    private ConfigurationRegistry configurationRegistry;

    @Value("${APP.externalExtensionsEnabled}")
    private boolean externalExtensionsEnabled;

    @Value("${APP.userRolesEnabled}")
    private Boolean userRolesEnabled;

    @Value("${APP.mappingAdminRole}")
    private String mappingAdminRole;

    @Value("${APP.mappingCreateRole}")
    private String mappingCreateRole;

    @GetMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Extension>> getProcessorExtensions() {
        String tenant = contextService.getContext().getTenant();
        Map<String, Extension> result = configurationRegistry.getC8yAgent().getProcessorExtensions(tenant);
        return ResponseEntity.status(HttpStatus.OK).body(result);
    }

    @GetMapping(value = "/{extensionName}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Extension> getProcessorExtension(
            @PathVariable @NotBlank String extensionName) {
        String tenant = contextService.getContext().getTenant();
        Extension result = configurationRegistry.getC8yAgent().getProcessorExtension(tenant, extensionName);
        if (result == null)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Extension with id " + extensionName + " could not be found.");
        return ResponseEntity.status(HttpStatus.OK).body(result);
    }

    @DeleteMapping(value = "/{extensionName}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Extension> deleteProcessorExtension(@PathVariable String extensionName) {
        String tenant = contextService.getContext().getTenant();
        if (!userHasMappingAdminRole()) {
            log.error("Tenant {} - Insufficient Permission, user does not have required permission to access this API",
                    tenant);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Insufficient Permission, user does not have required permission to access this API");
        }
        Extension result = configurationRegistry.getC8yAgent().deleteProcessorExtension(tenant, extensionName);
        if (result == null)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Extension with id " + extensionName + " could not be found.");
        return ResponseEntity.status(HttpStatus.OK).body(result);
    }

    private boolean userHasMappingAdminRole() {
        return !userRolesEnabled || (userRolesEnabled && roleService.getUserRoles().contains(mappingAdminRole));
    }

}