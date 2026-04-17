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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.validation.Valid;
import dynamic.mapper.configuration.ConnectorConfiguration;
import dynamic.mapper.configuration.ConnectorId;
import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.connector.core.client.ConnectorException;
import dynamic.mapper.connector.core.client.AConnectorClient;
import dynamic.mapper.connector.core.registry.ConnectorRegistry;
import dynamic.mapper.connector.core.registry.ConnectorRegistryException;
import dynamic.mapper.core.BootstrapService;
import dynamic.mapper.core.C8YAgent;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.core.ExtensionManager;
import dynamic.mapper.core.facade.IdentityFacade;
import dynamic.mapper.core.facade.InventoryFacade;

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

import lombok.extern.slf4j.Slf4j;
import dynamic.mapper.model.Direction;
import dynamic.mapper.model.LoggingEventType;
import dynamic.mapper.model.SnoopStatus;
import org.joda.time.DateTime;
import dynamic.mapper.service.ConnectorConfigurationService;
import dynamic.mapper.service.MappingService;
import dynamic.mapper.service.ServiceConfigurationService;
import dynamic.mapper.service.deployment.DeploymentMapService;
import dynamic.mapper.service.status.MappingStatusService;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.Operation;
import dynamic.mapper.model.ServiceOperation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@Slf4j
@RequestMapping("/operation")
@RestController
@Tag(name = "Operation Controller", description = "API for executing various administrative and operational tasks on the dynamic mapper service")
public class OperationController {

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

    @Autowired
    private ConfigurationRegistry configurationRegistry;

    @Value("${APP.externalExtensionsEnabled}")
    private Boolean externalExtensionsEnabled;

    @Autowired
    private DeploymentMapService deploymentMapService;

    @Autowired
    private MappingStatusService mappingStatusService;

    @Autowired
    private IdentityFacade identityFacade;

    @Autowired
    private InventoryFacade inventoryFacade;

    @Autowired
    private dynamic.mapper.service.cache.FlowStateStore flowStateStore;

    @Autowired
    private ExtensionManager extensionManager;

    private ObjectMapper objectMapper;

