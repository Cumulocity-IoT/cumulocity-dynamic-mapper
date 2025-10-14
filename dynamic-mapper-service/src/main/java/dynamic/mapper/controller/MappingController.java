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

import dynamic.mapper.connector.core.client.AConnectorClient;
import dynamic.mapper.connector.core.registry.ConnectorRegistry;
import dynamic.mapper.connector.core.registry.ConnectorRegistryException;
import dynamic.mapper.model.Direction;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.service.MappingService;
import dynamic.mapper.service.MappingValidationException;
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
    private ConnectorRegistry connectorRegistry;

    @Autowired
    private MappingService mappingService;

    @Autowired
    private ContextService<UserCredentials> contextService;

    // ========== GET Endpoints ==========

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
        String tenant = getTenant();
        
        try {
            log.debug("{} - Getting mappings with direction: {}", tenant, direction);
            List<Mapping> result = mappingService.getMappings(tenant, direction);
            log.debug("{} - Retrieved {} mappings", tenant, result.size());
            return ResponseEntity.ok(result);
            
        } catch (Exception e) {
            log.error("{} - Failed to retrieve mappings", tenant, e);
            throw new ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR, 
                "Failed to retrieve mappings: " + e.getMessage()
            );
        }
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
        String tenant = getTenant();
        
        try {
            log.debug("{} - Getting mapping: {}", tenant, id);
            Mapping result = mappingService.getMapping(tenant, id);
            
            if (result == null) {
                log.warn("{} - Mapping not found: {}", tenant, id);
                throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, 
                    "Mapping with id " + id + " not found"
                );
            }
            
            return ResponseEntity.ok(result);
            
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("{} - Failed to retrieve mapping: {}", tenant, id, e);
            throw new ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR, 
                "Failed to retrieve mapping: " + e.getMessage()
            );
        }
    }

    // ========== CREATE Endpoint ==========

    @Operation(
        summary = "Create a new mapping", 
        description = """
        Creates a new mapping configuration. The mapping will be created in disabled state by default 
        and needs to be activated separately. For INBOUND mappings, subscriptions will be created 
        across all connectors. For OUTBOUND mappings, the outbound cache will be rebuilt.
        
        **Security:** Requires ROLE_DYNAMIC_MAPPER_ADMIN or ROLE_DYNAMIC_MAPPER_CREATE role.
        """
    )
    @ApiResponses(value = {
        @ApiResponse(
            responseCode = "201", 
            description = "Mapping created successfully", 
            content = @Content(
                mediaType = "application/json", 
                schema = @Schema(implementation = Mapping.class)
            )
        ),
        @ApiResponse(responseCode = "400", description = "Invalid mapping configuration", content = @Content),
        @ApiResponse(responseCode = "403", description = "Insufficient permissions to create mapping", content = @Content),
        @ApiResponse(responseCode = "409", description = "Mapping already exists or conflicts with existing configuration", content = @Content),
        @ApiResponse(responseCode = "500", description = "Internal server error", content = @Content)
    })
    @PreAuthorize("hasAnyRole('ROLE_DYNAMIC_MAPPER_ADMIN', 'ROLE_DYNAMIC_MAPPER_CREATE')")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Mapping> createMapping(
            @Parameter(
                description = "The mapping configuration to create", 
                required = true,
                content = @Content(schema = @Schema(implementation = Mapping.class))
            )
            @Valid @RequestBody Mapping mapping) {
        
        String tenant = getTenant();
        
        try {
            log.info("{} - Creating mapping: {}", tenant, mapping.getMappingTopic());
            log.debug("{} - Mapping details: {}", tenant, mapping);
            
            // New mapping should be disabled by default
            mapping.setActive(false);
            
            // Create the mapping
            Mapping createdMapping = mappingService.createMapping(tenant, mapping);
            
            // Handle post-creation operations
            handleMappingCreation(tenant, createdMapping);
            
            log.info("{} - Successfully created mapping: {} [{}]", 
                tenant, createdMapping.getName(), createdMapping.getId());
            
            return ResponseEntity.status(HttpStatus.CREATED).body(createdMapping);
            
        } catch (MappingValidationException e) {
            log.warn("{} - Mapping validation failed: {}", tenant, e.getMessage());
            throw new ResponseStatusException(
                HttpStatus.CONFLICT, 
                "Mapping validation failed: " + e.getMessage()
            );
            
        } catch (IllegalArgumentException e) {
            log.warn("{} - Invalid mapping data: {}", tenant, e.getMessage());
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, 
                "Invalid mapping data: " + e.getMessage()
            );
            
        } catch (Exception e) {
            log.error("{} - Failed to create mapping", tenant, e);
            throw new ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR, 
                "Failed to create mapping: " + e.getMessage()
            );
        }
    }

    // ========== UPDATE Endpoint ==========

    @Operation(
        summary = "Update an existing mapping", 
        description = """
        Updates an existing mapping configuration. Note that active mappings cannot be updated - 
        they must be deactivated first. For INBOUND mappings, subscriptions will be updated across 
        all connectors. For OUTBOUND mappings, the outbound cache will be rebuilt.
        
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
                schema = @Schema(implementation = Mapping.class))
        ),
        @ApiResponse(responseCode = "400", description = "Invalid mapping configuration", content = @Content),
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
        
        String tenant = getTenant();
        
        try {
            log.info("{} - Updating mapping: {}", tenant, id);
            log.debug("{} - Mapping details: {}", tenant, mapping);
            
            // Ensure the ID matches
            mapping.setId(id);
            
            // Update the mapping
            Mapping updatedMapping = mappingService.updateMapping(tenant, mapping, false, false);
            
            // Handle post-update operations
            handleMappingUpdate(tenant, updatedMapping);
            
            log.info("{} - Successfully updated mapping: {} [{}]", 
                tenant, updatedMapping.getName(), updatedMapping.getId());
            
            return ResponseEntity.ok(updatedMapping);
            
        } catch (MappingValidationException e) {
            log.warn("{} - Mapping validation failed: {}", tenant, e.getMessage());
            throw new ResponseStatusException(
                HttpStatus.CONFLICT, 
                "Mapping validation failed: " + e.getMessage()
            );
            
        } catch (IllegalStateException e) {
            log.warn("{} - Cannot update mapping: {}", tenant, e.getMessage());
            throw new ResponseStatusException(
                HttpStatus.NOT_ACCEPTABLE, 
                "Cannot update mapping: " + e.getMessage()
            );
            
        } catch (IllegalArgumentException e) {
            log.warn("{} - Invalid mapping data: {}", tenant, e.getMessage());
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, 
                "Invalid mapping data: " + e.getMessage()
            );
            
        } catch (Exception e) {
            log.error("{} - Failed to update mapping: {}", tenant, id, e);
            throw new ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR, 
                "Failed to update mapping: " + e.getMessage()
            );
        }
    }

    // ========== DELETE Endpoint ==========

    @Operation(
        summary = "Delete a mapping", 
        description = """
        Deletes a mapping by its unique identifier. This will also remove all associated 
        subscriptions and cache entries. The mapping must be deactivated before deletion.
        
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
        @ApiResponse(responseCode = "403", description = "Insufficient permissions to delete mapping", content = @Content),
        @ApiResponse(responseCode = "404", description = "Mapping not found", content = @Content),
        @ApiResponse(responseCode = "406", description = "Mapping cannot be deleted (e.g., still active)", content = @Content),
        @ApiResponse(responseCode = "500", description = "Internal server error", content = @Content)
    })
    @PreAuthorize("hasAnyRole('ROLE_DYNAMIC_MAPPER_ADMIN', 'ROLE_DYNAMIC_MAPPER_CREATE')")
    @DeleteMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> deleteMapping(@PathVariable String id) {
        String tenant = getTenant();
        
        try {
            log.info("{} - Deleting mapping: {}", tenant, id);
            
            // Delete the mapping (includes cache removal)
            Mapping deletedMapping = mappingService.deleteMapping(tenant, id);
            
            if (deletedMapping == null) {
                log.warn("{} - Mapping not found: {}", tenant, id);
                throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, 
                    "Mapping with id " + id + " not found"
                );
            }
            
            // Handle post-deletion operations (cleanup subscriptions)
            handleMappingDeletion(tenant, deletedMapping);
            
            log.info("{} - Successfully deleted mapping: {} [{}]", 
                tenant, deletedMapping.getName(), deletedMapping.getId());
            
            return ResponseEntity.ok(id);
            
        } catch (ResponseStatusException e) {
            throw e;
            
        } catch (IllegalStateException e) {
            log.warn("{} - Cannot delete mapping: {}", tenant, e.getMessage());
            throw new ResponseStatusException(
                HttpStatus.NOT_ACCEPTABLE, 
                "Cannot delete mapping: " + e.getMessage()
            );
            
        } catch (Exception e) {
            log.error("{} - Failed to delete mapping: {}", tenant, id, e);
            throw new ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR, 
                "Failed to delete mapping: " + e.getMessage()
            );
        }
    }

    // ========== Private Helper Methods ==========

    /**
     * Gets the current tenant from the context
     */
    private String getTenant() {
        return contextService.getContext().getTenant();
    }

    /**
     * Handles post-creation operations for a mapping
     * @throws ConnectorRegistryException 
     */
    private void handleMappingCreation(String tenant, Mapping mapping) throws ConnectorRegistryException {
        if (Direction.OUTBOUND.equals(mapping.getDirection())) {
            handleOutboundMappingCreation(tenant, mapping);
        } else {
            handleInboundMappingCreation(tenant, mapping);
        }
    }

    /**
     * Handles outbound mapping creation
     */
    private void handleOutboundMappingCreation(String tenant, Mapping mapping) {
        try {
            // Rebuild outbound cache using the new service
            mappingService.rebuildMappingCaches(tenant, dynamic.mapper.configuration.ConnectorId.INTERNAL);
            log.debug("{} - Rebuilt outbound cache after creating mapping: {}", tenant, mapping.getId());
            
        } catch (Exception e) {
            log.error("{} - Failed to rebuild outbound cache for mapping: {}", 
                tenant, mapping.getId(), e);
            // Don't throw - mapping is created, cache rebuild can be retried
        }
    }

    /**
     * Handles inbound mapping creation
     * @throws ConnectorRegistryException 
     */
    private void handleInboundMappingCreation(String tenant, Mapping mapping) throws ConnectorRegistryException {
        // Update subscriptions in all connectors
        Map<String, AConnectorClient> clients = connectorRegistry.getClientsForTenant(tenant);
        
        clients.values().forEach(client -> {
            try {
                client.updateSubscriptionForInbound(mapping, true, false);
                log.debug("{} - Updated subscription for connector after creating mapping: {}", 
                    tenant, mapping.getId());
            } catch (Exception e) {
                log.error("{} - Failed to update subscription for connector for mapping: {}", 
                    tenant, mapping.getId(), e);
                // Continue with other connectors
            }
        });
        
        // Add to inbound cache (automatically updates resolver)
        try {
            mappingService.addMappingInboundToCache(tenant, mapping.getId(), mapping);
            log.debug("{} - Added inbound mapping to cache: {}", tenant, mapping.getId());
        } catch (Exception e) {
            log.error("{} - Failed to add mapping to cache: {}", tenant, mapping.getId(), e);
        }
    }

    /**
     * Handles post-update operations for a mapping
     * @throws ConnectorRegistryException 
     */
    private void handleMappingUpdate(String tenant, Mapping mapping) throws ConnectorRegistryException {
        if (Direction.OUTBOUND.equals(mapping.getDirection())) {
            handleOutboundMappingUpdate(tenant, mapping);
        } else {
            handleInboundMappingUpdate(tenant, mapping);
        }
    }

    /**
     * Handles outbound mapping update
     */
    private void handleOutboundMappingUpdate(String tenant, Mapping mapping) {
        try {
            // Rebuild outbound cache
            mappingService.rebuildMappingCaches(tenant, dynamic.mapper.configuration.ConnectorId.INTERNAL);
            log.debug("{} - Rebuilt outbound cache after updating mapping: {}", tenant, mapping.getId());
            
        } catch (Exception e) {
            log.error("{} - Failed to rebuild outbound cache for mapping: {}", 
                tenant, mapping.getId(), e);
        }
    }

    /**
     * Handles inbound mapping update
     * @throws ConnectorRegistryException 
     */
    private void handleInboundMappingUpdate(String tenant, Mapping mapping) throws ConnectorRegistryException {
        // Update subscriptions in all connectors
        Map<String, AConnectorClient> clients = connectorRegistry.getClientsForTenant(tenant);
        
        clients.values().forEach(client -> {
            try {
                client.updateSubscriptionForInbound(mapping, false, false);
                log.debug("{} - Updated subscription for connector after updating mapping: {}", 
                    tenant, mapping.getId());
            } catch (Exception e) {
                log.error("{} - Failed to update subscription for connector for mapping: {}", 
                    tenant, mapping.getId(), e);
            }
        });
        
        // Update cache (automatically updates resolver)
        try {
            mappingService.addMappingInboundToCache(tenant, mapping.getId(), mapping);
            log.debug("{} - Updated inbound mapping in cache: {}", tenant, mapping.getId());
        } catch (Exception e) {
            log.error("{} - Failed to update mapping in cache: {}", tenant, mapping.getId(), e);
        }
    }

    /**
     * Handles post-deletion operations for a mapping
     * @throws ConnectorRegistryException 
     */
    private void handleMappingDeletion(String tenant, Mapping mapping) throws ConnectorRegistryException {
        // Only handle inbound mappings - outbound don't have subscriptions
        if (!Direction.OUTBOUND.equals(mapping.getDirection())) {
            deleteInboundSubscriptions(tenant, mapping);
        }
    }

    /**
     * Deletes subscriptions for an inbound mapping from all connectors
     * @throws ConnectorRegistryException 
     */
    private void deleteInboundSubscriptions(String tenant, Mapping mapping) throws ConnectorRegistryException {
        Map<String, AConnectorClient> clients = connectorRegistry.getClientsForTenant(tenant);
        
        clients.values().forEach(client -> {
            try {
                client.deleteActiveSubscription(mapping);
                log.debug("{} - Deleted subscription for connector after deleting mapping: {}", 
                    tenant, mapping.getId());
            } catch (Exception e) {
                log.error("{} - Failed to delete subscription for connector for mapping: {}", 
                    tenant, mapping.getId(), e);
                // Continue with other connectors
            }
        });
    }
}