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

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
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
import dynamic.mapper.model.MappingVersion;
import dynamic.mapper.model.MappingVersionCount;
import dynamic.mapper.service.MappingService;
import dynamic.mapper.service.MappingValidationException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
@RequestMapping("/mapping")
@RestController
@Tag(name = "Mapping Controller", description = "API for managing dynamic mappings between external systems and Cumulocity IoT")
public class MappingController {

    private final ConnectorRegistry connectorRegistry;
    private final MappingService mappingService;
    private final ContextService<UserCredentials> contextService;

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
        summary = "Get published version counts for all mappings",
        description = "Returns the number of published versions per mapping in a single inventory scan. "
                    + "Optionally filter by direction.",
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
            description = "Version counts retrieved successfully",
            content = @Content(
                mediaType = "application/json",
                array = @ArraySchema(schema = @Schema(implementation = MappingVersionCount.class))
            )
        ),
        @ApiResponse(responseCode = "500", description = "Internal server error", content = @Content)
    })
    @GetMapping(value = "/version-counts", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<MappingVersionCount>> getVersionCounts(
            @RequestParam(required = false) Direction direction) {
        String tenant = getTenant();
        try {
            List<MappingVersionCount> result = mappingService.getVersionCounts(tenant, direction);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("{} - Failed to retrieve version counts", tenant, e);
            throw new ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Failed to retrieve version counts: " + e.getMessage()
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
        @ApiResponse(responseCode = "422", description = "Mapping validation failed (duplicate topic, conflicting configuration)", content = @Content),
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
                HttpStatus.UNPROCESSABLE_ENTITY,
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
        @ApiResponse(responseCode = "422", description = "Mapping validation failed (duplicate topic, conflicting configuration)", content = @Content),
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
                HttpStatus.UNPROCESSABLE_ENTITY,
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

    // ========== DRAFT Endpoints ==========

    @Operation(
        summary = "Get the draft (working copy) of a mapping",
        description = """
        Returns the unpublished draft for a mapping line, if one exists. Editing a mapping saves to
        this draft and never changes the running/active configuration. Returns 204 when there is no draft.

        **Security:** Requires ROLE_DYNAMIC_MAPPER_ADMIN or ROLE_DYNAMIC_MAPPER_CREATE role.
        """
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Draft returned",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = Mapping.class))),
        @ApiResponse(responseCode = "204", description = "No draft exists for this mapping", content = @Content),
        @ApiResponse(responseCode = "404", description = "Mapping not found", content = @Content)
    })
    @PreAuthorize("hasAnyRole('ROLE_DYNAMIC_MAPPER_ADMIN', 'ROLE_DYNAMIC_MAPPER_CREATE')")
    @GetMapping(value = "/{id}/draft", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Mapping> getDraft(@PathVariable String id) {
        String tenant = getTenant();
        try {
            Mapping draft = mappingService.getDraftMapping(tenant, id);
            if (draft == null) {
                return ResponseEntity.noContent().build();
            }
            return ResponseEntity.ok(draft);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (Exception e) {
            log.error("{} - Failed to get draft for mapping: {}", tenant, id, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "Failed to get draft: " + e.getMessage());
        }
    }

    @Operation(
        summary = "Save edits into the draft of a mapping",
        description = """
        Saves the supplied configuration into the mapping line's draft (working copy) without changing
        the running/active configuration. To apply a draft, publish it as a version and activate that version.

        Optimistic concurrency: include the draft's last `lastUpdate` value in the body; if the stored draft
        has changed since then the request is rejected with 409.

        **Security:** Requires ROLE_DYNAMIC_MAPPER_ADMIN or ROLE_DYNAMIC_MAPPER_CREATE role.
        """
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Draft saved",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = Mapping.class))),
        @ApiResponse(responseCode = "404", description = "Mapping not found", content = @Content),
        @ApiResponse(responseCode = "409", description = "Draft was modified concurrently", content = @Content)
    })
    @PreAuthorize("hasAnyRole('ROLE_DYNAMIC_MAPPER_ADMIN', 'ROLE_DYNAMIC_MAPPER_CREATE')")
    @PutMapping(value = "/{id}/draft", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Mapping> saveDraft(@PathVariable String id, @RequestBody Mapping mapping) {
        String tenant = getTenant();
        try {
            Mapping draft = mappingService.saveDraftMapping(tenant, id, mapping);
            return ResponseEntity.ok(draft);
        } catch (IllegalStateException e) {
            log.warn("{} - Draft conflict for mapping {}: {}", tenant, id, e.getMessage());
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (Exception e) {
            log.error("{} - Failed to save draft for mapping: {}", tenant, id, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "Failed to save draft: " + e.getMessage());
        }
    }

    @Operation(
        summary = "Discard the draft of a mapping",
        description = """
        Permanently deletes the mapping line's current draft (working copy) without affecting
        published versions or the active configuration. No-op when there is no draft.

        **Security:** Requires ROLE_DYNAMIC_MAPPER_ADMIN or ROLE_DYNAMIC_MAPPER_CREATE role.
        """
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "204", description = "Draft discarded (or no draft existed)", content = @Content),
        @ApiResponse(responseCode = "404", description = "Mapping not found", content = @Content)
    })
    @PreAuthorize("hasAnyRole('ROLE_DYNAMIC_MAPPER_ADMIN', 'ROLE_DYNAMIC_MAPPER_CREATE')")
    @DeleteMapping(value = "/{id}/draft")
    public ResponseEntity<Void> deleteDraft(@PathVariable String id) {
        String tenant = getTenant();
        try {
            mappingService.deleteDraftMapping(tenant, id);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (Exception e) {
            log.error("{} - Failed to delete draft for mapping: {}", tenant, id, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "Failed to delete draft: " + e.getMessage());
        }
    }

    // ========== VERSION Endpoints ==========

    @Operation(
        summary = "Publish the draft as a new version",
        description = """
        Freezes the mapping line's current draft into a new immutable version and clears the draft.
        The currently active configuration is captured as a version first if the line has none yet.
        This does not activate the new version; activate it separately via the ACTIVATE_MAPPING operation.

        **Security:** Requires ROLE_DYNAMIC_MAPPER_ADMIN or ROLE_DYNAMIC_MAPPER_CREATE role.
        """
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "201", description = "Version published",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = MappingVersion.class))),
        @ApiResponse(responseCode = "404", description = "Mapping not found", content = @Content),
        @ApiResponse(responseCode = "409", description = "No draft to publish", content = @Content),
        @ApiResponse(responseCode = "422", description = "Draft failed validation", content = @Content)
    })
    @PreAuthorize("hasAnyRole('ROLE_DYNAMIC_MAPPER_ADMIN', 'ROLE_DYNAMIC_MAPPER_CREATE')")
    @PostMapping(value = "/{id}/publish", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<MappingVersion> publishDraft(@PathVariable String id,
            @RequestParam(required = false) String note) {
        String tenant = getTenant();
        try {
            MappingVersion version = mappingService.publishDraft(tenant, id, note);
            return ResponseEntity.status(HttpStatus.CREATED).body(version);
        } catch (MappingValidationException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "Draft validation failed: " + e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (Exception e) {
            log.error("{} - Failed to publish draft for mapping: {}", tenant, id, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "Failed to publish draft: " + e.getMessage());
        }
    }

    @Operation(
        summary = "List versions of a mapping",
        description = """
        Returns all published versions of a mapping line. The active version is the one whose number
        matches the mapping's current `versionNumber`.

        **Security:** Requires ROLE_DYNAMIC_MAPPER_ADMIN or ROLE_DYNAMIC_MAPPER_CREATE role.
        """
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Versions returned"),
        @ApiResponse(responseCode = "404", description = "Mapping not found", content = @Content)
    })
    @PreAuthorize("hasAnyRole('ROLE_DYNAMIC_MAPPER_ADMIN', 'ROLE_DYNAMIC_MAPPER_CREATE')")
    @GetMapping(value = "/{id}/version", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<MappingVersion>> getVersions(@PathVariable String id) {
        String tenant = getTenant();
        try {
            return ResponseEntity.ok(mappingService.listVersions(tenant, id));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    @Operation(
        summary = "Get a specific version of a mapping",
        description = """
        Returns the full configuration of a single published version.

        **Security:** Requires ROLE_DYNAMIC_MAPPER_ADMIN or ROLE_DYNAMIC_MAPPER_CREATE role.
        """
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Version returned",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = MappingVersion.class))),
        @ApiResponse(responseCode = "404", description = "Mapping or version not found", content = @Content)
    })
    @PreAuthorize("hasAnyRole('ROLE_DYNAMIC_MAPPER_ADMIN', 'ROLE_DYNAMIC_MAPPER_CREATE')")
    @GetMapping(value = "/{id}/version/{versionNumber}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<MappingVersion> getVersion(@PathVariable String id, @PathVariable int versionNumber) {
        String tenant = getTenant();
        try {
            MappingVersion version = mappingService.getVersion(tenant, id, versionNumber);
            if (version == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Version " + versionNumber + " not found");
            }
            return ResponseEntity.ok(version);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    @Operation(
        summary = "Update a version's note",
        description = """
        Updates the change note of a published version. The note is the only mutable field of a
        version; all other fields are immutable.

        **Security:** Requires ROLE_DYNAMIC_MAPPER_ADMIN or ROLE_DYNAMIC_MAPPER_CREATE role.
        """
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Note updated",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = MappingVersion.class))),
        @ApiResponse(responseCode = "404", description = "Mapping or version not found", content = @Content)
    })
    @PreAuthorize("hasAnyRole('ROLE_DYNAMIC_MAPPER_ADMIN', 'ROLE_DYNAMIC_MAPPER_CREATE')")
    @PatchMapping(value = "/{id}/version/{versionNumber}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<MappingVersion> updateVersionNote(@PathVariable String id,
            @PathVariable int versionNumber, @RequestParam(required = false) String note) {
        String tenant = getTenant();
        try {
            return ResponseEntity.ok(mappingService.updateVersionNote(tenant, id, versionNumber, note));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    @Operation(
        summary = "Delete a version of a mapping",
        description = """
        Deletes an inactive published version. The active version cannot be deleted.

        **Security:** Requires ROLE_DYNAMIC_MAPPER_ADMIN or ROLE_DYNAMIC_MAPPER_CREATE role.
        """
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "204", description = "Version deleted", content = @Content),
        @ApiResponse(responseCode = "404", description = "Mapping or version not found", content = @Content),
        @ApiResponse(responseCode = "406", description = "Active version cannot be deleted", content = @Content)
    })
    @PreAuthorize("hasAnyRole('ROLE_DYNAMIC_MAPPER_ADMIN', 'ROLE_DYNAMIC_MAPPER_CREATE')")
    @DeleteMapping(value = "/{id}/version/{versionNumber}")
    public ResponseEntity<Void> deleteVersion(@PathVariable String id, @PathVariable int versionNumber) {
        String tenant = getTenant();
        try {
            mappingService.deleteVersion(tenant, id, versionNumber);
            return ResponseEntity.noContent().build();
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.NOT_ACCEPTABLE, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
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
        // A brand-new mapping has no deployment yet, so notifying all connectors is wasteful.
        // Notify only connectors it is already deployed to (covers import/restore scenarios).
        notifyDeployedConnectorsInbound(tenant, mapping, true);

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
        // Only notify connectors where the mapping is deployed; others are unaffected.
        notifyDeployedConnectorsInbound(tenant, mapping, false);

        // Remove old entry then add updated one so stale topics are cleaned from the resolver tree
        try {
            mappingService.removeFromMappingFromCaches(tenant, mapping);
            mappingService.addMappingInboundToCache(tenant, mapping.getId(), mapping);
            log.debug("{} - Updated inbound mapping in cache: {}", tenant, mapping.getId());
        } catch (Exception e) {
            log.error("{} - Failed to update mapping in cache: {}", tenant, mapping.getId(), e);
        }
    }

    /**
     * Notifies only the connectors where this mapping is deployed about a subscription change.
     * Avoids iterating all connectors for operations that affect only a subset.
     */
    private void notifyDeployedConnectorsInbound(String tenant, Mapping mapping, boolean create)
            throws ConnectorRegistryException {
        List<String> deployedConnectorIds = mappingService.getDeploymentMapEntry(tenant, mapping.getIdentifier());
        if (deployedConnectorIds == null || deployedConnectorIds.isEmpty()) {
            log.debug("{} - Mapping {} has no deployed connectors, skipping subscription update",
                    tenant, mapping.getId());
            return;
        }
        for (String connectorId : deployedConnectorIds) {
            try {
                AConnectorClient client = connectorRegistry.getClientForTenant(tenant, connectorId);
                client.updateSubscriptionForInbound(mapping, create, false);
                log.debug("{} - Updated subscription on connector {} after {} mapping: {}",
                        tenant, connectorId, create ? "creating" : "updating", mapping.getId());
            } catch (ConnectorRegistryException e) {
                log.warn("{} - Connector {} not found while updating mapping {}: {}",
                        tenant, connectorId, mapping.getId(), e.getMessage());
            } catch (Exception e) {
                log.error("{} - Failed to update subscription on connector {} for mapping {}: {}",
                        tenant, connectorId, mapping.getId(), e);
            }
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