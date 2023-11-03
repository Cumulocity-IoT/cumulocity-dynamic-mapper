/*
 * Copyright (c) 2022 Software AG, Darmstadt, Germany and/or Software AG USA Inc., Reston, VA, USA,
 * and/or its subsidiaries and/or its affiliates and/or their licensors.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * @authors Christof Strack, Stefan Witschel
 */

package mqtt.mapping.rest;

import com.cumulocity.microservice.context.ContextService;
import com.cumulocity.microservice.context.credentials.UserCredentials;
import com.cumulocity.microservice.security.service.RoleService;
import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.extern.slf4j.Slf4j;
import mqtt.mapping.configuration.ConnectorConfiguration;
import mqtt.mapping.configuration.ConnectorConfigurationComponent;
import mqtt.mapping.configuration.ServiceConfiguration;
import mqtt.mapping.configuration.ServiceConfigurationComponent;
import mqtt.mapping.connector.core.client.IConnectorClient;
import mqtt.mapping.connector.core.ConnectorProperty;
import mqtt.mapping.connector.core.ConnectorPropertyConfiguration;
import mqtt.mapping.connector.core.registry.ConnectorRegistry;
import mqtt.mapping.connector.core.registry.ConnectorRegistryException;
import mqtt.mapping.core.*;
import mqtt.mapping.model.Mapping;
import mqtt.mapping.model.*;
import mqtt.mapping.processor.model.ProcessingContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
public class MQTTMappingRestController {

    @Autowired
    ConnectorRegistry connectorRegistry;

    @Autowired
    C8YAgent c8yAgent;

    @Autowired
    MappingComponent mappingComponent;


    @Autowired
    ConnectorConfigurationComponent connectorConfigurationComponent;

    @Autowired
    ServiceConfigurationComponent serviceConfigurationComponent;

    @Autowired
    private RoleService roleService;

    @Autowired
    private ContextService<UserCredentials> contextService;

    @Autowired
    private MappingComponent mappingStatusComponent;

    @Value("${APP.externalExtensionsEnabled}")
    private boolean externalExtensionsEnabled;

    @Value("${APP.outputMappingEnabled}")
    private boolean outputMappingEnabled;

    @Value("${APP.userRolesEnabled}")
    private Boolean userRolesEnabled;

    @Value("${APP.mappingAdminRole}")
    private String mappingAdminRole;

    @Value("${APP.mappingCreateRole}")
    private String mappingCreateRole;

