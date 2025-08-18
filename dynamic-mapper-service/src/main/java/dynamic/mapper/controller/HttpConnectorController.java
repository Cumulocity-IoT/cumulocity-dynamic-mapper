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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import dynamic.mapper.model.Mapping;
import dynamic.mapper.service.ConnectorConfigurationService;
import dynamic.mapper.service.MappingService;
import dynamic.mapper.service.ServiceConfigurationService;

import org.springframework.beans.factory.annotation.Autowired;
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
import com.cumulocity.microservice.security.service.SecurityUserDetails;

import dynamic.mapper.connector.core.callback.ConnectorMessage;
import dynamic.mapper.connector.core.registry.ConnectorRegistry;
import dynamic.mapper.connector.http.HttpClient;
import dynamic.mapper.core.BootstrapService;
import dynamic.mapper.core.C8YAgent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@Tag(name = "HTTP Connector Controller", description = "HTTP endpoint for receiving data from external systems via HTTP/HTTPS. Acts as a webhook receiver that processes incoming messages and routes them through the dynamic mapping system.")
public class HttpConnectorController {

    @Autowired
    ConnectorRegistry connectorRegistry;

    @Autowired
    MappingService mappingService;

    @Autowired
    ConnectorConfigurationService connectorConfigurationService;

    @Autowired
    ServiceConfigurationService serviceConfigurationService;

    @Autowired
    BootstrapService bootstrapService;

    @Autowired
    C8YAgent c8YAgent;

    @Autowired
    private ContextService<UserCredentials> contextService;

    @Operation(
        summary = "Process HTTP connector message",
        description = """
            Receives HTTP messages from external systems and processes them through the dynamic mapping system. 
            This endpoint acts as a webhook receiver that can handle various payload formats (JSON, XML, plain text, binary).
            The path after '/httpConnector' is used as the topic for mapping resolution.
            
            **Path Examples:**
            - POST /httpConnector/sensors/temperature → topic: 'sensors/temperature'
            - PUT /httpConnector/devices/device001/data → topic: 'devices/device001/data'
            - POST /httpConnector → topic: '' (empty, root level)
            
            **Security:** Requires ROLE_DYNAMIC_MAPPER_HTTP_CONNECTOR_CREATE role.
            """,
        requestBody = @RequestBody(
            description = "Message payload in any format (JSON, XML, plain text, binary)",
            required = false,
            content = {
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(type = "object",
                            description = "Any JSON object representing sensor data or device status"),
                    examples = @ExampleObject(
                        name = "JSON Sensor Data",
                        description = "Typical IoT sensor data",
                        value = """
                        {
                          "deviceId": "sensor001",
                          "temperature": 23.5,
                          "humidity": 65.2,
                          "timestamp": "2024-01-15T14:30:00Z"
                        }
                        """
                    )
                ),
                @Content(
                    mediaType = "application/xml",
                    schema = @Schema(type = "object",
                            description = "Any XML object representing sensor data or device status"),
                    examples = @ExampleObject(
                        name = "XML Device Status",
                        description = "Device status in XML format",
                        value = """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <device>
                          <id>device001</id>
                          <status>online</status>
                          <battery>85</battery>
                        </device>
                        """
                    )
                ),
                @Content(
                    mediaType = "text/plain",
                    schema = @Schema(type = "object",
                            description = "Any CSV data representing sensor data or device status"),
                    examples = @ExampleObject(
                        name = "CSV Data",
                        description = "Simple CSV format",
                        value = "sensor001,23.5,65.2,2024-01-15T14:30:00Z"
                    )
                ),
                @Content(
                    mediaType = "application/octet-stream",
                    schema = @Schema(type = "object",
                            description = "Any binary data representing sensor data or device status"),
                    examples = @ExampleObject(
                        name = "Binary Data",
                        description = "Binary payload (e.g., protobuf, custom binary format)"
                    )
                )
            }
        ),
        parameters = {
            @Parameter(
                name = "path",
                description = "Dynamic path that becomes the topic for mapping resolution. Everything after '/httpConnector' is used as the topic.",
                example = "sensors/temperature/data",
                schema = @Schema(type = "string")
            )
        }
    )
    @ApiResponses(value = {
        @ApiResponse(
            responseCode = "200",
            description = "Message processed successfully",
            content = @Content(mediaType = "application/json")
        ),
        @ApiResponse(
            responseCode = "400",
            description = "Bad request - error processing the message",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(
                    type = "object",
                    description = "Error details"
                )
            )
        ),
        @ApiResponse(
            responseCode = "403",
            description = "Forbidden - insufficient permissions or missing required role ROLE_DYNAMIC_MAPPER_HTTP_CONNECTOR_CREATE",
            content = @Content(
                mediaType = "application/json",
                examples = @ExampleObject(
                    value = """
                    {
                      "error": "Authenticated user does not have the required role: ROLE_MAPPING_HTTP_CONNECTOR_CREATE"
                    }
                    """
                )
            )
        ),
        @ApiResponse(
            responseCode = "500",
            description = "Internal server error",
            content = @Content
        )
    })
    @SecurityRequirement(name = "bearerAuth")
    @RequestMapping(
        value = { "/httpConnector", "/httpConnector/**" }, 
        method = { RequestMethod.POST, RequestMethod.PUT }, 
        consumes = MediaType.ALL_VALUE
    )
    @PreAuthorize("hasRole('ROLE_DYNAMIC_MAPPER_HTTP_CONNECTOR_CREATE')")
    public ResponseEntity<?> processGenericMessage(
            @Parameter(hidden = true) HttpServletRequest request) {
        String tenant = contextService.getContext().getTenant();
        String fullPath = request.getRequestURI().substring(request.getContextPath().length());

        log.debug("{} -  HTTPConnector message received. Topic: {}", tenant, fullPath);
        try {
            HttpClient connectorClient = connectorRegistry
                    .getHttpConnectorForTenant(tenant);
            Integer cutOffLength = (Boolean) connectorClient.getConnectorConfiguration()
                    .getProperties().get(HttpClient.PROPERTY_CUTOFF_LEADING_SLASH) ? 1 : 0;
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
            log.error("{} - Error transforming payload: {}", tenant, ex);
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
        log.warn("{} - User {} tried to access HTTPConnectorEndpoint but does not have the required 'ROLE_MAPPING_HTTP_CONNECTOR_CREATE' role",
                tenant, user);
        response.sendError(403, "Authenticated user does not have the required role: ROLE_MAPPING_HTTP_CONNECTOR_CREATE");
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