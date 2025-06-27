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

import java.util.Map;
import dynamic.mapper.configuration.ConnectorConfigurationComponent;
import dynamic.mapper.configuration.ServiceConfigurationComponent;

import dynamic.mapper.connector.core.registry.ConnectorRegistry;
import dynamic.mapper.core.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.cumulocity.microservice.context.ContextService;
import com.cumulocity.microservice.context.credentials.UserCredentials;
import lombok.extern.slf4j.Slf4j;
import dynamic.mapper.model.Extension;
import jakarta.validation.constraints.NotBlank;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@Slf4j
@RequestMapping("/extension")
@RestController
@Tag(name = "Extension Controller", description = "API for managing processor extensions that provide custom data transformation capabilities")
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
    private ContextService<UserCredentials> contextService;

    @Autowired
    private ConfigurationRegistry configurationRegistry;

    @Value("${APP.externalExtensionsEnabled}")
    private boolean externalExtensionsEnabled;

    @Operation(
        summary = "Get all processor extensions",
        description = "Retrieves all available processor extensions for the current tenant. Extensions provide custom data transformation and processing capabilities that can be used in mappings."
    )
    @ApiResponses(value = {
        @ApiResponse(
            responseCode = "200",
            description = "Extensions retrieved successfully",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(
                    type = "object",
                    description = "Map of extension names to their configurations"
                )
            )
        ),
        @ApiResponse(responseCode = "500", description = "Internal server error", content = @Content)
    })
    @GetMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Extension>> getProcessorExtensions() {
        String tenant = contextService.getContext().getTenant();
        Map<String, Extension> result = configurationRegistry.getC8yAgent().getProcessorExtensions(tenant);
        return ResponseEntity.status(HttpStatus.OK).body(result);
    }

    @Operation(
        summary = "Get a specific processor extension",
        description = "Retrieves detailed information about a specific processor extension including its configuration, status, and available entry points.",
        parameters = {
            @Parameter(
                name = "extensionName",
                description = "The unique name of the extension to retrieve",
                required = true,
                example = "custom-json-processor",
                schema = @Schema(type = "string")
            )
        }
    )
    @ApiResponses(value = {
        @ApiResponse(
            responseCode = "200",
            description = "Extension found and retrieved successfully",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = Extension.class)
            )
        ),
        @ApiResponse(
            responseCode = "404",
            description = "Extension not found",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(
                    type = "object",
                    description = "Error details indicating the extension was not found"
                )
            )
        ),
        @ApiResponse(responseCode = "500", description = "Internal server error", content = @Content)
    })
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

    @Operation(
        summary = "Delete a processor extension",
        description = "Deletes a processor extension from the system. This will remove the extension and make it unavailable for use in mappings. Only external extensions can be deleted - built-in extensions cannot be removed.",
        parameters = {
            @Parameter(
                name = "extensionName",
                description = "The unique name of the extension to delete",
                required = true,
                example = "custom-json-processor",
                schema = @Schema(type = "string")
            )
        }
    )
    @ApiResponses(value = {
        @ApiResponse(
            responseCode = "200",
            description = "Extension deleted successfully",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = Extension.class)
            )
        ),
        @ApiResponse(
            responseCode = "403",
            description = "Forbidden - insufficient permissions to delete extensions",
            content = @Content
        ),
        @ApiResponse(
            responseCode = "404",
            description = "Extension not found",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(
                    type = "object",
                    description = "Error details indicating the extension was not found"
                )
            )
        ),
        @ApiResponse(responseCode = "500", description = "Internal server error", content = @Content)
    })
    @PreAuthorize("hasRole('ROLE_DYNAMIC_MAPPER_ADMIN')")
    @DeleteMapping(value = "/{extensionName}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Extension> deleteProcessorExtension(@PathVariable String extensionName) {
        String tenant = contextService.getContext().getTenant();
        Extension result = configurationRegistry.getC8yAgent().deleteProcessorExtension(tenant, extensionName);
        if (result == null)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Extension with id " + extensionName + " could not be found.");
        return ResponseEntity.status(HttpStatus.OK).body(result);
    }
}