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
import dynamic.mapper.model.NotificationSubscriptionRequest;
import dynamic.mapper.model.NotificationSubscriptionResponse;
import dynamic.mapper.model.Device;
import dynamic.mapper.exception.OutboundMappingDisabledException;
import dynamic.mapper.exception.DeviceNotFoundException;
import dynamic.mapper.service.NotificationSubscriptionService;
import dynamic.mapper.service.ServiceConfigurationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import dynamic.mapper.core.C8YAgent;
import dynamic.mapper.core.ConfigurationRegistry;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.util.CollectionUtils;

import jakarta.validation.Valid;

import java.util.List;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@Slf4j
@RequestMapping("/subscription")
@RestController
@RequiredArgsConstructor
@Tag(name = "Device Subscription Controller", description = "API for managing Cumulocity IoT notification subscriptions for outbound mappings")
public class NotificationSubscriptionController {

    private final C8YAgent c8yAgent;
    private final ContextService<UserCredentials> contextService;
    private final ConfigurationRegistry configurationRegistry;
    private final ServiceConfigurationService serviceConfigurationService;
    private final NotificationSubscriptionService subscriptionService;

    private static final String OUTBOUND_MAPPING_DISABLED_MESSAGE = "Outbound mapping is disabled!";
    private static final String ADMIN_CREATE_ROLES = "hasAnyRole('ROLE_DYNAMIC_MAPPER_ADMIN', 'ROLE_DYNAMIC_MAPPER_CREATE')";

    @Operation(summary = "Create device notification subscription")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Subscription created successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid request"),
            @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions"),
            @ApiResponse(responseCode = "404", description = "Outbound mapping is disabled"),
            @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    @PreAuthorize(ADMIN_CREATE_ROLES)
    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<NotificationSubscriptionResponse> createSubscription(
            @Valid @RequestBody NotificationSubscriptionRequest request) {

        String tenant = getTenant();
        validateOutboundMappingEnabled(tenant);
        validateDeviceListNotEmpty(request.getDevices());

        try {
            NotificationSubscriptionResponse response = subscriptionService.createDeviceSubscription(tenant, request);
            log.info("{} - Successfully created subscription for {} devices", tenant,
                    response.getDevices() != null ? response.getDevices().size() : 0);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("{} - Error creating subscription: {}", tenant, e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getLocalizedMessage());
        }
    }

    @Operation(summary = "Update device notification subscription")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Subscription updated successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid request"),
            @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions"),
            @ApiResponse(responseCode = "404", description = "Outbound mapping is disabled"),
            @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    @PreAuthorize(ADMIN_CREATE_ROLES)
    @PutMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<NotificationSubscriptionResponse> updateSubscription(
            @Valid @RequestBody NotificationSubscriptionRequest request) {

        String tenant = getTenant();
        validateOutboundMappingEnabled(tenant);

        try {
            NotificationSubscriptionResponse response = subscriptionService.updateDeviceSubscription(tenant, request);
            log.info("{} - Successfully updated subscription", tenant);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("{} - Error updating subscription: {}", tenant, e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getLocalizedMessage());
        }
    }

    @Operation(summary = "Get device notification subscriptions")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Subscriptions retrieved successfully"),
            @ApiResponse(responseCode = "404", description = "Outbound mapping is disabled"),
            @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<NotificationSubscriptionResponse> getSubscriptions() {
        String tenant = getTenant();
        validateOutboundMappingEnabled(tenant);

        try {
            NotificationSubscriptionResponse response = configurationRegistry.getNotificationSubscriber()
                    .getSubscriptionsDevices(tenant, null, null);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("{} - Error retrieving subscriptions: {}", tenant, e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getLocalizedMessage());
        }
    }

