/*
 * Copyright (c) 2022 Software AG, Darmstadt, Germany and/or Software AG USA Inc., Reston, VA, USA,
 * and/or its subsidiaries and/or its affiliates and/or their licensors.
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
 * @authors Christof Strack, Stefan Witschel
 */

package dynamic.mapping.controller;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import com.cumulocity.microservice.context.credentials.Credentials;
import com.cumulocity.microservice.security.service.SecurityUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import dynamic.mapping.configuration.ConnectorConfigurationComponent;
import dynamic.mapping.configuration.ServiceConfigurationComponent;
import dynamic.mapping.connector.core.callback.ConnectorMessage;
import dynamic.mapping.connector.core.registry.ConnectorRegistry;
import dynamic.mapping.connector.core.registry.ConnectorRegistryException;
import dynamic.mapping.connector.http.HttpClient;
import dynamic.mapping.core.*;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.cumulocity.microservice.context.ContextService;
import com.cumulocity.microservice.context.credentials.UserCredentials;
import com.cumulocity.microservice.security.service.RoleService;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
public class HttpConnectorController {

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

    @Value("${APP.userRolesEnabled}")
    private Boolean userRolesEnabled;

    @Value("${APP.mappingAdminRole}")
    private String mappingAdminRole;

    @Value("${APP.mappingCreateRole}")
    private String mappingCreateRole;

    @Value("${APP.mappingHttpConnectorRole}")
    private String mappingHttpConnectorRole;

    @RequestMapping(value = { "/httpConnector",
            "/httpConnector/**" }, method = { RequestMethod.POST, RequestMethod.PUT }, consumes = MediaType.ALL_VALUE)
    @PreAuthorize("hasRole(@environment.getProperty('APP.mappingHttpConnectorRole'))")
    public ResponseEntity<?> processGenericMessage(HttpServletRequest request) {
        String tenant = contextService.getContext().getTenant();
        String fullPath = request.getRequestURI().substring(request.getContextPath().length());

        log.debug("Tenant {} - Generic HTTP message received. Topic: {}", tenant, fullPath);
        try {
            HttpClient connectorClient = connectorRegistry
                    .getHttpConnectorForTenant(tenant);
            Integer cutOffLength = Boolean.parseBoolean((String) connectorClient.getConnectorConfiguration()
                    .getProperties().get(HttpClient.PROPERTY_CUTOFF_LEADING_SLASH)) ? 1 : 0;
            // Get the path
            String subPath = fullPath.equals(HttpClient.HTTP_CONNECTOR_ABSOLUTE_PATH) ? ""
                    : fullPath.substring(HttpClient.HTTP_CONNECTOR_ABSOLUTE_PATH.length() + cutOffLength);
            // Read the body manually
            byte[] payload = readBody(request);
            // build connectorMessage
            ConnectorMessage connectorMessage = ConnectorMessage.builder()
                    .tenant(tenant)
                    .supportsMessageContext(true)
                    .topic(subPath)
                    .sendPayload(true)
                    .connectorIdentifier(HttpClient.HTTP_CONNECTOR_IDENTIFIER)
                    .payload(payload)
                    .build();

            connectorClient.onMessage(connectorMessage);

            return ResponseEntity.ok().build();
        } catch (Exception ex) {
            log.error("Tenant {} - Error transforming payload: {}", tenant, ex);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getLocalizedMessage());
        }
    }

    @ExceptionHandler(value = { AccessDeniedException.class })
    public void handleAccessDeniedException(HttpServletRequest request, HttpServletResponse response,
            AccessDeniedException accessDeniedException) throws IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        SecurityUserDetails securityUserDetails = ((SecurityUserDetails) auth.getPrincipal());

        String tenant = securityUserDetails.getTenant();
        String user = securityUserDetails.getUsername();
        log.warn("Tenant {} - User {} tried to access HTTPConnectorEndpoint but does not have the required '{}' role",
                tenant, user, this.mappingHttpConnectorRole);
        response.sendError(403, "Authenticated user does not have the required role: " + this.mappingHttpConnectorRole);
    }

    private byte[] readBody(HttpServletRequest request) throws IOException {
        try (InputStream inputStream = request.getInputStream();
                ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {

            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
            return outputStream.toByteArray();
        }
    }

}