    @RequestMapping(value = "/feature", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Feature> getFeatures() {
        log.info("Get Feature status");
        Feature feature = new Feature();
        feature.setOutputMappingEnabled(outputMappingEnabled);
        feature.setExternalExtensionsEnabled(externalExtensionsEnabled);
        feature.setUserHasMQTTMappingCreateRole(userHasMappingCreateRole());
        feature.setUserHasMQTTMappingAdminRole(userHasMappingAdminRole());
        return new ResponseEntity<Feature>(feature, HttpStatus.OK);
    }

    @RequestMapping(value = "/configuration/connectionPropertyConfig", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<ConnectorPropertyConfiguration>> getConnectionConfiugration() {
        HashMap<String, IConnectorClient> clients = null;
        List<ConnectorPropertyConfiguration> connectorConfigurations = new ArrayList<>();
        try {
             clients = connectorRegistry.getClientsForTenant(contextService.getContext().getTenant());
        } catch (ConnectorRegistryException e) {
            log.error("Could not get Configuration Properties:", e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getLocalizedMessage());
        }
        log.info("Get connection Properties");
        for (IConnectorClient client : clients.values()) {
            ConnectorPropertyConfiguration config = new ConnectorPropertyConfiguration(client.getConntectorId(), client.getConfigProperties());
            connectorConfigurations.add(config);
        }
        return ResponseEntity.ok(connectorConfigurations);
    }

    @RequestMapping(value = "/configuration/connection", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<HttpStatus> configureConnectionToBroker(
            @Valid @RequestBody ConnectorConfiguration configuration) {

        if (!userHasMappingAdminRole()) {
            log.error("Insufficient Permission, user does not have required permission to access this API");
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Insufficient Permission, user does not have required permission to access this API");
        }

        //Remove sensitive data before printing to log
        ConnectorConfiguration clonedConfig = (ConnectorConfiguration) configuration.clone();
        for (String property : clonedConfig.getProperties().keySet()) {
                try {
                IConnectorClient client = connectorRegistry.getClientForTenant(contextService.getContext().getTenant(), clonedConfig.getConnectorId());
                if (ConnectorProperty.SENSITIVE_STRING_PROPERTY == client.getConfigProperties().get(property)) {
                    clonedConfig.getProperties().replace(property, "****");
                }
            } catch (ConnectorRegistryException e) {
                log.error("Could not get Configuration Properties:", e);
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getLocalizedMessage());
            }
        }
        log.info("Post Connector configuration: {}", clonedConfig.toString());
        try {

            connectorConfigurationComponent.saveConnectionConfiguration(configuration);
            IConnectorClient client = connectorRegistry.getClientForTenant(contextService.getContext().getTenant(), configuration.getConnectorId());
            client.reconnect();
            return ResponseEntity.status(HttpStatus.CREATED).build();
        } catch (Exception ex) {
            log.error("Error getting mqtt broker configuration {}", ex);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getLocalizedMessage());
        }
    }

    @RequestMapping(value = "/configuration/connection", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<ConnectorConfiguration>> getConnectionConfiguration() {
        log.info("Get connection details");
        try {
            List<ConnectorConfiguration> configurations = connectorConfigurationComponent.loadAllConnectorConfiguration();
            List<ConnectorConfiguration> modifiedConfigs = new ArrayList<>();

            //Remove sensitive data before sending to UI
            for (ConnectorConfiguration config : configurations) {
                ConnectorConfiguration clonedConfig = (ConnectorConfiguration) config.clone();
                for (String property : clonedConfig.getProperties().keySet()) {
                    try {
                        IConnectorClient client = connectorRegistry.getClientForTenant(contextService.getContext().getTenant(), clonedConfig.getConnectorId());
                        if (ConnectorProperty.SENSITIVE_STRING_PROPERTY == client.getConfigProperties().get(property)) {
                            clonedConfig.getProperties().replace(property, "");
                        }

                    } catch (ConnectorRegistryException e) {
                        log.error("Could not get Configuration Properties:", e);
                        throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getLocalizedMessage());
                    }
                }
                modifiedConfigs.add(clonedConfig);
            }
            return ResponseEntity.ok(modifiedConfigs);
        } catch (Exception ex) {
            log.error("Error on loading configuration {}", ex);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getLocalizedMessage());
        }
    }



    @RequestMapping(value = "/configuration/service", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ServiceConfiguration> getServiceConfiguration() {
        log.info("Get connection details");

        try {
            final ServiceConfiguration configuration = serviceConfigurationComponent.loadServiceConfiguration();
            if (configuration == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Service connection not available");
            }
            // don't modify original copy
            return new ResponseEntity<ServiceConfiguration>(configuration, HttpStatus.OK);
        } catch (Exception ex) {
            log.error("Error on loading configuration {}", ex);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getLocalizedMessage());
        }
    }

    @RequestMapping(value = "/configuration/service", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<HttpStatus> configureConnectionToBroker(
            @Valid @RequestBody ServiceConfiguration configuration) {

        // don't modify original copy
        log.info("Post service configuration: {}", configuration.toString());

        if (!userHasMappingAdminRole()) {
            log.error("Insufficient Permission, user does not have required permission to access this API");
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Insufficient Permission, user does not have required permission to access this API");
        }

        try {
            serviceConfigurationComponent.saveServiceConfiguration(configuration);
            return ResponseEntity.status(HttpStatus.CREATED).build();
        } catch (Exception ex) {
            log.error("Error getting mqtt broker configuration {}", ex);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getLocalizedMessage());
        }
    }

