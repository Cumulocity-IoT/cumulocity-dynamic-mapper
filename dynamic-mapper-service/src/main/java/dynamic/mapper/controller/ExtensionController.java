/*
 * Copyright (c) 2022-2026 Cumulocity GmbH.
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

import java.io.IOException;
import java.util.List;
import java.util.Map;

import dynamic.mapper.core.ExtensionManager;
import dynamic.mapper.core.ExtensionUploadException;
import dynamic.mapper.mapping.MappingService;
import dynamic.mapper.model.Mapping;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.cumulocity.microservice.context.ContextService;
import com.cumulocity.sdk.client.SDKException;
import com.cumulocity.microservice.context.credentials.UserCredentials;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import dynamic.mapper.model.extension.Extension;
import jakarta.validation.constraints.NotBlank;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@Slf4j
@RequiredArgsConstructor
@RequestMapping("/extension")
@RestController
@Tag(name = "Extension Controller", description = "API for managing processor extensions that provide custom data transformation capabilities")
public class ExtensionController {

    private final ContextService<UserCredentials> contextService;
    private final ExtensionManager extensionManager;
    private final MappingService mappingService;

    @Operation(summary = "Get all processor extensions", description = "Retrieves all available processor extensions for the current tenant. Extensions provide custom data transformation and processing capabilities that can be used in mappings.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Extensions retrieved successfully", content = @Content(mediaType = "application/json", schema = @Schema(type = "object", description = "Map of extension names to their configurations"))),
            @ApiResponse(responseCode = "500", description = "Internal server error", content = @Content)
    })
    @PreAuthorize("hasAnyRole('ROLE_DYNAMIC_MAPPER_ADMIN', 'ROLE_DYNAMIC_MAPPER_CREATE')")
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Extension>> getProcessorExtensions() {
        String tenant = contextService.getContext().getTenant();
        Map<String, Extension> result = extensionManager.getProcessorExtensions(tenant);
        return ResponseEntity.status(HttpStatus.OK).body(result);
    }

    @Operation(summary = "Upload a processor extension", description = "Uploads the JAR of a processor extension, registers it under a name and loads it for the current tenant. The JAR must contain an extension-external.yaml that lists its extensions. If no extension can be loaded from the file, a new upload is rolled back. Mappings refer to the extension by its name.\n\n**Security:** Requires ROLE_DYNAMIC_MAPPER_ADMIN role.\n")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Extension uploaded and loaded", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Extension.class))),
            @ApiResponse(responseCode = "400", description = "Invalid name, or the file is not a valid extension JAR", content = @Content),
            @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions to upload extensions", content = @Content),
            @ApiResponse(responseCode = "409", description = "An extension with that name exists and overwrite was not set, or it is built in", content = @Content),
            @ApiResponse(responseCode = "413", description = "The file is larger than 50 MB", content = @Content),
            @ApiResponse(responseCode = "422", description = "The file was stored but no extension could be loaded from it; a new upload is rolled back", content = @Content),
            @ApiResponse(responseCode = "502", description = "Cumulocity refused to store the file; the message carries its reason", content = @Content),
            @ApiResponse(responseCode = "500", description = "Internal server error", content = @Content)
    })
    @PreAuthorize("hasRole('ROLE_DYNAMIC_MAPPER_ADMIN')")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Extension> uploadProcessorExtension(
            @Parameter(description = "The extension JAR", required = true) @RequestParam("file") MultipartFile file,
            @Parameter(description = "The name mappings refer to. Defaults to the file name without \".jar\".", example = "dynamic-mapper-extension") @RequestParam(value = "name", required = false) String name,
            @Parameter(description = "Replace the file of an existing external extension with the same name") @RequestParam(value = "overwrite", defaultValue = "false") boolean overwrite) {
        String tenant = contextService.getContext().getTenant();
        String extensionName = name != null && !name.isBlank() ? name.trim() : defaultName(file.getOriginalFilename());
        try {
            Extension result = extensionManager.uploadProcessorExtension(tenant, extensionName, file.getBytes(),
                    overwrite);
            return ResponseEntity.status(HttpStatus.CREATED).body(result);
        } catch (ExtensionUploadException e) {
            HttpStatus status = switch (e.getReason()) {
                case INVALID -> HttpStatus.BAD_REQUEST;
                case TOO_LARGE -> HttpStatus.PAYLOAD_TOO_LARGE;
                case NAME_TAKEN -> HttpStatus.CONFLICT;
                case NOT_LOADED -> HttpStatus.UNPROCESSABLE_ENTITY;
            };
            throw new ResponseStatusException(status, e.getMessage());
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The file could not be read: " + e.getMessage());
        } catch (SDKException e) {
            // Cumulocity refused to store the file: say so instead of answering with a bare 500 and a stack trace
            log.error("{} - Cumulocity rejected the upload of extension {}: {}", tenant, extensionName, e.getMessage());
            String reason = e.getMessage() != null ? e.getMessage().lines().skip(1).findFirst().orElse(e.getMessage()) : "unknown reason";
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Cumulocity could not store the extension (HTTP " + e.getHttpStatus() + "): " + reason.strip());
        }
    }

    /** The file name without its path and its ".jar" extension. */
    static String defaultName(String originalFilename) {
        if (originalFilename == null) {
            return null;
        }
        String base = originalFilename.substring(Math.max(originalFilename.lastIndexOf('/'),
                originalFilename.lastIndexOf('\\')) + 1);
        return base.toLowerCase().endsWith(".jar") ? base.substring(0, base.length() - 4) : base;
    }

    @Operation(summary = "Get a specific processor extension", description = "Retrieves detailed information about a specific processor extension including its configuration, status, and available entry points.", parameters = {
            @Parameter(name = "extensionName", description = "The unique name of the extension to retrieve", required = true, example = "custom-json-processor", schema = @Schema(type = "string"))
    })
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Extension found and retrieved successfully", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Extension.class))),
            @ApiResponse(responseCode = "404", description = "Extension not found", content = @Content(mediaType = "application/json", schema = @Schema(type = "object", description = "Error details indicating the extension was not found"))),
            @ApiResponse(responseCode = "500", description = "Internal server error", content = @Content)
    })
    @PreAuthorize("hasAnyRole('ROLE_DYNAMIC_MAPPER_ADMIN', 'ROLE_DYNAMIC_MAPPER_CREATE')")
    @GetMapping(value = "/{extensionName}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Extension> getProcessorExtension(
            @PathVariable @NotBlank String extensionName) {
        String tenant = contextService.getContext().getTenant();
        Extension result = extensionManager.getProcessorExtension(tenant, extensionName);
        if (result == null)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Extension with id " + extensionName + " could not be found.");
        return ResponseEntity.status(HttpStatus.OK).body(result);
    }

    @Operation(summary = "Delete a processor extension", description = """
            Deletes a processor extension from the system. This will remove the extension and make it unavailable for use in mappings. Only external extensions can be deleted - built-in extensions cannot be removed.

            **Security:** Requires ROLE_DYNAMIC_MAPPER_ADMIN role.
            """, parameters = {
            @Parameter(name = "extensionName", description = "The unique name of the extension to delete", required = true, example = "custom-json-processor", schema = @Schema(type = "string")),
            @Parameter(name = "force", description = "Delete even if mappings still use the extension; those mappings then fail until they use another extension", schema = @Schema(type = "boolean", defaultValue = "false"))
    })
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Extension deleted successfully", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Extension.class))),
            @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions to delete extensions", content = @Content),
            @ApiResponse(responseCode = "400", description = "Built-in extensions cannot be deleted", content = @Content),
            @ApiResponse(responseCode = "404", description = "Extension not found", content = @Content(mediaType = "application/json", schema = @Schema(type = "object", description = "Error details indicating the extension was not found"))),
            @ApiResponse(responseCode = "409", description = "Extension is still used by mappings; the body has a message and the list of mappings. Repeat with force=true to delete anyway", content = @Content),
            @ApiResponse(responseCode = "500", description = "Internal server error", content = @Content)
    })
    @PreAuthorize("hasRole('ROLE_DYNAMIC_MAPPER_ADMIN')")
    @DeleteMapping(value = "/{extensionName}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> deleteProcessorExtension(@PathVariable String extensionName,
            @RequestParam(name = "force", defaultValue = "false") boolean force) {
        String tenant = contextService.getContext().getTenant();
        Extension registered = extensionManager.getProcessorExtension(tenant, extensionName);
        if (registered == null)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Extension with id " + extensionName + " could not be found.");
        if (!registered.isExternal())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Built-in extension " + extensionName + " cannot be deleted.");

        List<Mapping> usedBy = force ? List.of()
                : mappingService.getMappings(tenant, null).stream()
                        .filter(m -> m.getExtension() != null
                                && extensionName.equals(m.getExtension().getExtensionName()))
                        .sorted(java.util.Comparator.comparing(Mapping::getName,
                                java.util.Comparator.nullsLast(String::compareTo)))
                        .toList();
        if (!usedBy.isEmpty()) {
            // Built here, not with ResponseStatusException: the message must reach the UI whatever the
            // container does with error pages.
            java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("status", HttpStatus.CONFLICT.value());
            body.put("error", HttpStatus.CONFLICT.getReasonPhrase());
            body.put("message", usedByMessage(extensionName, usedBy));
            body.put("mappings", usedBy.stream().map(m -> {
                java.util.Map<String, Object> entry = new java.util.LinkedHashMap<>();
                entry.put("name", m.getName());
                entry.put("id", m.getId());
                entry.put("identifier", m.getIdentifier());
                entry.put("direction", m.getDirection());
                entry.put("active", m.getActive());
                return entry;
            }).toList());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
        }
        if (force) {
            log.warn("{} - Deleting extension {} with force: mappings that use it will fail", tenant, extensionName);
        }

        Extension result = extensionManager.deleteProcessorExtension(tenant, extensionName);
        if (result == null)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Extension with id " + extensionName + " could not be found.");
        return ResponseEntity.status(HttpStatus.OK).body(result);
    }

    private static final int MAX_LISTED_MAPPINGS = 10;

    /** Names each mapping that blocks the delete, with its direction and identifier, so it can be found. */
    static String usedByMessage(String extensionName, List<Mapping> usedBy) {
        String list = usedBy.stream()
                .limit(MAX_LISTED_MAPPINGS)
                .map(m -> String.format("\"%s\" (%s, %s%s)", m.getName(),
                        m.getDirection() == null ? "unknown direction"
                                : m.getDirection().name().toLowerCase(),
                        "id " + m.getIdentifier(),
                        Boolean.TRUE.equals(m.getActive()) ? "" : ", inactive"))
                .collect(java.util.stream.Collectors.joining("; "));
        int more = usedBy.size() - MAX_LISTED_MAPPINGS;
        return "Extension " + extensionName + " is used by " + usedBy.size()
                + (usedBy.size() == 1 ? " mapping: " : " mappings: ") + list
                + (more > 0 ? "; and " + more + " more" : "")
                + ". Change the extension in these mappings or delete them first, or delete the extension anyway.";
    }
}
