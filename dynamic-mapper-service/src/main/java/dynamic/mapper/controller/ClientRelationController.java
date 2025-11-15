/*
 * Copyright (c) 2025 Cumulocity GmbH.
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

import com.cumulocity.microservice.context.ContextService;
import com.cumulocity.microservice.context.credentials.UserCredentials;

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.model.MapEntry;
import dynamic.mapper.exception.OutboundMappingDisabledException;
import dynamic.mapper.exception.DeviceNotFoundException;
import dynamic.mapper.service.ServiceConfigurationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import dynamic.mapper.core.ConfigurationRegistry;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import jakarta.validation.Valid;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@Slf4j
@RequestMapping("/relation")
@RestController
@RequiredArgsConstructor
@Tag(name = "Client Relation Controller", description = "API for managing relations from devices to MQTT clients for outbound mappings")
public class ClientRelationController {

    private final ContextService<UserCredentials> contextService;
    private final ConfigurationRegistry configurationRegistry;
    private final ServiceConfigurationService serviceConfigurationService;

    private static final String OUTBOUND_MAPPING_DISABLED_MESSAGE = "Outbound relation is disabled!";
    private static final String ADMIN_CREATE_ROLES = "hasAnyRole('ROLE_DYNAMIC_MAPPER_ADMIN', 'ROLE_DYNAMIC_MAPPER_CREATE')";

    @Operation(summary = "Update client relation for client")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Client relation updated successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid request"),
            @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions"),
            @ApiResponse(responseCode = "404", description = "Device not found or outbound relation disabled"),
            @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    @PreAuthorize(ADMIN_CREATE_ROLES)
    @PutMapping(value = "/client/{clientId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> addOrUpdateClientRelations(
            @PathVariable String clientId,
            @Valid @RequestBody List<String> deviceIds) {

        String tenant = getTenant();
        validateOutboundMappingEnabled(tenant);

        try {

            // Add client relation
            configurationRegistry.addOrUpdateClientRelations(tenant, clientId, deviceIds);

            log.info("{} - Successfully updated client relations: client {}",
                    tenant, clientId, deviceIds);

            Map<String, Object> response = Map.of(
                    "clientId", clientId,
                    "deviceIds", deviceIds);

            return ResponseEntity.ok(response);

        } catch (DeviceNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (ResponseStatusException e) {
            // Let ResponseStatusException bubble up - Spring will handle it
            throw e;
        } catch (Exception e) {
            log.error("{} - Error updating relations for client {}: {}", tenant, clientId, e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getLocalizedMessage());
        }
    }

    @Operation(summary = "Get all client relations")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "All client relations retrieved successfully"),
            @ApiResponse(responseCode = "404", description = "Outbound mapping disabled"),
            @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    @GetMapping(value = "/client", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> getAllClientRelations() {
        String tenant = getTenant();
        validateOutboundMappingEnabled(tenant);

        try {
            Map<String, String> allRelations = configurationRegistry.getAllClientRelations(tenant);

            // Convert Map to List of MapEntry
            List<MapEntry> relationsList = allRelations.entrySet().stream()
                    .map(entry -> new MapEntry(entry.getKey(), entry.getValue()))
                    .collect(Collectors.toList());

            Map<String, Object> response = Map.of(
                    "totalRelations", relationsList.size(),
                    "relations", relationsList);

            log.debug("{} - Retrieved {} client relations", tenant, relationsList.size());
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("{} - Error retrieving all client relations: {}", tenant, e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getLocalizedMessage());
        }
    }

    @Operation(summary = "Get all devices mapped to a specific client")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Devices for client retrieved successfully"),
            @ApiResponse(responseCode = "404", description = "Client not found or outbound mapping disabled"),
            @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    @GetMapping(value = "/client/{clientId}/devices", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> getDevicesForClient(@PathVariable String clientId) {
        String tenant = getTenant();
        validateOutboundMappingEnabled(tenant);

        try {
            List<String> devices = configurationRegistry.getDevicesForClient(tenant, clientId);

            if (devices.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No devices found for client " + clientId);
            }

            Map<String, Object> response = Map.of(
                    "clientId", clientId,
                    "deviceCount", devices.size(),
                    "devices", devices);

            log.debug("{} - Retrieved {} devices for client {}", tenant, devices.size(), clientId);
            return ResponseEntity.ok(response);

        } catch (ResponseStatusException e) {
            // Let ResponseStatusException bubble up - Spring will handle it
            throw e;
        } catch (Exception e) {
            log.error("{} - Error retrieving devices for client {}: {}", tenant, clientId, e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getLocalizedMessage());
        }
    }

    @Operation(summary = "Remove client mapping for specific device")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Client mapping deleted successfully"),
            @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions"),
            @ApiResponse(responseCode = "404", description = "Device not found, client mapping not found, or outbound mapping disabled"),
            @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    @PreAuthorize(ADMIN_CREATE_ROLES)
    @DeleteMapping(value = "/device/{deviceId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, String>> removeRelationForDevice(@PathVariable String deviceId) {
        String tenant = getTenant();
        validateOutboundMappingEnabled(tenant);

        try {
            // Check if client mapping exists before attempting to remove
            String existingClientId = configurationRegistry.resolveDeviceToClient(tenant, deviceId);
            if (existingClientId == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No client mapping found for device " + deviceId);
            }

            // Remove client mapping for this specific device
            configurationRegistry.removeClientRelation(tenant, deviceId);

            log.info("{} - Successfully removed client mapping: device {} (was mapped to client {})",
                    tenant, deviceId, existingClientId);

            Map<String, String> response = Map.of(
                    "deviceId", deviceId,
                    "previousClientId", existingClientId,
                    "status", "removed");

            return ResponseEntity.ok(response);

        } catch (DeviceNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (ResponseStatusException e) {
            // Let ResponseStatusException bubble up - Spring will handle it
            throw e;
        } catch (Exception e) {
            log.error("{} - Error removing client mapping for device {}: {}", tenant, deviceId, e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getLocalizedMessage());
        }
    }

    @Operation(summary = "Remove all device mappings for a specific client")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "All mappings for client removed successfully"),
            @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions"),
            @ApiResponse(responseCode = "404", description = "Client not found or outbound mapping disabled"),
            @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    @PreAuthorize(ADMIN_CREATE_ROLES)
    @DeleteMapping(value = "/client/{clientId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> removeAllRelationsForClient(@PathVariable String clientId) {
        String tenant = getTenant();
        validateOutboundMappingEnabled(tenant);

        try {
            // Get devices mapped to this client before removing
            List<String> devicesForClient = configurationRegistry.getDevicesForClient(tenant, clientId);

            if (devicesForClient.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No device mappings found for client " + clientId);
            }

            // Remove all mappings for this client
            configurationRegistry.removeClientById(tenant, clientId);

            log.info("{} - Successfully removed all mappings for client {}: {} devices affected",
                    tenant, clientId, devicesForClient.size());

            Map<String, Object> response = Map.of(
                    "clientId", clientId,
                    "removedDeviceCount", devicesForClient.size(),
                    "removedDevices", devicesForClient,
                    "status", "removed");

            return ResponseEntity.ok(response);

        } catch (ResponseStatusException e) {
            // Let ResponseStatusException bubble up - Spring will handle it
            throw e;
        } catch (Exception e) {
            log.error("{} - Error removing all mappings for client {}: {}", tenant, clientId, e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getLocalizedMessage());
        }
    }

    @Operation(summary = "Get client mapping for device")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Client mapping retrieved successfully"),
            @ApiResponse(responseCode = "404", description = "Device not found, client mapping not found, or outbound mapping disabled"),
            @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    @GetMapping(value = "/device/{deviceId}/client", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, String>> getClientForDevice(@PathVariable String deviceId) {
        String tenant = getTenant();
        validateOutboundMappingEnabled(tenant);

        try {
            // Resolve client mapping
            String clientId = configurationRegistry.resolveDeviceToClient(tenant, deviceId);
            if (clientId == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No client mapping found for device " + deviceId);
            }

            log.debug("{} - Retrieved client mapping: device {} -> client {}", tenant, deviceId, clientId);

            Map<String, String> response = Map.of(
                    "deviceId", deviceId,
                    "clientId", clientId);

            return ResponseEntity.ok(response);

        } catch (DeviceNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (ResponseStatusException e) {
            // Let ResponseStatusException bubble up - Spring will handle it
            throw e;
        } catch (Exception e) {
            log.error("{} - Error retrieving client mapping for device {}: {}", tenant, deviceId, e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getLocalizedMessage());
        }
    }

    @Operation(summary = "Get all clients")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "All clients retrieved successfully"),
            @ApiResponse(responseCode = "404", description = "Outbound mapping disabled"),
            @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    @GetMapping(value = "/clients", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> getAllClients() {
        String tenant = getTenant();
        validateOutboundMappingEnabled(tenant);

        try {
            List<String> allClients = configurationRegistry.getAllClients(tenant);

            Map<String, Object> response = Map.of(
                    "clientCount", allClients.size(),
                    "clients", allClients);

            log.debug("{} - Retrieved {} unique clients", tenant, allClients.size());
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("{} - Error retrieving all clients: {}", tenant, e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getLocalizedMessage());
        }
    }

    @Operation(summary = "Clear all client relations")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "All client relations cleared successfully"),
            @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions"),
            @ApiResponse(responseCode = "404", description = "Outbound mapping disabled"),
            @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    @PreAuthorize(ADMIN_CREATE_ROLES)
    @DeleteMapping(value = "/client", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> clearAllClientRelations() {
        String tenant = getTenant();
        validateOutboundMappingEnabled(tenant);

        try {
            int mappingCount = configurationRegistry.getAllClientRelations(tenant).size();
            configurationRegistry.clearCacheDeviceToClient(tenant);

            log.info("{} - Successfully cleared {} client relations", tenant, mappingCount);

            Map<String, Object> response = Map.of(
                    "removedMappings", mappingCount);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("{} - Error clearing all client relations: {}", tenant, e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getLocalizedMessage());
        }
    }

    private String getTenant() {
        return contextService.getContext().getTenant();
    }

    private void validateOutboundMappingEnabled(String tenant) {
        ServiceConfiguration config = serviceConfigurationService.getServiceConfiguration(tenant);
        if (!config.getOutboundMappingEnabled()) {
            throw new OutboundMappingDisabledException(OUTBOUND_MAPPING_DISABLED_MESSAGE);
        }
    }

}