    @RequestMapping(value = "/operation", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<HttpStatus> runOperation(@Valid @RequestBody ServiceOperation operation) {
        //TODO ConnectorId must be added to the Parameter of the Operations from the UI.
        log.info("Post operation: {}", operation.toString());
        try {
            String tenant = contextService.getContext().getTenant();
            IConnectorClient client = connectorRegistry.getClientForTenant(contextService.getContext().getTenant(), operation.getParameter().get("connectorId"));
            if (operation.getOperation().equals(Operation.RELOAD_MAPPINGS)) {
                mappingComponent.rebuildMappingOutboundCache(tenant);
                // in order to keep MappingInboundCache and ActiveSubscriptionMappingInbound in
                // sync, the ActiveSubscriptionMappingInbound is build on the
                // previously used updatedMappings

                List<Mapping> updatedMappings = mappingComponent.rebuildMappingInboundCache(tenant);
                client.updateActiveSubscriptions(updatedMappings, false);
            } else if (operation.getOperation().equals(Operation.CONNECT)) {
                client.connect();
            } else if (operation.getOperation().equals(Operation.DISCONNECT)) {
                client.disconnect();
            } else if (operation.getOperation().equals(Operation.REFRESH_STATUS_MAPPING)) {
                mappingComponent.sendStatusMapping(tenant);
            } else if (operation.getOperation().equals(Operation.RESET_STATUS_MAPPING)) {
                mappingComponent.initializeMappingStatus(tenant, true);
            } else if (operation.getOperation().equals(Operation.RELOAD_EXTENSIONS)) {
                c8yAgent.reloadExtensions();
            } else if (operation.getOperation().equals(Operation.ACTIVATE_MAPPING)) {
                String id = operation.getParameter().get("id");
                Boolean activeBoolean = Boolean.parseBoolean(operation.getParameter().get("active"));
                mappingComponent.setActivationMapping(tenant, id, activeBoolean);
            } else if (operation.getOperation().equals(Operation.REFRESH_NOTFICATIONS_SUBSCRIPTIONS)) {
                c8yAgent.notificationSubscriberReconnect(contextService.getContext().getTenant());
            }
            return ResponseEntity.status(HttpStatus.CREATED).build();
        } catch (Exception ex) {
            log.error("Error getting mqtt broker configuration {}", ex);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getLocalizedMessage());
        }
    }

