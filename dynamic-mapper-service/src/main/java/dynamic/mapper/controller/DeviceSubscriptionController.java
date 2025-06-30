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
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.configuration.ServiceConfigurationComponent;
import dynamic.mapper.model.C8YNotificationSubscription;
import dynamic.mapper.model.Device;
import lombok.extern.slf4j.Slf4j;
import dynamic.mapper.core.C8YAgent;
import dynamic.mapper.core.ConfigurationRegistry;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@Slf4j
@RequestMapping("/subscription")
@RestController
@Tag(name = "Device Subscription Controller", description = "API for managing Cumulocity IoT notification subscriptions for outbound mappings. Handles device subscriptions for real-time notifications when device data changes.")
public class DeviceSubscriptionController {

    @Autowired
    C8YAgent c8yAgent;

    @Autowired
    private ContextService<UserCredentials> contextService;

    @Autowired
    private ConfigurationRegistry configurationRegistry;

    @Autowired
    ServiceConfigurationComponent serviceConfigurationComponent;

    @Operation(
        summary = "Create device notification subscription",
        description = """
            Creates notification subscriptions for specified devices to enable outbound mapping functionality. 
            When devices are subscribed, the system will receive real-time notifications about changes to the devices
            and can trigger outbound mappings accordingly.
            
            **Prerequisites:**
            - Outbound mapping must be enabled in service configuration
            - User must have CREATE or ADMIN role
            
            **Behavior:**
            - Automatically discovers and includes all child devices
            - Creates subscriptions for all specified API types
            - Returns the subscription with all included devices
            
            **Security:** Requires ROLE_DYNAMIC_MAPPER_ADMIN or ROLE_DYNAMIC_MAPPER_CREATE role.
            """,
        requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "Subscription configuration with devices and API types",
            required = true,
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = C8YNotificationSubscription.class),
                examples = @ExampleObject(
                    name = "Device Subscription",
                    description = "Subscribe to measurements for specific devices",
                    value = """
                    {
                      "api": "MEASUREMENT",
                      "subscriptionName": "temperature-sensors",
                      "devices": [
                        {
                          "id": "12345",
                          "name": "Temperature Sensor 01"
                        },
                        {
                          "id": "12346",
                          "name": "Temperature Sensor 02"
                        }
                      ]
                    }
                    """
                )
            )
        )
    )
    @ApiResponses(value = {
        @ApiResponse(
            responseCode = "200",
            description = "Subscription created successfully",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = C8YNotificationSubscription.class)
            )
        ),
        @ApiResponse(
            responseCode = "403",
            description = "Forbidden - insufficient permissions",
            content = @Content
        ),
        @ApiResponse(
            responseCode = "404",
            description = "Outbound mapping is disabled",
            content = @Content(
                mediaType = "application/json"
            )
        ),
        @ApiResponse(responseCode = "500", description = "Internal server error", content = @Content)
    })
    @PreAuthorize("hasAnyRole('ROLE_DYNAMIC_MAPPER_ADMIN', 'ROLE_DYNAMIC_MAPPER_CREATE')")
    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<C8YNotificationSubscription> subscriptionCreate(
            @Valid @RequestBody C8YNotificationSubscription subscription) {
        String tenant = contextService.getContext().getTenant();
        ServiceConfiguration serviceConfiguration = serviceConfigurationComponent.getServiceConfiguration(tenant);
        if (!serviceConfiguration.isOutboundMappingEnabled())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Output Mapping is disabled!");
        try {
            List<Device> allChildDevices = null;
            for (Device managedObject : subscription.getDevices()) {
                log.info("{} - Find all related Devices of Managed Object {}", managedObject.getId());
                ManagedObjectRepresentation mor = c8yAgent
                        .getManagedObjectForId(contextService.getContext().getTenant(), managedObject.getId());
                if (mor != null) {
                    allChildDevices = configurationRegistry.getNotificationSubscriber().findAllRelatedDevicesByMO(mor,
                            allChildDevices, false);
                } else {
                    log.warn("{} - Could not subscribe device with id {}. Device does not exists!", tenant,
                            managedObject.getId());
                }
            }
            if (allChildDevices != null) {
                for (Device device : allChildDevices) {
                    ManagedObjectRepresentation childDeviceMor = c8yAgent
                            .getManagedObjectForId(contextService.getContext().getTenant(), device.getId());
                    // Creates subscription for each connector
                    configurationRegistry.getNotificationSubscriber().subscribeDeviceAndConnect(childDeviceMor,
                            subscription.getApi());
                }
            }
            subscription.setDevices(allChildDevices);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getLocalizedMessage());
        }
        return ResponseEntity.ok(subscription);
    }

    @Operation(
        summary = "Update device notification subscription",
        description = """
            Updates an existing notification subscription by comparing the new device list with the current subscriptions.
            
            **Update Logic:**
            1. Devices in new list but not in current subscriptions → Subscribe
            2. Devices in current subscriptions but not in new list → Unsubscribe  
            3. Devices in both lists → No change
            
            **Prerequisites:**
            - Outbound mapping must be enabled in service configuration
            
            **Security:** Requires ROLE_DYNAMIC_MAPPER_ADMIN or ROLE_DYNAMIC_MAPPER_CREATE role.
            """,
        requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "Updated subscription configuration",
            required = true,
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = C8YNotificationSubscription.class),
                examples = @ExampleObject(
                    name = "Updated Subscription",
                    description = "Modified device list for subscription",
                    value = """
                    {
                      "api": "MEASUREMENT",
                      "subscriptionName": "temperature-sensors",
                      "devices": [
                        {
                          "id": "12345",
                          "name": "Temperature Sensor 01"
                        },
                        {
                          "id": "12347",
                          "name": "Temperature Sensor 03"
                        }
                      ]
                    }
                    """
                )
            )
        )
    )
    @ApiResponses(value = {
        @ApiResponse(
            responseCode = "200",
            description = "Subscription updated successfully",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = C8YNotificationSubscription.class)
            )
        ),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content),
        @ApiResponse(responseCode = "404", description = "Outbound mapping is disabled", content = @Content),
        @ApiResponse(responseCode = "500", description = "Internal server error", content = @Content)
    })
    @PreAuthorize("hasAnyRole('ROLE_DYNAMIC_MAPPER_ADMIN', 'ROLE_DYNAMIC_MAPPER_CREATE')")
    @PutMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<C8YNotificationSubscription> subscriptionUpdate(
            @Valid @RequestBody C8YNotificationSubscription subscription) {
        String tenant = contextService.getContext().getTenant();
        ServiceConfiguration serviceConfiguration = serviceConfigurationComponent.getServiceConfiguration(tenant);
        if (!serviceConfiguration.isOutboundMappingEnabled())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Output Mapping is disabled!");
        try {
            C8YNotificationSubscription c8ySubscription = configurationRegistry.getNotificationSubscriber()
                    .getDeviceSubscriptions(tenant, null, null);
            
            List<Device> toBeRemovedSub = new ArrayList<>();
            List<Device> toBeCreatedSub = new ArrayList<>();

            c8ySubscription.getDevices().forEach(device -> toBeRemovedSub.add(device));
            subscription.getDevices().forEach(device -> toBeCreatedSub.add(device));

            subscription.getDevices().forEach(device -> toBeRemovedSub.removeIf(x -> x.getId().equals(device.getId())));
            c8ySubscription.getDevices()
                    .forEach(entity -> toBeCreatedSub.removeIf(x -> x.getId().equals(entity.getId())));
            
            List<Device> allChildDevices = null;
            for (Device device : toBeCreatedSub) {
                ManagedObjectRepresentation mor = c8yAgent.getManagedObjectForId(tenant, device.getId());
                if (mor != null) {
                    try {
                        allChildDevices = configurationRegistry.getNotificationSubscriber()
                                .findAllRelatedDevicesByMO(mor, allChildDevices, false);
                    } catch (Exception e) {
                        log.error("{} - Error creating subscriptions: ", tenant, e);
                        throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getLocalizedMessage());
                    }
                } else {
                    log.warn("{} - Could not subscribe device with id {}. Device does not exists!", tenant,
                            device.getId());
                }
            }
            
            if (allChildDevices != null) {
                for (Device childDevice : allChildDevices) {
                    ManagedObjectRepresentation childDeviceMor = c8yAgent.getManagedObjectForId(tenant,
                            childDevice.getId());
                    configurationRegistry.getNotificationSubscriber().subscribeDeviceAndConnect(childDeviceMor,
                            subscription.getApi());
                }
                subscription.setDevices(allChildDevices);
            }

            for (Device device : toBeRemovedSub) {
                ManagedObjectRepresentation mor = c8yAgent.getManagedObjectForId(tenant, device.getId());
                if (mor != null) {
                    try {
                        configurationRegistry.getNotificationSubscriber().unsubscribeDeviceAndDisconnect(mor);
                    } catch (Exception e) {
                        log.error("{} - Error removing subscriptions: ", tenant, e);
                        throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getLocalizedMessage());
                    }
                } else {
                    log.warn("{} - Could not subscribe device with id {}. Device does not exists!", tenant,
                            device.getId());
                }
            }

        } catch (Exception e) {
            log.error("{} - Error updating subscriptions: ", tenant, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getLocalizedMessage());
        }
        return ResponseEntity.ok(subscription);
    }

    @Operation(
        summary = "Get device notification subscriptions",
        description = """
        Retrieves current notification subscriptions, optionally filtered by device ID or subscription name. Shows which devices are currently subscribed for outbound notifications.
        """,
        parameters = {
            @Parameter(
                name = "deviceId",
                description = "Filter subscriptions by specific device ID",
                required = false,
                example = "12345",
                schema = @Schema(type = "string")
            ),
            @Parameter(
                name = "subscriptionName",
                description = "Filter subscriptions by subscription name",
                required = false,
                example = "temperature-sensors",
                schema = @Schema(type = "string")
            )
        }
    )
    @ApiResponses(value = {
        @ApiResponse(
            responseCode = "200",
            description = "Subscriptions retrieved successfully",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = C8YNotificationSubscription.class)
            )
        ),
        @ApiResponse(responseCode = "404", description = "Outbound mapping is disabled", content = @Content),
        @ApiResponse(responseCode = "500", description = "Internal server error", content = @Content)
    })
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<C8YNotificationSubscription> subscriptionsGet(
            @RequestParam(required = false) String deviceId,
            @RequestParam(required = false) String subscriptionName) {
        String tenant = contextService.getContext().getTenant();
        ServiceConfiguration serviceConfiguration = serviceConfigurationComponent.getServiceConfiguration(tenant);
        if (!serviceConfiguration.isOutboundMappingEnabled())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Output Mapping is disabled!");
        try {
            C8YNotificationSubscription c8YAPISubscription = configurationRegistry.getNotificationSubscriber()
                    .getDeviceSubscriptions(contextService.getContext().getTenant(), deviceId, subscriptionName);
            return ResponseEntity.ok(c8YAPISubscription);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getLocalizedMessage());
        }
    }

    @Operation(
        summary = "Delete device notification subscription",
        description = """
        Removes notification subscription for a specific device. The device will no longer trigger outbound mappings when its data changes.
        
        **Security:** Requires ROLE_DYNAMIC_MAPPER_ADMIN or ROLE_DYNAMIC_MAPPER_CREATE role.
        """,
        parameters = {
            @Parameter(
                name = "deviceId",
                description = "ID of the device to unsubscribe",
                required = true,
                example = "12345",
                schema = @Schema(type = "string")
            )
        }
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Subscription deleted successfully", content = @Content),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content),
        @ApiResponse(
            responseCode = "404", 
            description = "Device not found or outbound mapping is disabled",
            content = @Content(
                mediaType = "application/json"
            )
        ),
        @ApiResponse(responseCode = "500", description = "Internal server error", content = @Content)
    })
    @PreAuthorize("hasAnyRole('ROLE_DYNAMIC_MAPPER_ADMIN', 'ROLE_DYNAMIC_MAPPER_CREATE')")
    @DeleteMapping(value = "/{deviceId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> subscriptionDelete(@PathVariable String deviceId) {
        String tenant = contextService.getContext().getTenant();
        ServiceConfiguration serviceConfiguration = serviceConfigurationComponent.getServiceConfiguration(tenant);
        if (!serviceConfiguration.isOutboundMappingEnabled())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Output Mapping is disabled!");
        try {
            ManagedObjectRepresentation mor = c8yAgent.getManagedObjectForId(contextService.getContext().getTenant(),
                    deviceId);
            if (mor != null) {
                configurationRegistry.getNotificationSubscriber().unsubscribeDeviceAndDisconnect(mor);
            } else {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Could not delete subscription for device with id " + deviceId + ". Device not found");
            }
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getLocalizedMessage());
        }
        return ResponseEntity.ok().build();
    }
}