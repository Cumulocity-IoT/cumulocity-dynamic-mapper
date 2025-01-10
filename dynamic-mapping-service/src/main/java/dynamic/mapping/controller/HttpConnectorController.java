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
import jakarta.servlet.http.HttpServletRequest;
import dynamic.mapping.configuration.ConnectorConfigurationComponent;
import dynamic.mapping.configuration.ServiceConfigurationComponent;
import dynamic.mapping.connector.core.callback.ConnectorMessage;
import dynamic.mapping.connector.core.registry.ConnectorRegistry;
import dynamic.mapping.connector.core.registry.ConnectorRegistryException;
import dynamic.mapping.connector.http.HttpClient;
import dynamic.mapping.core.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.cumulocity.microservice.context.ContextService;
import com.cumulocity.microservice.context.credentials.UserCredentials;
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
    private ContextService<UserCredentials> contextService;

    @Value("${APP.externalExtensionsEnabled}")
    private boolean externalExtensionsEnabled;

    @Value("${APP.userRolesEnabled}")
    private Boolean userRolesEnabled;

    @Value("${APP.mappingAdminRole}")
    private String mappingAdminRole;

    @Value("${APP.mappingCreateRole}")
    private String mappingCreateRole;

    @RequestMapping(value = { "/httpConnector",
            "/httpConnector/**" }, method = { RequestMethod.POST, RequestMethod.PUT }, consumes = MediaType.ALL_VALUE)
    public ResponseEntity<?> processGenericMessage(HttpServletRequest request) {
        // Get the path
        String fullPath = request.getRequestURI().substring(request.getContextPath().length());
        String subPath = fullPath.equals(HttpClient.HTTP_CONNECTOR_ABSOLUTE_PATH) ? ""
                : fullPath.substring(HttpClient.HTTP_CONNECTOR_ABSOLUTE_PATH.length());
        String tenant = contextService.getContext().getTenant();
        log.debug("Tenant {} - Generic message : {}, {}, {}", tenant, subPath);
        try {
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
            try {
                HttpClient connectorClient = connectorRegistry
                        .getHttpConnectorForTenant(tenant);
                connectorClient.onMessage(connectorMessage);
            } catch (ConnectorRegistryException e) {
                throw new RuntimeException(e);
            }
            return ResponseEntity.ok()
                    .body("Processed request for path: " + subPath);
        } catch (Exception ex) {
            log.error("Tenant {} - Error transforming payload: {}", tenant, ex);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getLocalizedMessage());
        }
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