    @RequestMapping(value = "/monitoring/status/service/{connectorId}", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ServiceStatus> getServiceStatus(@PathVariable @NotNull String connectorId) {
        try {
            IConnectorClient client = connectorRegistry.getClientForTenant(contextService.getContext().getTenant(), connectorId);
            ServiceStatus st = client.getServiceStatus();
            log.info("Get status for connector {}: {}",connectorId, st);
            return new ResponseEntity<>(st, HttpStatus.OK);
        } catch (ConnectorRegistryException e) {
            throw new RuntimeException(e);
        }
    }

    @RequestMapping(value = "/monitoring/status/mapping", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<MappingStatus>> getMappingStatus() {
        String tenant = contextService.getContext().getTenant();
        List<MappingStatus> ms = mappingStatusComponent.getMappingStatus(tenant);
        log.info("Get mapping status: {}", ms);
        return new ResponseEntity<List<MappingStatus>>(ms, HttpStatus.OK);
    }

    @RequestMapping(value = "/monitoring/tree", method = RequestMethod.GET, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TreeNode> getInboundMappingTree() {
        String tenant = contextService.getContext().getTenant();
        TreeNode result = mappingComponent.getResolverMappingInbound().get(tenant);
        log.info("Get mapping tree!");
        return ResponseEntity.status(HttpStatus.OK).body(result);
    }

    @RequestMapping(value = "/monitoring/subscription/{connectorId}", method = RequestMethod.GET, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Integer>> getActiveSubscriptions(@PathVariable @NotNull String connectorId) {
        String tenant = contextService.getContext().getTenant();
        IConnectorClient client = null;
        try {
            client = connectorRegistry.getClientForTenant(contextService.getContext().getTenant(), connectorId);
            Map<String, Integer> result = client.getActiveSubscriptions(tenant);
            log.info("Get active subscriptions!");
            return ResponseEntity.status(HttpStatus.OK).body(result);
        } catch (ConnectorRegistryException e) {
            throw new RuntimeException(e);
        }

    }

    @RequestMapping(value = "/mapping", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<Mapping>> getMappings() {
        log.info("Get mappings");
        List<Mapping> result = mappingComponent.getMappings(contextService.getContext().getTenant());
        return ResponseEntity.status(HttpStatus.OK).body(result);
    }

    @RequestMapping(value = "/mapping/{id}", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Mapping> getMapping(@PathVariable String id) {
        log.info("Get mapping: {}", id);
        Mapping result = mappingComponent.getMapping(contextService.getContext().getTenant(), id);
        if (result == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(result);
        }
        return ResponseEntity.status(HttpStatus.OK).body(result);
    }

    @RequestMapping(value = "/mapping/{id}", method = RequestMethod.DELETE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> deleteMapping(@PathVariable String id) {
        log.info("Delete mapping: {}", id);
        Mapping mapping = null;
        String tenant = contextService.getContext().getTenant();
        try {
            final Mapping deletedMapping = mappingComponent.deleteMapping(tenant, id);
            if (deletedMapping == null)
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Mapping with id " + id + " could not be found.");

            mappingComponent.deleteFromMappingCache(tenant, deletedMapping);

            if (!Direction.OUTBOUND.equals(deletedMapping.direction)) {
                //TODO Move this to mapping component, call unsubscribe on client
                //FIXME Currently we create mappings in ALL connectors assuming they could occur in all of them.
                HashMap<String, IConnectorClient> clients  = connectorRegistry.getClientsForTenant(tenant);
                clients.keySet().stream().forEach(connector -> {
                    clients.get(connector).deleteActiveSubscription(deletedMapping);
                });
            }
        } catch (Exception ex) {
            log.error("Deleting active mappings is not allowed {}", ex);
            throw new ResponseStatusException(HttpStatus.NOT_ACCEPTABLE, ex.getLocalizedMessage());
        }

        return ResponseEntity.status(HttpStatus.OK).body(mapping.id);

    }

    @RequestMapping(value = "/mapping", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Mapping> createMapping(@Valid @RequestBody Mapping mapping) {
        try {
            log.info("Add mapping: {}", mapping);
            String tenant = contextService.getContext().getTenant();
            final Mapping createdMapping = mappingComponent.createMapping(tenant, mapping);
            if (Direction.OUTBOUND.equals(createdMapping.direction)) {
                mappingComponent.rebuildMappingOutboundCache(tenant);
            } else {
                //FIXME Currently we create mappings in ALL connectors assuming they could occur in all of them.
                HashMap<String, IConnectorClient> clients  = connectorRegistry.getClientsForTenant(tenant);
                clients.keySet().stream().forEach(connector -> {
                    clients.get(connector).upsertActiveSubscription(createdMapping);
                });
                mappingComponent.deleteFromCacheMappingInbound(tenant, createdMapping);
                mappingComponent.addToCacheMappingInbound(tenant, createdMapping);
                mappingComponent.getCacheMappingInbound().get(tenant).put(createdMapping.id, mapping);
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

    @RequestMapping(value = "/mapping/{id}", method = RequestMethod.PUT, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Mapping> updateMapping(@PathVariable String id, @Valid @RequestBody Mapping mapping) {
        try {
            log.info("Update mapping: {}, {}", mapping, id);
            String tenant = contextService.getContext().getTenant();
            final Mapping updatedMapping = mappingComponent.updateMapping(tenant, mapping, false, false);
            if (Direction.OUTBOUND.equals(mapping.direction)) {
                mappingComponent.rebuildMappingOutboundCache(tenant);
            } else {
                HashMap<String, IConnectorClient> clients = connectorRegistry.getClientsForTenant(tenant);
                clients.keySet().stream().forEach(connector -> {
                    clients.get(connector).upsertActiveSubscription(updatedMapping);
                });
                mappingComponent.deleteFromCacheMappingInbound(tenant, mapping);
                mappingComponent.addToCacheMappingInbound(tenant, mapping);
                mappingComponent.getCacheMappingInbound().get(tenant).put(mapping.id, mapping);
            }
            return ResponseEntity.status(HttpStatus.OK).body(mapping);
        } catch (Exception ex) {
            if (ex instanceof IllegalArgumentException) {
                log.error("Updating active mappings is not allowed {}", ex);
                throw new ResponseStatusException(HttpStatus.NOT_ACCEPTABLE, ex.getLocalizedMessage());
            } else if (ex instanceof RuntimeException)
                throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getLocalizedMessage());
            else if (ex instanceof JsonProcessingException)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getLocalizedMessage());
            else
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getLocalizedMessage());
        }
    }

    @RequestMapping(value = "/test/{method}", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<ProcessingContext<?>>> forwardPayload(@PathVariable String method,
            @RequestParam URI topic,
            @Valid @RequestBody Map<String, Object> payload) {
        String path = topic.getPath();
        List<ProcessingContext<?>> result = null;
        log.info("Test payload: {}, {}, {}", path, method, payload);
        try {
            boolean send = ("send").equals(method);
            Map<String,IConnectorClient> connectorClients = null;
            try {
                connectorClients = connectorRegistry.getClientsForTenant(contextService.getContext().getTenant());
            } catch (ConnectorRegistryException e) {
                throw new RuntimeException(e);
            }
            //TODO For multiple connectors this would lead to only the result of the last one will be returned
            //TODO As an alternative we can expose the connector ID to the UI and the user can select to which connector he wants to test
            for (String connector : connectorClients.keySet()) {
                try {
                    result = connectorClients.get(connector).test(path, send, payload);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
            //List<ProcessingContext<?>> result = mqttClient.test(path, send, payload);
            return new ResponseEntity<List<ProcessingContext<?>>>(result, HttpStatus.OK);
        } catch (Exception ex) {
            log.error("Error transforming payload: {}", ex);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getLocalizedMessage());
        }
    }

    @RequestMapping(value = "/extension", method = RequestMethod.GET, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Extension>> getProcessorExtensions() {
        Map<String, Extension> result = c8yAgent.getProcessorExtensions();
        return ResponseEntity.status(HttpStatus.OK).body(result);
    }

    @RequestMapping(value = "/extension/{extensionName}", method = RequestMethod.GET, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Extension> getProcessorExtension(@PathVariable String extensionName) {
        Extension result = c8yAgent.getProcessorExtension(extensionName);
        if (result == null)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Extension with id " + extensionName + " could not be found.");
        return ResponseEntity.status(HttpStatus.OK).body(result);
    }

    @RequestMapping(value = "/extension/{extensionName}", method = RequestMethod.DELETE, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Extension> deleteProcessorExtension(@PathVariable String extensionName) {

        if (!userHasMappingAdminRole()) {
            log.error("Insufficient Permission, user does not have required permission to access this API");
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Insufficient Permission, user does not have required permission to access this API");
        }
        Extension result = c8yAgent.deleteProcessorExtension(extensionName);
        if (result == null)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Extension with id " + extensionName + " could not be found.");
        return ResponseEntity.status(HttpStatus.OK).body(result);
    }

    private boolean userHasMappingAdminRole() {
        return !userRolesEnabled || (userRolesEnabled && roleService.getUserRoles().contains(mappingAdminRole));
    }

    private boolean userHasMappingCreateRole() {
        return !userRolesEnabled || userHasMappingAdminRole()
                || (userRolesEnabled && roleService.getUserRoles().contains(mappingCreateRole));
    }

}