    @Operation(summary = "Delete device notification subscription")
    @PreAuthorize(ADMIN_CREATE_ROLES)
    @DeleteMapping(value = "/{deviceId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> deleteSubscription(@PathVariable String deviceId) {
        String tenant = getTenant();
        validateOutboundMappingEnabled(tenant);

        try {
            ManagedObjectRepresentation mor = c8yAgent.getManagedObjectForId(tenant, deviceId);
            if (mor == null) {
                throw new DeviceNotFoundException("Device with id " + deviceId + " not found");
            }

            configurationRegistry.getNotificationSubscriber().unsubscribeDeviceAndDisconnect(tenant, mor);
            log.info("{} - Successfully deleted subscription for device {}", tenant, deviceId);
            return ResponseEntity.ok().build();
        } catch (DeviceNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (Exception e) {
            log.error("{} - Error deleting subscription for device {}: {}", tenant, deviceId, e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getLocalizedMessage());
        }
    }

    @Operation(summary = "Update group notification subscription")
    @PreAuthorize(ADMIN_CREATE_ROLES)
    @PutMapping(value = "/group", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<NotificationSubscriptionResponse> updateGroupSubscription(
            @Valid @RequestBody NotificationSubscriptionRequest request) {

        String tenant = getTenant();
        validateOutboundMappingEnabled(tenant);

        try {
            NotificationSubscriptionResponse response = subscriptionService.updateGroupSubscription(tenant, request);
            log.info("{} - Successfully updated group subscription", tenant);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("{} - Error updating group subscription: {}", tenant, e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getLocalizedMessage());
        }
    }

    @Operation(summary = "Get group notification subscriptions")
    @GetMapping(value = "/group", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<NotificationSubscriptionResponse> getGroupSubscriptions() {
        String tenant = getTenant();
        validateOutboundMappingEnabled(tenant);

        try {
            NotificationSubscriptionResponse response = configurationRegistry.getNotificationSubscriber()
                    .getSubscriptionsByDeviceGroup(tenant);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("{} - Error retrieving group subscriptions: {}", tenant, e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getLocalizedMessage());
        }
    }

    @Operation(summary = "Delete device group notification subscription")
    @PreAuthorize(ADMIN_CREATE_ROLES)
    @DeleteMapping(value = "/group/{groupId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> deleteGroupSubscription(@PathVariable String groupId) {
        String tenant = getTenant();
        validateOutboundMappingEnabled(tenant);

        try {
            ManagedObjectRepresentation mor = c8yAgent.getManagedObjectForId(tenant, groupId);
            if (mor == null) {
                throw new DeviceNotFoundException("Device group with id " + groupId + " not found");
            }

            subscriptionService.deleteGroupSubscription(tenant, mor);
            log.info("{} - Successfully deleted group subscription for {}", tenant, groupId);
            return ResponseEntity.ok().build();
        } catch (DeviceNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (Exception e) {
            log.error("{} - Error deleting group subscription {}: {}", tenant, groupId, e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getLocalizedMessage());
        }
    }

    @Operation(summary = "Get device type notification subscriptions")
    @GetMapping(value = "/type", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<NotificationSubscriptionResponse> getTypeSubscriptions() {
        String tenant = getTenant();
        validateOutboundMappingEnabled(tenant);

        try {
            NotificationSubscriptionResponse response = configurationRegistry.getNotificationSubscriber()
                    .getSubscriptionsByDeviceType(tenant);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("{} - Error retrieving type subscriptions: {}", tenant, e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getLocalizedMessage());
        }
    }

    @Operation(summary = "Update device type notification subscription")
    @PreAuthorize(ADMIN_CREATE_ROLES)
    @PutMapping(value = "/type", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<NotificationSubscriptionResponse> updateTypeSubscription(
            @Valid @RequestBody NotificationSubscriptionRequest request) {

        String tenant = getTenant();
        validateOutboundMappingEnabled(tenant);

        try {
            NotificationSubscriptionResponse response = configurationRegistry
                    .getNotificationSubscriber().updateSubscriptionByType(request.getTypes());
            log.info("{} - Successfully updated type subscription", tenant);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("{} - Error updating type subscription: {}", tenant, e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getLocalizedMessage());
        }
    }

    private String getTenant() {
        return contextService.getContext().getTenant();
    }

    private void validateOutboundMappingEnabled(String tenant) {
        ServiceConfiguration config = serviceConfigurationService.getServiceConfiguration(tenant);
        if (!config.isOutboundMappingEnabled()) {
            throw new OutboundMappingDisabledException(OUTBOUND_MAPPING_DISABLED_MESSAGE);
        }
    }

    private void validateDeviceListNotEmpty(List<Device> devices) {
        if (CollectionUtils.isEmpty(devices)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Device list cannot be empty");
        }
    }
}