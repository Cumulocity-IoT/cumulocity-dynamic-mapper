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

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.cumulocity.microservice.context.ContextService;
import com.cumulocity.microservice.context.credentials.UserCredentials;
import com.fasterxml.jackson.core.JsonProcessingException;

import dynamic.mapper.configuration.ConnectorId;
import dynamic.mapper.connector.core.client.AConnectorClient;
import dynamic.mapper.connector.core.registry.ConnectorRegistry;
import dynamic.mapper.core.BootstrapService;
import dynamic.mapper.core.C8YAgent;
import dynamic.mapper.model.Direction;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.service.ConnectorConfigurationService;
import dynamic.mapper.service.MappingService;
import dynamic.mapper.service.ServiceConfigurationService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;


@Slf4j
@RequestMapping("/mapping")
@RestController
@Tag(name = "Mapping Controller", description = "API for managing dynamic mappings between external systems and Cumulocity IoT")
public class MappingController {

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
        summary = "Get all mappings", 
        description = "Retrieves all mappings for the current tenant. Optionally filter by direction (INBOUND/OUTBOUND).",
        parameters = {
            @Parameter(
                name = "direction", 
                description = "Filter mappings by direction", 
                required = false,
                schema = @Schema(implementation = Direction.class)
            )
        }
    )
    @ApiResponses(value = {
        @ApiResponse(
            responseCode = "200", 
            description = "List of mappings retrieved successfully", 
            content = @Content(
                mediaType = "application/json", 
                array = @ArraySchema(schema = @Schema(implementation = Mapping.class))
            )
        ),
        @ApiResponse(responseCode = "500", description = "Internal server error", content = @Content)
    })
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<Mapping>> getMappings(
            @RequestParam(required = false) Direction direction) {
        String tenant = contextService.getContext().getTenant();
        log.debug("{} - Get mappings", tenant);
        List<Mapping> result = mappingService.getMappings(tenant, direction);
        return ResponseEntity.status(HttpStatus.OK).body(result);
    }

    @Operation(
        summary = "Get a specific mapping", 
        description = "Retrieves a mapping by its unique identifier.",
        parameters = {
            @Parameter(
                name = "id", 
                description = "The unique identifier of the mapping", 
                required = true,
                schema = @Schema(type = "string")
            )
        }
    )
    @ApiResponses(value = {
        @ApiResponse(
            responseCode = "200", 
            description = "Mapping found and retrieved successfully", 
            content = @Content(
                mediaType = "application/json", 
                schema = @Schema(implementation = Mapping.class)
            )
        ),
        @ApiResponse(responseCode = "404", description = "Mapping not found", content = @Content),
        @ApiResponse(responseCode = "500", description = "Internal server error", content = @Content)
    })
    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Mapping> getMapping(@PathVariable String id) {
        String tenant = contextService.getContext().getTenant();
        log.debug("{} - Get mapping: {}", tenant, id);
        Mapping result = mappingService.getMapping(tenant, id);
        if (result == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(result);
        }
        return ResponseEntity.status(HttpStatus.OK).body(result);
    }

    @Operation(
        summary = "Delete a mapping", 
        description = """
        Deletes a mapping by its unique identifier. This will also remove all associated subscriptions and cache entries.
        
        **Security:** Requires ROLE_DYNAMIC_MAPPER_ADMIN or ROLE_DYNAMIC_MAPPER_CREATE role.
        """,
        parameters = {
            @Parameter(
                name = "id", 
                description = "The unique identifier of the mapping to delete", 
                required = true,
                schema = @Schema(type = "string")
            )
        }
    )
    @ApiResponses(value = {
        @ApiResponse(
            responseCode = "200", 
            description = "Mapping deleted successfully", 
            content = @Content(
                mediaType = "application/json", 
                schema = @Schema(type = "string", description = "The ID of the deleted mapping")
            )
        ),
        @ApiResponse(responseCode = "404", description = "Mapping not found", content = @Content),
        @ApiResponse(responseCode = "406", description = "Mapping could not be deleted due to business constraints", content = @Content),
        @ApiResponse(responseCode = "403", description = "Insufficient permissions to delete mapping", content = @Content),
        @ApiResponse(responseCode = "500", description = "Internal server error", content = @Content)
    })
    @PreAuthorize("hasAnyRole('ROLE_DYNAMIC_MAPPER_ADMIN', 'ROLE_DYNAMIC_MAPPER_CREATE')")
    @DeleteMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> deleteMapping(@PathVariable String id) {
        String tenant = contextService.getContext().getTenant();
        log.debug("{} - Delete mapping: {}", tenant, id);
        try {
            final Mapping deletedMapping = mappingService.deleteMapping(tenant, id);
            if (deletedMapping == null)
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Mapping with id " + id + " could not be found");

            mappingService.removeFromMappingFromCaches(tenant, deletedMapping);

            if (!Direction.OUTBOUND.equals(deletedMapping.getDirection())) {
                // FIXME Currently we create mappings in ALL connectors assuming they could
                // occur in all of them.
                Map<String, AConnectorClient> clients = connectorRegistry.getClientsForTenant(tenant);
                clients.keySet().stream().forEach(connector -> {
                    clients.get(connector).deleteActiveSubscription(deletedMapping);
                });
            }
        } catch (Exception ex) {
            log.error("{} - Exception deleting mapping: {}", tenant, id, ex);
            throw new ResponseStatusException(HttpStatus.NOT_ACCEPTABLE, ex.getLocalizedMessage());
        }
        log.info("{} - Mapping {} deleted", tenant, id);

        return ResponseEntity.status(HttpStatus.OK).body(id);
    }

    @Operation(
        summary = "Create a new mapping", 
        description = """
        Creates a new mapping configuration. The mapping will be created in disabled state by default and needs to be activated separately. For INBOUND mappings, subscriptions will be created across all connectors. For OUTBOUND mappings, the outbound cache will be rebuilt.
        
        **Security:** Requires ROLE_DYNAMIC_MAPPER_ADMIN or ROLE_DYNAMIC_MAPPER_CREATE role.
        """
    )
    @ApiResponses(value = {
        @ApiResponse(
            responseCode = "200", 
            description = "Mapping created successfully", 
            content = @Content(
                mediaType = "application/json", 
                schema = @Schema(implementation = Mapping.class)
            )
        ),
        @ApiResponse(responseCode = "400", description = "Invalid mapping configuration or JSON processing error", content = @Content),
        @ApiResponse(responseCode = "403", description = "Insufficient permissions to create mapping", content = @Content),
        @ApiResponse(responseCode = "409", description = "Mapping already exists or conflicts with existing configuration", content = @Content),
        @ApiResponse(responseCode = "500", description = "Internal server error", content = @Content)
    })
    // TODO We might need to add the connector ID here to correlate mappings to
    // exactly one connector
    @PreAuthorize("hasAnyRole('ROLE_DYNAMIC_MAPPER_ADMIN', 'ROLE_DYNAMIC_MAPPER_CREATE')")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Mapping> createMapping(
            @Parameter(
                description = "The mapping configuration to create", 
                required = true,
                content = @Content(schema = @Schema(implementation = Mapping.class))
            )
            @Valid @RequestBody Mapping mapping) {
        try {
            String tenant = contextService.getContext().getTenant();
            log.info("{} - Create mapping: {}", tenant, mapping.getMappingTopic());
            log.debug("{} - Create mapping: {}", tenant, mapping);
            // new mapping should be disabled by default
            mapping.setActive(false);
            final Mapping createdMapping = mappingService.createMapping(tenant, mapping);
            if (Direction.OUTBOUND.equals(createdMapping.getDirection())) {
                mappingService.rebuildMappingOutboundCache(tenant, ConnectorId.INTERNAL);
            } else {
                // FIXME Currently we create mappings in ALL connectors assuming they could
                // occur in all of them.
                Map<String, AConnectorClient> clients = connectorRegistry.getClientsForTenant(tenant);
                clients.keySet().stream().forEach(connector -> {
                    clients.get(connector).updateSubscriptionForInbound(createdMapping, true, false);
                });
                mappingService.removeMappingInboundFromResolver(tenant, createdMapping);
                mappingService.addMappingInboundToResolver(tenant, createdMapping);
                mappingService.addMappingInboundToCache(tenant, createdMapping.getId(), mapping);
            }
            return ResponseEntity.status(HttpStatus.OK).body(createdMapping);
        } catch (Exception ex) {
            if (ex instanceof RuntimeException)
                throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getLocalizedMessage());
            else if (ex instanceof JsonProcessingException)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getLocalizedMessage());
            else
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getLocalizedMessage());
        }
    }

    @Operation(
        summary = "Update an existing mapping", 
        description = """
        Updates an existing mapping configuration. Note that active mappings cannot be updated - they must be deactivated first. For INBOUND mappings, subscriptions will be updated across all connectors. For OUTBOUND mappings, the outbound cache will be rebuilt.
        
        **Security:** Requires ROLE_DYNAMIC_MAPPER_ADMIN or ROLE_DYNAMIC_MAPPER_CREATE role.
        """,
        parameters = {
            @Parameter(
                name = "id", 
                description = "The unique identifier of the mapping to update", 
                required = true,
                schema = @Schema(type = "string")
            )
        }
    )
    @ApiResponses(value = {
        @ApiResponse(
            responseCode = "200", 
            description = "Mapping updated successfully", 
            content = @Content(
                mediaType = "application/json", 
                schema = @Schema(implementation = Mapping.class)
            )
        ),
        @ApiResponse(responseCode = "400", description = "Invalid mapping configuration or JSON processing error", content = @Content),
        @ApiResponse(responseCode = "403", description = "Insufficient permissions to update mapping", content = @Content),
        @ApiResponse(responseCode = "404", description = "Mapping not found", content = @Content),
        @ApiResponse(responseCode = "406", description = "Active mappings cannot be updated", content = @Content),
        @ApiResponse(responseCode = "409", description = "Mapping conflicts with existing configuration", content = @Content),
        @ApiResponse(responseCode = "500", description = "Internal server error", content = @Content)
    })
    @PreAuthorize("hasAnyRole('ROLE_DYNAMIC_MAPPER_ADMIN', 'ROLE_DYNAMIC_MAPPER_CREATE')")
    @PutMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Mapping> updateMapping(
            @PathVariable String id,
            @Parameter(
                description = "The updated mapping configuration", 
                required = true,
                content = @Content(schema = @Schema(implementation = Mapping.class))
            )
            @Valid @RequestBody Mapping mapping) {
        String tenant = contextService.getContext().getTenant();
        try {
            log.info("{} - Update mapping: {}, {}", tenant, mapping, id);
            final Mapping updatedMapping = mappingService.updateMapping(tenant, mapping, false, false);
            if (Direction.OUTBOUND.equals(mapping.getDirection())) {
                mappingService.rebuildMappingOutboundCache(tenant, ConnectorId.INTERNAL);
            } else {
                Map<String, AConnectorClient> clients = connectorRegistry.getClientsForTenant(tenant);
                clients.keySet().stream().forEach(connector -> {
                    clients.get(connector).updateSubscriptionForInbound(updatedMapping, false, false);
                });
                mappingService.removeMappingInboundFromResolver(tenant, mapping);
                mappingService.addMappingInboundToResolver(tenant, mapping);
                mappingService.addMappingInboundToCache(tenant, mapping.getId(), mapping);
            }
            return ResponseEntity.status(HttpStatus.OK).body(mapping);
        } catch (Exception ex) {
            if (ex instanceof IllegalArgumentException) {
                log.error("{} - Updating active mappings is not allowed", tenant, ex);
                throw new ResponseStatusException(HttpStatus.NOT_ACCEPTABLE, ex.getLocalizedMessage());
            } else if (ex instanceof RuntimeException)
                throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getLocalizedMessage());
            else if (ex instanceof JsonProcessingException)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getLocalizedMessage());
            else
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getLocalizedMessage());
        }
    }
}