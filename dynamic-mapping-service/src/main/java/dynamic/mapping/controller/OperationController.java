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

package dynamic.mapping.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.validation.Valid;
import dynamic.mapping.configuration.ConnectorConfiguration;
import dynamic.mapping.configuration.ConnectorConfigurationComponent;
import dynamic.mapping.configuration.ConnectorId;
import dynamic.mapping.configuration.ServiceConfiguration;
import dynamic.mapping.configuration.ServiceConfigurationComponent;
import dynamic.mapping.connector.core.client.ConnectorException;
import dynamic.mapping.connector.core.client.AConnectorClient;
import dynamic.mapping.connector.core.registry.ConnectorRegistry;
import dynamic.mapping.connector.core.registry.ConnectorRegistryException;
import dynamic.mapping.core.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.cumulocity.microservice.context.ContextService;
import com.cumulocity.microservice.context.credentials.UserCredentials;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import dynamic.mapping.model.Direction;
import dynamic.mapping.model.SnoopStatus;
import dynamic.mapping.model.Mapping;

@Slf4j
@RequestMapping("/operation")
@RestController
@Tag(name = "Operation Controller", description = "API for executing various administrative and operational tasks on the dynamic mapping service")
public class OperationController {

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

    private ObjectMapper objectMapper;

    @Autowired
    public void setObjectMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @io.swagger.v3.oas.annotations.Operation(
        summary = "Execute a service operation", 
        description = "Executes various administrative and operational tasks such as reloading mappings, connecting/disconnecting connectors, managing caches, and other maintenance operations. Different operations require different permission levels.",
        requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "Service operation to execute with parameters",
            required = true,
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ServiceOperation.class),
                examples = {
                    @ExampleObject(
                        name = "Reload Mappings",
                        description = "Reload all mappings for the current tenant",
                        value = """
                        {
                          "operation": "RELOAD_MAPPINGS",
                          "parameter": {}
                        }
                        """
                    ),
                    @ExampleObject(
                        name = "Connect Connector",
                        description = "Connect a specific connector",
                        value = """
                        {
                          "operation": "CONNECT",
                          "parameter": {
                            "connectorIdentifier": "jrr12x"
                          }
                        }
                        """
                    ),
                    @ExampleObject(
                        name = "Activate Mapping",
                        description = "Activate or deactivate a mapping",
                        value = """
                        {
                          "operation": "ACTIVATE_MAPPING",
                          "parameter": {
                            "id": "34573838974",
                            "active": "true"
                          }
                        }
                        """
                    ),
                    @ExampleObject(
                        name = "Clear Cache",
                        description = "Clear a specific cache",
                        value = """
                        {
                          "operation": "CLEAR_CACHE",
                          "parameter": {
                            "cacheId": "INBOUND_ID_CACHE"
                          }
                        }
                        """
                    )
                }
            )
        )
    )
    @ApiResponses(value = {
        @ApiResponse(
            responseCode = "201", 
            description = "Operation executed successfully", 
            content = @Content(mediaType = "application/json")
        ),
        @ApiResponse(
            responseCode = "400", 
            description = "Bad request - invalid operation parameters or failed connector operations", 
            content = @Content(mediaType = "application/json", schema = @Schema(type = "object", description = "Map of failed connectors with their identifiers and names"))
        ),
        @ApiResponse(
            responseCode = "403", 
            description = "Forbidden - insufficient permissions for the requested operation", 
            content = @Content
        ),
        @ApiResponse(
            responseCode = "500", 
            description = "Internal server error", 
            content = @Content
        )
    })
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> runOperation(
            @Parameter(description = "Service operation to execute", required = true)
            @Valid @RequestBody ServiceOperation operation) {
        String tenant = contextService.getContext().getTenant();
        log.info("{} - Post operation: {}", tenant, operation);

        try {
            Operation operationType = operation.getOperation();
            Map<String, String> parameters = operation.getParameter();

            switch (operationType) {
                case RELOAD_MAPPINGS:
                    if (!Utils.userHasMappingCreateRole()) {
                        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                                "User does not have permission to reload mappings");
                    }
                    return handleReloadMappings(tenant);
                case CONNECT:
                    if (!Utils.userHasMappingAdminRole()) {
                        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                                "User does not have permission to connect to connector");
                    }
                    return handleConnect(tenant, parameters);
                case DISCONNECT:
                    if (!Utils.userHasMappingAdminRole()) {
                        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                                "User does not have permission to disconnect from connector");
                    }
                    return handleDisconnect(tenant, parameters);
                case REFRESH_STATUS_MAPPING:
                    if (!Utils.userHasMappingCreateRole()) {
                        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                                "User does not have permission to refresh status mappings");
                    }
                    return handleRefreshStatusMapping(tenant);
                case RESET_STATUS_MAPPING:
                    if (!Utils.userHasMappingAdminRole()) {
                        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                                "User does not have permission to reset status mapping");
                    }
                    return handleResetStatusMapping(tenant);
                case RESET_DEPLOYMENT_MAP:
                    if (!Utils.userHasMappingAdminRole()) {
                        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                                "User does not have permission to reset deployment map");
                    }
                    return handleResetDeploymentMap(tenant);
                case RELOAD_EXTENSIONS:
                    if (!Utils.userHasMappingAdminRole()) {
                        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                                "User does not have permission to reload extensions");
                    }
                    return handleReloadExtensions(tenant);
                case ACTIVATE_MAPPING:
                    if (!Utils.userHasMappingCreateRole()) {
                        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                                "User does not have permission to activate mappings");
                    }
                    return handleActivateMapping(tenant, parameters);
                case APPLY_MAPPING_FILTER:
                    if (!Utils.userHasMappingCreateRole()) {
                        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                                "User does not have permission to apply mapping filter");
                    }
                    return handleApplyMappingFilter(tenant, parameters);
                case DEBUG_MAPPING:
                    if (!Utils.userHasMappingCreateRole()) {
                        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                                "User does not have permission to debug mappings");
                    }
                    return handleDebugMapping(tenant, parameters);
                case SNOOP_MAPPING:
                    if (!Utils.userHasMappingCreateRole()) {
                        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                                "User does not have permission to snoop mappings");
                    }
                    return handleSnoopMapping(tenant, parameters);
                case SNOOP_RESET:
                    if (!Utils.userHasMappingCreateRole()) {
                        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                                "User does not have permission to reset snoop");
                    }
                    return handleSnoopReset(tenant, parameters);
                case REFRESH_NOTIFICATIONS_SUBSCRIPTIONS:
                    if (!Utils.userHasMappingAdminRole()) {
                        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                                "User does not have permission to refresh notifications subscriptions");
                    }
                    return handleRefreshNotifications(tenant);
                case CLEAR_CACHE:
                    if (!Utils.userHasMappingAdminRole()) {
                        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                                "User does not have permission to clear cache");
                    }
                    return handleClearCache(tenant, parameters);
                case COPY_SNOOPED_SOURCE_TEMPLATE:
                    if (!Utils.userHasMappingCreateRole()) {
                        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                                "User does not have permission to copy snooped source template");
                    }
                    return handleCopySnoopedSourceTemplate(tenant, parameters);
                case ADD_SAMPLE_MAPPINGS:
                    if (!Utils.userHasMappingCreateRole()) {
                        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                                "User does not have permission to add sample mappings");
                    }
                    return handleAddSampleMappings(tenant, parameters);
                case INIT_CODE_TEMPLATES:
                    if (!Utils.userHasMappingAdminRole()) {
                        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                                "User does not have permission to initialize code templates");
                    }
                    return handleInitCodeTemplates(tenant, parameters);
                default:
                    throw new IllegalArgumentException("Unknown operation: " + operationType);
            }
        } catch (Exception ex) {
            log.error("{} - Error running operation: {}", tenant, ex.getMessage(), ex);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage());
        }
    }

    private ResponseEntity<?> handleAddSampleMappings(String tenant, Map<String, String> parameters)
            throws Exception {
        Direction direction = Direction.valueOf(parameters.get("direction"));
        if (direction.equals(Direction.INBOUND)) {
            String samples = serviceConfigurationComponent.getSampleMappingsInbound_01();
            List<Mapping> mappings = objectMapper.readValue(samples, new TypeReference<List<Mapping>>() {
            });
            List<Mapping> existingMappings = mappingComponent.getMappings(tenant, Direction.INBOUND);
            mappings.forEach(mapping -> {
                AtomicBoolean alreadyExits = new AtomicBoolean(false);
                existingMappings.forEach(existingMapping -> {
                    if (existingMapping.getIdentifier().equals(mapping.getIdentifier()))
                        alreadyExits.set(true);
                });
                if (!alreadyExits.get()) {
                    mapping.setActive(false);
                    mappingComponent.createMapping(tenant, mapping);
                }
            });
        } else {
            List<Mapping> existingMappings = mappingComponent.getMappings(tenant, Direction.OUTBOUND);
            String samples = serviceConfigurationComponent.getSampleMappingsOutbound_01();
            List<Mapping> mappings = objectMapper.readValue(samples, new TypeReference<List<Mapping>>() {
            });
            mappings.forEach(mapping -> {
                AtomicBoolean alreadyExits = new AtomicBoolean(false);
                existingMappings.forEach(existingMapping -> {
                    if (existingMapping.getIdentifier().equals(mapping.getIdentifier()))
                        alreadyExits.set(true);
                });
                if (!alreadyExits.get()) {
                    mapping.setActive(false);
                    mappingComponent.createMapping(tenant, mapping);
                }
            });
        }
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    private ResponseEntity<?> handleInitCodeTemplates(String tenant, Map<String, String> parameters) throws Exception {
        ServiceConfiguration serviceConfiguration = serviceConfigurationComponent.getServiceConfiguration(tenant);
        log.debug("{} - Init system code template", tenant);

        // Initialize code templates from properties if not already set
        serviceConfigurationComponent.initCodeTemplates(serviceConfiguration, true);

        try {
            serviceConfigurationComponent.saveServiceConfiguration(tenant, serviceConfiguration);
            configurationRegistry.addServiceConfiguration(tenant, serviceConfiguration);
        } catch (JsonProcessingException ex) {
            log.error("{} - Error saving service configuration with code templates: {}", tenant, ex);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getLocalizedMessage());
        }

        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    private ResponseEntity<?> handleCopySnoopedSourceTemplate(String tenant, Map<String, String> parameters)
            throws Exception {
        String id = parameters.get("id");
        Integer index = Integer.parseInt(parameters.get("index"));
        mappingComponent.updateSourceTemplate(tenant, id, index);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    private ResponseEntity<?> handleReloadMappings(String tenant) throws ConnectorRegistryException {
        List<Mapping> updatedMappingsInbound = mappingComponent.rebuildMappingInboundCache(tenant,
                ConnectorId.INTERNAL);
        List<Mapping> updatedMappingsOutbound = mappingComponent.rebuildMappingOutboundCache(tenant,
                ConnectorId.INTERNAL);

        Map<String, AConnectorClient> connectorMap = connectorRegistry.getClientsForTenant(tenant);
        connectorMap.values().forEach(client -> {
            // we always start with a cleanSession in case we reload the mappings
            client.initializeSubscriptionsInbound(updatedMappingsInbound, false, true);
            updatedMappingsOutbound.forEach(mapping -> client.updateSubscriptionForOutbound(mapping, false, false));
        });

        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    private ResponseEntity<?> handleResetDeploymentMap(String tenant) throws Exception {
        mappingComponent.initializeDeploymentMap(tenant, true);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    private ResponseEntity<?> handleSnoopReset(String tenant, Map<String, String> parameters) throws Exception {
        String id = parameters.get("id");
        mappingComponent.resetSnoop(tenant, id);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    private ResponseEntity<?> handleActivateMapping(String tenant, Map<String, String> parameters) throws Exception {
        String id = parameters.get("id");
        Boolean activation = Boolean.parseBoolean(parameters.get("active"));
        Mapping updatedMapping = mappingComponent.setActivationMapping(tenant, id, activation);
        Map<String, AConnectorClient> connectorMap = connectorRegistry
                .getClientsForTenant(tenant);
        // subscribe/unsubscribe respective mappingTopic of mapping only for
        // outbound mapping
        Map<String, String> failed = new HashMap<>();
        for (AConnectorClient client : connectorMap.values()) {
            if (updatedMapping.direction == Direction.INBOUND) {
                if (!client.updateSubscriptionForInbound(updatedMapping, false, true)) {
                    ConnectorConfiguration conf = client.getConnectorConfiguration();
                    failed.put(conf.getIdentifier(), conf.getName());
                }
                ;
            } else {
                client.updateSubscriptionForOutbound(updatedMapping, false, true);
            }
        }

        if (failed.size() > 0) {
            return new ResponseEntity<Map<String, String>>(failed, HttpStatus.BAD_REQUEST);
        }
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    private ResponseEntity<?> handleApplyMappingFilter(String tenant, Map<String, String> parameters) throws Exception {
        String id = parameters.get("id");
        String filterMapping = parameters.get("filterMapping");
        mappingComponent.setFilterMapping(tenant, id, filterMapping);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    private ResponseEntity<?> handleDebugMapping(String tenant, Map<String, String> parameters) throws Exception {
        String id = parameters.get("id");
        Boolean debugBoolean = Boolean.parseBoolean(parameters.get("debug"));
        mappingComponent.setDebugMapping(tenant, id, debugBoolean);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    private ResponseEntity<?> handleRefreshStatusMapping(String tenant) throws Exception {
        mappingComponent.sendMappingStatus(tenant);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    private ResponseEntity<?> handleResetStatusMapping(String tenant) throws Exception {
        mappingComponent.initializeMappingStatus(tenant, true);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    private ResponseEntity<?> handleReloadExtensions(String tenant) throws Exception {
        configurationRegistry.getC8yAgent().reloadExtensions(tenant);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    private ResponseEntity<?> handleRefreshNotifications(String tenant) throws Exception {
        configurationRegistry.getNotificationSubscriber().notificationSubscriberReconnect(tenant);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    private ResponseEntity<?> handleSnoopMapping(String tenant, Map<String, String> parameters) throws Exception {
        String id = parameters.get("id");
        SnoopStatus newSnoop = SnoopStatus.valueOf(parameters.get("snoopStatus"));
        mappingComponent.setSnoopStatusMapping(tenant, id, newSnoop);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    private ResponseEntity<?> handleConnect(String tenant, Map<String, String> parameters)
            throws JsonProcessingException, ConnectorRegistryException, ConnectorException {
        String connectorIdentifier = parameters.get("connectorIdentifier");
        ConnectorConfiguration configuration = connectorConfigurationComponent
                .getConnectorConfiguration(connectorIdentifier, tenant);

        configuration.setEnabled(true);
        connectorConfigurationComponent.saveConnectorConfiguration(configuration);

        ServiceConfiguration serviceConfiguration = serviceConfigurationComponent
                .getServiceConfiguration(tenant);

        Future<?> connectTask = bootstrapService.initializeConnectorByConfiguration(configuration, serviceConfiguration,
                tenant);
        AConnectorClient client = connectorRegistry.getClientForTenant(tenant,
                connectorIdentifier);
        // Wait until client is connected before subscribing - otherwise "old"
        // notification messages will be ignored
        if (client.supportedDirections().contains(Direction.OUTBOUND)) {
            try {
                connectTask.get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.error("{} - Error waiting for client to connect: {}", tenant, e.getMessage());
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
            }
            configurationRegistry.getNotificationSubscriber().notificationSubscriberReconnect(tenant);
        }

        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    private ResponseEntity<?> handleDisconnect(String tenant, Map<String, String> parameters)
            throws JsonProcessingException, ConnectorRegistryException {
        String connectorIdentifier = parameters.get("connectorIdentifier");
        ConnectorConfiguration configuration = connectorConfigurationComponent
                .getConnectorConfiguration(connectorIdentifier, tenant);
        configuration.setEnabled(false);
        connectorConfigurationComponent.saveConnectorConfiguration(configuration);

        AConnectorClient client = connectorRegistry.getClientForTenant(tenant,
                connectorIdentifier);
        // client.submitDisconnect();
        bootstrapService.disableConnector(tenant, client.getConnectorIdentifier());
        // We might need to Reconnect other Notification Clients for other connectors
        configurationRegistry.getNotificationSubscriber().notificationSubscriberReconnect(tenant);

        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    // Add other private handler methods for each operation type...

    private ResponseEntity<?> handleClearCache(String tenant, Map<String, String> parameters) {
        String cacheId = parameters.get("cacheId");
        if ("INBOUND_ID_CACHE".equals(cacheId)) {
            Integer cacheSize = serviceConfigurationComponent
                    .getServiceConfiguration(tenant).inboundExternalIdCacheSize;
            configurationRegistry.getC8yAgent().clearInboundExternalIdCache(tenant, false, cacheSize);
            log.info("{} - Cache cleared: {}", tenant, cacheId);
            return ResponseEntity.status(HttpStatus.CREATED).build();
        } else if ("INVENTORY_CACHE".equals(cacheId)) {
            Integer cacheSize = serviceConfigurationComponent
                    .getServiceConfiguration(tenant).inventoryCacheSize;
            configurationRegistry.getC8yAgent().clearInventoryCache(tenant, false, cacheSize);
            log.info("{} - Cache cleared: {}", tenant, cacheId);
            return ResponseEntity.status(HttpStatus.CREATED).build();
        }

        String errorMsg = String.format("Tenant %s - Unknown cache: %s", tenant, cacheId);
        log.error(errorMsg);
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, errorMsg);
    }

}