    @Autowired
    public void setObjectMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "Execute a service operation", description = """
            Executes various administrative and operational tasks such as reloading mappings, connecting/disconnecting connectors, managing caches, and other maintenance operations. Different operations require different permission levels.

            **Please note:** Each operation may have specific requirements and permissions. Ensure that the user has the necessary roles to perform the requested operation.
            `ROLE_DYNAMIC_MAPPER_CREATE` Operations:
            - `RELOAD_MAPPINGS`: Reloads all mappings for the current tenant.
            - `ACTIVATE_MAPPING`: Activates or deactivates a mapping.
            - `APPLY_MAPPING_FILTER`: Applies a filter to a mapping.
            - `UPDATE_CODE`: UPdate code for Smart Function or Substitution as Code.
            - `DEBUG_MAPPING`: Enables or disables debug mode for a mapping.
            - `SNOOP_MAPPING`: Enables or disables snooping for a mapping.
            - `SNOOP_RESET`: Resets snooping for a mapping.
            - `REFRESH_STATUS_MAPPING`: Refreshes the status of all mappings.
            - `ADD_SAMPLE_MAPPINGS`: Adds sample mappings for inbound or outbound direction.
            - `COPY_SNOOPED_SOURCE_TEMPLATE`: Copies the source template from a snooped mapping.


            `ROLE_DYNAMIC_MAPPER_ADMIN` Operations:
            - `CONNECT`: Connects a specific connector.
            - `DISCONNECT`: Disconnects a specific connector.
            - `RESET_STATISTICS_MAPPING`: Resets statistics for all mappings.
            - `RESET_DEPLOYMENT_MAP`: Resets the deployment map for the current tenant.
            - `RELOAD_EXTENSIONS`: Reloads all extensions for the current tenant.
            - `REFRESH_NOTIFICATIONS_SUBSCRIPTIONS`: Refreshes notification subscriptions for the current tenant.
            - `CLEAR_CACHE`: Clears a specific cache (e.g., inbound ID cache, inventory cache).
            - `INIT_CODE_TEMPLATES`: Initializes code templates for the current tenant.

            """, requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "Service operation to execute with parameters", required = true, content = @Content(mediaType = "application/json", schema = @Schema(implementation = ServiceOperation.class), examples = {
            @ExampleObject(name = "Reload Mappings", description = "Reload all mappings for the current tenant", value = """
                    {
                      "operation": "RELOAD_MAPPINGS",
                      "parameter": {}
                    }
                    """),
            @ExampleObject(name = "Connect Connector", description = "Connect a specific connector", value = """
                    {
                      "operation": "CONNECT",
                      "parameter": {
                        "connectorIdentifier": "jrr12x"
                      }
                    }
                    """),
            @ExampleObject(name = "Activate Mapping", description = "Activate or deactivate a mapping", value = """
                    {
                      "operation": "ACTIVATE_MAPPING",
                      "parameter": {
                        "id": "34573838974",
                        "active": "true"
                      }
                    }
                    """),
            @ExampleObject(name = "Clear Cache", description = "Clear a specific cache", value = """
                    {
                      "operation": "CLEAR_CACHE",
                      "parameter": {
                        "cacheId": "INBOUND_ID_CACHE"
                      }
                    }
                    """)
    })))
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Operation executed successfully", content = @Content(mediaType = "application/json")),
            @ApiResponse(responseCode = "400", description = "Bad request - invalid operation parameters or failed connector operations", content = @Content(mediaType = "application/json", schema = @Schema(type = "object", description = "Map of failed connectors with their identifiers and names"))),
            @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions for the requested operation", content = @Content),
            @ApiResponse(responseCode = "500", description = "Internal server error", content = @Content)
    })
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> runOperation(
            @Parameter(description = "Service operation to execute", required = true) @Valid @RequestBody ServiceOperation operation) {
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
                case RESET_STATISTICS_MAPPING:
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

                case UPDATE_CODE:
                    if (!Utils.userHasMappingCreateRole()) {
                        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                                "User does not have permission to change transformation code");
                    }
                    return handleApplyUpdateCode(tenant, parameters);
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
                case CLEAR_CACHE_DEVICE_TO_CLIENT:
                    if (!Utils.userHasMappingAdminRole()) {
                        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                                "User does not have permission to clear device-to-client cache");
                    }
                    return handleClearCacheDeviceToClient(tenant, parameters);
                default:
                    throw new IllegalArgumentException("Unknown operation: " + operationType);
            }
        } catch (ResponseStatusException ex) {
            // Re-throw permission errors (403) and other explicit status responses as-is;
            // the outer catch must not swallow them into 500.
            throw ex;
        } catch (IllegalArgumentException ex) {
            // Covers NumberFormatException (bad int param), enum valueOf with unknown value, etc.
            log.warn("{} - Bad operation parameter: {}", tenant, ex.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (Exception ex) {
            log.error("{} - Error running operation: {}", tenant, ex.getMessage(), ex);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage());
        }
    }

    private ResponseEntity<?> handleClearCacheDeviceToClient(String tenant, Map<String, String> parameters) {
        configurationRegistry.clearCacheDeviceToClient(tenant);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    private ResponseEntity<?> handleAddSampleMappings(String tenant, Map<String, String> parameters)
            throws Exception {
        String directionParam = parameters.get("direction");
        if (directionParam == null || directionParam.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Parameter 'direction' is required");
        }
        Direction direction = Direction.valueOf(directionParam);
        if (direction.equals(Direction.INBOUND)) {
            String samples = serviceConfigurationService.getSampleMappingsInbound_01();
            List<Mapping> mappings = objectMapper.readValue(samples, new TypeReference<List<Mapping>>() {
            });
            List<Mapping> existingMappings = mappingService.getMappings(tenant, Direction.INBOUND);
            mappings.forEach(mapping -> {
                AtomicBoolean alreadyExits = new AtomicBoolean(false);
                existingMappings.forEach(existingMapping -> {
                    if (existingMapping.getIdentifier().equals(mapping.getIdentifier()))
                        alreadyExits.set(true);
                });
                if (!alreadyExits.get()) {
                    mapping.setActive(false);
                    mappingService.createMapping(tenant, mapping);
                }
            });
        } else {
            List<Mapping> existingMappings = mappingService.getMappings(tenant, Direction.OUTBOUND);
            String samples = serviceConfigurationService.getSampleMappingsOutbound_01();
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
                    mappingService.createMapping(tenant, mapping);
                }
            });
        }
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    private ResponseEntity<?> handleInitCodeTemplates(String tenant, Map<String, String> parameters) throws Exception {
        ServiceConfiguration serviceConfiguration = serviceConfigurationService.getServiceConfiguration(tenant);
        log.debug("{} - Init system code template", tenant);

        serviceConfigurationService.initCodeTemplates(serviceConfiguration, true);

        try {
            serviceConfigurationService.saveServiceConfiguration(tenant, serviceConfiguration);
            configurationRegistry.addServiceConfiguration(tenant, serviceConfiguration);
        } catch (JsonProcessingException ex) {
            log.error("{} - Error saving service configuration with code templates: {}", tenant, ex);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getLocalizedMessage());
        }

        c8YAgent.createOperationEvent(
                "System code templates re-initialized",
                LoggingEventType.CODE_TEMPLATE_INIT_EVENT_TYPE,
                DateTime.now(),
                tenant,
                null);

        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    private ResponseEntity<?> handleCopySnoopedSourceTemplate(String tenant, Map<String, String> parameters)
            throws Exception {
        String id = parameters.get("id");
        String indexParam = parameters.get("index");
        if (indexParam == null || indexParam.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Parameter 'index' is required");
        }
        Integer index = Integer.parseInt(indexParam);
        mappingService.updateSourceTemplate(tenant, id, index);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    private ResponseEntity<?> handleReloadMappings(String tenant) throws ConnectorRegistryException {
        // Rebuild all caches at once
        mappingService.rebuildMappingCaches(tenant, ConnectorId.INTERNAL);

        // Get the updated mappings from cache
        List<Mapping> updatedMappingsInbound = new ArrayList<>(
                mappingService.getCacheMappingInbound(tenant).values());
        List<Mapping> updatedMappingsOutbound = new ArrayList<>(
                mappingService.getCacheOutboundMappings(tenant).values());

        // Update connector subscriptions
        Map<String, AConnectorClient> connectorMap = connectorRegistry.getClientsForTenant(tenant);
        connectorMap.values().forEach(client -> {
            // We always start with a cleanSession in case we reload the mappings
            client.initializeSubscriptionsInbound(updatedMappingsInbound, false);
            updatedMappingsOutbound.forEach(mapping -> client.updateSubscriptionForOutbound(mapping, false, false));
        });

        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    private ResponseEntity<?> handleResetDeploymentMap(String tenant) throws Exception {
        deploymentMapService.initializeTenantDeploymentMap(tenant, true);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    private ResponseEntity<?> handleSnoopReset(String tenant, Map<String, String> parameters) throws Exception {
        String id = parameters.get("id");
        mappingService.resetSnoop(tenant, id);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    private ResponseEntity<?> handleActivateMapping(String tenant, Map<String, String> parameters) throws Exception {
        String id = parameters.get("id");
        Boolean activation = Boolean.parseBoolean(parameters.get("active"));
        Mapping updatedMapping = mappingService.setActivationMapping(tenant, id, activation);
        Map<String, AConnectorClient> connectorMap = connectorRegistry
                .getClientsForTenant(tenant);
        // subscribe/unsubscribe respective mappingTopic of mapping only for
        // outbound mapping
        Map<String, String> failed = new HashMap<>();
        for (AConnectorClient client : connectorMap.values()) {
            if (updatedMapping.getDirection() == Direction.INBOUND) {
                if (!client.updateSubscriptionForInbound(updatedMapping, false, true)) {
                    ConnectorConfiguration conf = client.getConnectorConfiguration();
                    failed.put(conf.getIdentifier(), conf.getName());
                }
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
        mappingService.setFilterMapping(tenant, id, filterMapping);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    private ResponseEntity<?> handleApplyUpdateCode(String tenant, Map<String, String> parameters) throws Exception {
        String id = parameters.get("id");
        String code = parameters.get("code");
        mappingService.setCodeMapping(tenant, id, code);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    private ResponseEntity<?> handleDebugMapping(String tenant, Map<String, String> parameters) throws Exception {
        String id = parameters.get("id");
        Boolean debugBoolean = Boolean.parseBoolean(parameters.get("debug"));
        mappingService.setDebugMapping(tenant, id, debugBoolean);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    private ResponseEntity<?> handleRefreshStatusMapping(String tenant) throws Exception {
        mappingService.sendMappingStatus(tenant);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    private ResponseEntity<?> handleResetStatusMapping(String tenant) throws Exception {
        mappingStatusService.initializeTenantStatus(tenant, true);
        mappingService.sendMappingStatus(tenant);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    private ResponseEntity<?> handleReloadExtensions(String tenant) throws Exception {
        extensionManager.reloadExtensions(tenant);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    private ResponseEntity<?> handleRefreshNotifications(String tenant) throws Exception {
        configurationRegistry.getNotificationSubscriber().notificationSubscriberReconnect(tenant);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    private ResponseEntity<?> handleSnoopMapping(String tenant, Map<String, String> parameters) throws Exception {
        String id = parameters.get("id");
        String snoopStatusParam = parameters.get("snoopStatus");
        if (snoopStatusParam == null || snoopStatusParam.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Parameter 'snoopStatus' is required");
        }
        SnoopStatus newSnoop = SnoopStatus.valueOf(snoopStatusParam);
        mappingService.setSnoopStatusMapping(tenant, id, newSnoop);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    private ResponseEntity<?> handleConnect(String tenant, Map<String, String> parameters)
            throws JsonProcessingException, ConnectorRegistryException, ConnectorException {
        String connectorIdentifier = parameters.get("connectorIdentifier");
        if (connectorIdentifier == null || connectorIdentifier.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Parameter 'connectorIdentifier' is required");
        }
        ConnectorConfiguration configuration = connectorConfigurationService
                .getConnectorConfiguration(connectorIdentifier, tenant);

        ServiceConfiguration serviceConfiguration = serviceConfigurationService
                .getServiceConfiguration(tenant);

        // Must be set and persisted before triggering the connection, because
        // AConnectorClient.loadConfiguration() reloads from persistence inside submitConnect().
        // If we save after, loadConfiguration() reads enabled=false and shouldConnect() returns false.
        configuration.setEnabled(true);
        connectorConfigurationService.saveConnectorConfiguration(configuration);

        // If the client is already registered (e.g. DISCONNECTED after a failed startup),
        // reconnect it directly. initializeConnectorByConfiguration uses putIfAbsent, so the
        // old client would stay in the registry and the new one would be silently dropped,
        // leaving the registered client's status unchanged.
        AConnectorClient existingClient = connectorRegistry.getClientForTenant(tenant, connectorIdentifier);
        Future<?> connectTask;
        if (existingClient != null) {
            log.info("{} - Client {} already registered, reconnecting existing client", tenant, connectorIdentifier);
            connectTask = existingClient.reconnect();
        } else {
            connectTask = bootstrapService.initializeConnectorByConfiguration(configuration, serviceConfiguration,
                    tenant);
        }

        // Wait up to 10s for fast connections; if still connecting after timeout just
        // return success — the async task continues in the background.
        if (connectTask != null) {
            try {
                connectTask.get(10, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                log.info("{} - Connector {} still connecting after 10s, returning success (async)", tenant,
                        connectorIdentifier);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("{} - Interrupted while waiting for connector to connect: {}", tenant, connectorIdentifier);
            } catch (Exception e) {
                log.error("{} - Connector {} failed to connect: {}", tenant, connectorIdentifier, e.getMessage(), e);
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
            }
        }

        // Reconnect outbound notification subscriptions after the connector is ready
        AConnectorClient client = connectorRegistry.getClientForTenant(tenant, connectorIdentifier);
        if (client != null && client.supportedDirections().contains(Direction.OUTBOUND)) {
            configurationRegistry.getNotificationSubscriber().notificationSubscriberReconnect(tenant);
        }

        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    private ResponseEntity<?> handleDisconnect(String tenant, Map<String, String> parameters)
            throws JsonProcessingException, ConnectorRegistryException {
        String connectorIdentifier = parameters.get("connectorIdentifier");
        if (connectorIdentifier == null || connectorIdentifier.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Parameter 'connectorIdentifier' is required");
        }
        ConnectorConfiguration configuration = connectorConfigurationService
                .getConnectorConfiguration(connectorIdentifier, tenant);
        if (configuration == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "No connector configuration found for identifier: " + connectorIdentifier);
        }

        AConnectorClient client = connectorRegistry.getClientForTenant(tenant, connectorIdentifier);
        if (client == null) {
            // Connector is not actively running — mark as disabled and return success
            log.info("{} - Connector {} is not active, marking as disabled", tenant, connectorIdentifier);
            configuration.setEnabled(false);
            connectorConfigurationService.saveConnectorConfiguration(configuration);
            return ResponseEntity.status(HttpStatus.CREATED).build();
        }

        configuration.setEnabled(false);
        connectorConfigurationService.saveConnectorConfiguration(configuration);
        bootstrapService.disableConnector(tenant, client.getConnectorIdentifier());
        // Reconnect other notification clients for remaining connectors
        configurationRegistry.getNotificationSubscriber().notificationSubscriberReconnect(tenant);

        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    // Add other private handler methods for each operation type...

    private ResponseEntity<?> handleClearCache(String tenant, Map<String, String> parameters) {
        String cacheId = parameters.get("cacheId");
        if ("INBOUND_ID_CACHE".equals(cacheId)) {
            Integer cacheSize = serviceConfigurationService
                    .getServiceConfiguration(tenant).getInboundExternalIdCacheSize();
            configurationRegistry.getC8yAgent().clearInboundExternalIdCache(tenant, false, cacheSize);
            log.info("{} - Cache cleared: {}", tenant, cacheId);
            return ResponseEntity.status(HttpStatus.CREATED).build();
        } else if ("INVENTORY_CACHE".equals(cacheId)) {
            Integer cacheSize = serviceConfigurationService
                    .getServiceConfiguration(tenant).getInventoryCacheSize();
            configurationRegistry.getC8yAgent().clearInventoryCache(tenant, false, cacheSize);
            log.info("{} - Cache cleared: {}", tenant, cacheId);
            return ResponseEntity.status(HttpStatus.CREATED).build();
        } else if ("MOCK_IDENTITY_CACHE".equals(cacheId)) {
            identityFacade.clearMockIdentityCache();
            log.info("{} - Cache cleared: {}", tenant, cacheId);
            return ResponseEntity.status(HttpStatus.CREATED).build();
        } else if ("MOCK_INVENTORY_CACHE".equals(cacheId)) {
            inventoryFacade.clearInventoryCache();
            log.info("{} - Cache cleared: {}", tenant, cacheId);
            return ResponseEntity.status(HttpStatus.CREATED).build();
        } else if ("FLOW_STATE_CACHE".equals(cacheId)) {
            flowStateStore.clearTenantState(tenant);
            log.info("{} - Cache cleared: {}", tenant, cacheId);
            return ResponseEntity.status(HttpStatus.CREATED).build();
        }

        String errorMsg = String.format("Tenant %s - Unknown cache: %s", tenant, cacheId);
        log.error(errorMsg);
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, errorMsg);
    }

}