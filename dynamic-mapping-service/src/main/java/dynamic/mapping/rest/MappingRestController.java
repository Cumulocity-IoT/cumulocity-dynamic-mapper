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

package dynamic.mapping.rest;

import java.net.URI;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collectors;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;

import dynamic.mapping.configuration.ConnectorConfiguration;
import dynamic.mapping.configuration.ConnectorConfigurationComponent;
import dynamic.mapping.configuration.ServiceConfiguration;
import dynamic.mapping.configuration.ServiceConfigurationComponent;

import dynamic.mapping.connector.core.ConnectorSpecification;
import dynamic.mapping.connector.core.client.AConnectorClient;
import dynamic.mapping.connector.core.registry.ConnectorRegistry;
import dynamic.mapping.connector.core.registry.ConnectorRegistryException;
import dynamic.mapping.processor.model.ProcessingContext;
import org.apache.commons.lang3.mutable.MutableInt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.cumulocity.microservice.context.ContextService;
import com.cumulocity.microservice.context.credentials.UserCredentials;
import com.cumulocity.microservice.security.service.RoleService;
import com.fasterxml.jackson.core.JsonProcessingException;

import lombok.extern.slf4j.Slf4j;
import dynamic.mapping.core.BootstrapService;
import dynamic.mapping.core.C8YAgent;
import dynamic.mapping.core.MappingComponent;
import dynamic.mapping.core.Operation;
import dynamic.mapping.core.ServiceOperation;
import dynamic.mapping.core.Status;
import dynamic.mapping.model.Direction;
import dynamic.mapping.model.Extension;
import dynamic.mapping.model.Feature;
import dynamic.mapping.model.InnerNode;
import dynamic.mapping.model.Mapping;
import dynamic.mapping.model.MappingStatus;

@Slf4j
@RestController
public class MappingRestController {

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
    BootstrapService bootstrapService;

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
        String tenant = contextService.getContext().getTenant();
        log.info("Tenant {} - Get Feature status", tenant);
        Feature feature = new Feature();
        feature.setOutputMappingEnabled(outputMappingEnabled);
        feature.setExternalExtensionsEnabled(externalExtensionsEnabled);
        feature.setUserHasMappingCreateRole(userHasMappingCreateRole());
        feature.setUserHasMappingAdminRole(userHasMappingAdminRole());
        return new ResponseEntity<Feature>(feature, HttpStatus.OK);
    }

    @RequestMapping(value = "/configuration/connector/specifications", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<ConnectorSpecification>> getConnectorSpecifications() {
        String tenant = contextService.getContext().getTenant();
        List<ConnectorSpecification> connectorConfigurations = new ArrayList<>();
        log.info("Tenant {} - Getting connection properties...", tenant);
        Map<String, ConnectorSpecification> spec = connectorRegistry
                .getConnectorSpecifications();
        // Iterate over all connectors
        for (String connectorId : spec.keySet()) {
            connectorConfigurations.add(spec.get(connectorId));
        }
        return ResponseEntity.ok(connectorConfigurations);
    }

    @RequestMapping(value = "/configuration/connector/instance", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<HttpStatus> createConnectorConfiguration(
            @Valid @RequestBody ConnectorConfiguration configuration) {
        String tenant = contextService.getContext().getTenant();
        if (!userHasMappingAdminRole()) {
            log.error("Tenant {} - Insufficient Permission, user does not have required permission to access this API",
                    tenant);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Insufficient Permission, user does not have required permission to access this API");
        }
        // Remove sensitive data before printing to log
        ConnectorConfiguration clonedConfig = getCleanedConfig(configuration);
        log.info("Tenant {} - Post Connector configuration: {}", tenant, clonedConfig.toString());
        try {
            connectorConfigurationComponent.saveConnectorConfiguration(configuration);
            bootstrapService.initializeConnectorByConfiguration(configuration, contextService.getContext(), tenant);
            return ResponseEntity.status(HttpStatus.CREATED).build();
        } catch (Exception ex) {
            log.error("Tenant {} - Error getting mqtt broker configuration {}", tenant, ex);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getLocalizedMessage());
        }
    }

    @RequestMapping(value = "/configuration/connector/instances", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<ConnectorConfiguration>> getConnectionConfigurations() {
        String tenant = contextService.getContext().getTenant();
        log.info("Tenant {} - Get connection details", tenant);

        try {
            List<ConnectorConfiguration> configurations = connectorConfigurationComponent
                    .getConnectorConfigurations(tenant);
            List<ConnectorConfiguration> modifiedConfigs = new ArrayList<>();

            // Remove sensitive data before sending to UI
            for (ConnectorConfiguration config : configurations) {
                ConnectorConfiguration clonedConfig = (ConnectorConfiguration) config.clone();
                ConnectorConfiguration cleanedConfig = getCleanedConfig(clonedConfig);
                modifiedConfigs.add(cleanedConfig);
            }
            return ResponseEntity.ok(modifiedConfigs);
        } catch (Exception ex) {
            log.error("Tenant {} - Error on loading configuration {}", tenant, ex);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getLocalizedMessage());
        }
    }

    @RequestMapping(value = "/configuration/connector/instance/{ident}", method = RequestMethod.DELETE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> deleteConnectionConfiguration(@PathVariable String ident) {
        log.info("Delete connection instance {}", ident);
        String tenant = contextService.getContext().getTenant();
        if (!userHasMappingAdminRole()) {
            log.error("Tenant {} - Insufficient Permission, user does not have required permission to access this API",
                    tenant);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Insufficient Permission, user does not have required permission to access this API");
        }
        try {
            ConnectorConfiguration configuration = connectorConfigurationComponent.getConnectorConfiguration(ident,
                    tenant);
            AConnectorClient client = connectorRegistry.getClientForTenant(tenant,
                    configuration.getIdent());
            if (client == null)
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Client with ident " + ident + " not found");
            client.disconnect();
            bootstrapService.shutdownConnector(tenant, ident);
            connectorConfigurationComponent.deleteConnectionConfiguration(ident);
        } catch (Exception ex) {
            log.error("Tenant {} -Error getting mqtt broker configuration {}", tenant, ex);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getLocalizedMessage());
        }
        return ResponseEntity.status(HttpStatus.OK).body(ident);
    }

    @RequestMapping(value = "/configuration/connector/instance/{ident}", method = RequestMethod.PUT, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ConnectorConfiguration> updateConnectionConfiguration(@PathVariable String ident,
            @Valid @RequestBody ConnectorConfiguration configuration) {
        String tenant = contextService.getContext().getTenant();
        log.info("Tenant {} - Update connection instance {}", tenant, ident);
        // make sure we are using the correct ident
        configuration.ident = ident;

        if (!userHasMappingAdminRole()) {
            log.error("Tenant {} - Insufficient Permission, user does not have required permission to access this API",
                    tenant);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Insufficient Permission, user does not have required permission to access this API");
        }
        // Remove sensitive data before printing to log
        ConnectorConfiguration clonedConfig = getCleanedConfig(configuration);
        log.info("Tenant {} - Post Connector configuration: {}", tenant, clonedConfig.toString());
        try {

            connectorConfigurationComponent.saveConnectorConfiguration(configuration);
            AConnectorClient client = connectorRegistry.getClientForTenant(tenant,
                    configuration.getIdent());
            client.reconnect();
        } catch (Exception ex) {
            log.error("Tenant {} -Error getting mqtt broker configuration {}", tenant, ex);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getLocalizedMessage());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(configuration);
    }

    private ConnectorConfiguration getCleanedConfig(ConnectorConfiguration configuration) {
        ConnectorConfiguration clonedConfig = (ConnectorConfiguration) configuration.clone();
        for (String property : clonedConfig.getProperties().keySet()) {
            ConnectorSpecification connectorSpecification = connectorRegistry
                    .getConnectorSpecification(configuration.connectorId);
            if (connectorSpecification.isPropetySensitive(property)) {
                clonedConfig.getProperties().replace(property, "****");
            }
        }
        return clonedConfig;
    }

    @RequestMapping(value = "/configuration/service", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ServiceConfiguration> getServiceConfiguration() {
        String tenant = contextService.getContext().getTenant();
        log.info("Tenant {} - Get connection details", tenant);

        try {
            final ServiceConfiguration configuration = serviceConfigurationComponent.loadServiceConfiguration();
            if (configuration == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Service connection not available");
            }
            // don't modify original copy
            return new ResponseEntity<ServiceConfiguration>(configuration, HttpStatus.OK);
        } catch (Exception ex) {
            log.error("Tenant {} - Error on loading configuration {}", tenant, ex);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getLocalizedMessage());
        }
    }

    @RequestMapping(value = "/configuration/service", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<HttpStatus> configureConnectionToBroker(
            @Valid @RequestBody ServiceConfiguration configuration) {
        String tenant = contextService.getContext().getTenant();
        // don't modify original copy
        log.info("Tenant {} - Post service configuration: {}", tenant, configuration.toString());

        if (!userHasMappingAdminRole()) {
            log.error("Tenant {} - Insufficient Permission, user does not have required permission to access this API",
                    tenant);
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
        String tenant = contextService.getContext().getTenant();
        log.info("Tenant {} - Post operation: {}", tenant, operation.toString());
        try {
            if (operation.getOperation().equals(Operation.RELOAD_MAPPINGS)) {
                mappingComponent.rebuildMappingOutboundCache(tenant);
                // in order to keep MappingInboundCache and ActiveSubscriptionMappingInbound in
                // sync, the ActiveSubscriptionMappingInbound is build on the
                // previously used updatedMappings

                List<Mapping> updatedMappings = mappingComponent.rebuildMappingInboundCache(tenant);
                HashMap<String, AConnectorClient> connectorMap = connectorRegistry
                        .getClientsForTenant(tenant);
                for (AConnectorClient client : connectorMap.values()) {
                    client.updateActiveSubscriptions(updatedMappings, false);
                }
            } else if (operation.getOperation().equals(Operation.CONNECT)) {
                String connectorIdent = operation.getParameter().get("connectorIdent");
                ConnectorConfiguration configuration = connectorConfigurationComponent
                        .getConnectorConfiguration(connectorIdent, tenant);
                configuration.setEnabled(true);
                connectorConfigurationComponent.saveConnectorConfiguration(configuration);

                AConnectorClient client = connectorRegistry.getClientForTenant(tenant,
                        connectorIdent);
                client.submitConnect();
            } else if (operation.getOperation().equals(Operation.DISCONNECT)) {
                String connectorIdent = operation.getParameter().get("connectorIdent");
                ConnectorConfiguration configuration = connectorConfigurationComponent
                        .getConnectorConfiguration(connectorIdent, tenant);
                configuration.setEnabled(false);
                connectorConfigurationComponent.saveConnectorConfiguration(configuration);

                AConnectorClient client = connectorRegistry.getClientForTenant(tenant,
                        connectorIdent);
                client.submitDisconnect();
            } else if (operation.getOperation().equals(Operation.REFRESH_STATUS_MAPPING)) {
                mappingComponent.sendMappingStatus(tenant);
            } else if (operation.getOperation().equals(Operation.RESET_STATUS_MAPPING)) {
                mappingComponent.initializeMappingStatus(tenant, true);
            } else if (operation.getOperation().equals(Operation.RELOAD_EXTENSIONS)) {
                c8yAgent.reloadExtensions(tenant);
            } else if (operation.getOperation().equals(Operation.ACTIVATE_MAPPING)) {
                String id = operation.getParameter().get("id");
                Boolean activeBoolean = Boolean.parseBoolean(operation.getParameter().get("active"));
                mappingComponent.setActivationMapping(tenant, id, activeBoolean);
            } else if (operation.getOperation().equals(Operation.REFRESH_NOTFICATIONS_SUBSCRIPTIONS)) {
                c8yAgent.notificationSubscriberReconnect(tenant);
            }
            return ResponseEntity.status(HttpStatus.CREATED).build();
        } catch (Exception ex) {
            log.error("Tenant {} - Error getting mqtt broker configuration {}", tenant, ex);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getLocalizedMessage());
        }
    }

    @RequestMapping(value = "/monitoring/status/connector/{connectorIdent}", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Status> getConnectorStatus(@PathVariable @NotNull String connectorIdent) {
        try {
            String tenant = contextService.getContext().getTenant();
            AConnectorClient client = connectorRegistry.getClientForTenant(tenant,
                    connectorIdent);
            Status st = client.getConnectorStatus().getStatus();
            log.info("Tenant {} - Get status for connector {}: {}", tenant, connectorIdent, st);
            return new ResponseEntity<>(st, HttpStatus.OK);
        } catch (ConnectorRegistryException e) {
            throw new RuntimeException(e);
        }
    }

    @RequestMapping(value = "/monitoring/status/connectors", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Status>> getConnectorsStatus() {
        HashMap<String, Status> connectorsStatus = new HashMap<>();
        String tenant = contextService.getContext().getTenant();
        try {
            HashMap<String, AConnectorClient> connectorMap = connectorRegistry
                    .getClientsForTenant(tenant);
            if (connectorMap != null) {
                for (AConnectorClient client : connectorMap.values()) {
                    Status st = client.getConnectorStatus().getStatus();
                    connectorsStatus.put(client.getConnectorIdent(), st);
                }
            }
            log.info("Tenant {} - Get status for connectors: {}", tenant, connectorsStatus);
            return new ResponseEntity<>(connectorsStatus, HttpStatus.OK);
        } catch (ConnectorRegistryException e) {
            throw new RuntimeException(e);
        }
    }

    @RequestMapping(value = "/monitoring/status/mapping", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<MappingStatus>> getMappingStatus() {
        String tenant = contextService.getContext().getTenant();
        List<MappingStatus> ms = mappingStatusComponent.getMappingStatus(tenant);
        log.info("Tenant {} - Get mapping status: {}", tenant, ms);
        return new ResponseEntity<List<MappingStatus>>(ms, HttpStatus.OK);
    }

    @RequestMapping(value = "/monitoring/tree", method = RequestMethod.GET, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<InnerNode> getInboundMappingTree() {
        String tenant = contextService.getContext().getTenant();
        InnerNode result = mappingComponent.getResolverMappingInbound().get(tenant);
        log.info("Tenant {} - Get mapping tree!", tenant);
        return ResponseEntity.status(HttpStatus.OK).body(result);
    }

    @RequestMapping(value = "/monitoring/subscription/{connectorIdent}", method = RequestMethod.GET, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Integer>> getActiveSubscriptions(@PathVariable @NotNull String connectorIdent) {
        String tenant = contextService.getContext().getTenant();
        AConnectorClient client = null;
        try {
            client = connectorRegistry.getClientForTenant(tenant, connectorIdent);
            Map<String, MutableInt> as = client.getActiveSubscriptions();
            Map<String, Integer> result = as.entrySet().stream()
                    .map(entry -> new AbstractMap.SimpleEntry<String, Integer>(entry.getKey(),
                            entry.getValue().getValue()))
                    .collect(Collectors.toMap(Entry::getKey, Entry::getValue));

            log.info("Tenant {} - Get active subscriptions!", tenant);
            return ResponseEntity.status(HttpStatus.OK).body(result);
        } catch (ConnectorRegistryException e) {
            throw new RuntimeException(e);
        }

    }

    @RequestMapping(value = "/mapping", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<Mapping>> getMappings() {
        String tenant = contextService.getContext().getTenant();
        log.info("Tenant {} - Get mappings", tenant);
        List<Mapping> result = mappingComponent.getMappings(tenant);
        return ResponseEntity.status(HttpStatus.OK).body(result);
    }

    @RequestMapping(value = "/mapping/{id}", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Mapping> getMapping(@PathVariable String id) {
        String tenant = contextService.getContext().getTenant();
        log.info("Tenant {} - Get mapping: {}", tenant, id);
        Mapping result = mappingComponent.getMapping(tenant, id);
        if (result == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(result);
        }
        return ResponseEntity.status(HttpStatus.OK).body(result);
    }

    @RequestMapping(value = "/mapping/{id}", method = RequestMethod.DELETE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> deleteMapping(@PathVariable String id) {
        String tenant = contextService.getContext().getTenant();
        log.info("Tenant {} - Delete mapping: {}", tenant, id);
        try {
            final Mapping deletedMapping = mappingComponent.deleteMapping(tenant, id);
            if (deletedMapping == null)
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Mapping with id " + id + " could not be found.");

            mappingComponent.deleteFromMappingCache(tenant, deletedMapping);

            if (!Direction.OUTBOUND.equals(deletedMapping.direction)) {
                // FIXME Currently we create mappings in ALL connectors assuming they could
                // occur in all of them.
                HashMap<String, AConnectorClient> clients = connectorRegistry.getClientsForTenant(tenant);
                clients.keySet().stream().forEach(connector -> {
                    clients.get(connector).deleteActiveSubscription(deletedMapping);
                });
            }
        } catch (Exception ex) {
            log.error("Tenant {} - Deleting active mappings is not allowed {}", tenant, ex);
            throw new ResponseStatusException(HttpStatus.NOT_ACCEPTABLE, ex.getLocalizedMessage());
        }
        log.info("Tenant {} - After Delete mapping: {}", tenant, id);

        return ResponseEntity.status(HttpStatus.OK).body(id);
    }

    // TODO We might need to add the connector ID here to correlate mappings to
    // excactly one connector
    @RequestMapping(value = "/mapping", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Mapping> createMapping(@Valid @RequestBody Mapping mapping) {
        try {
            String tenant = contextService.getContext().getTenant();
            log.info("Tenant {} - Add mapping: {}", mapping);
            final Mapping createdMapping = mappingComponent.createMapping(tenant, mapping);
            if (Direction.OUTBOUND.equals(createdMapping.direction)) {
                mappingComponent.rebuildMappingOutboundCache(tenant);
            } else {
                // FIXME Currently we create mappings in ALL connectors assuming they could
                // occur in all of them.
                HashMap<String, AConnectorClient> clients = connectorRegistry.getClientsForTenant(tenant);
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
            String tenant = contextService.getContext().getTenant();
            log.info("Tenant {} - Update mapping: {}, {}", mapping, id);
            final Mapping updatedMapping = mappingComponent.updateMapping(tenant, mapping, false, false);
            if (Direction.OUTBOUND.equals(mapping.direction)) {
                mappingComponent.rebuildMappingOutboundCache(tenant);
            } else {
                HashMap<String, AConnectorClient> clients = connectorRegistry.getClientsForTenant(tenant);
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
                                                                     @RequestParam URI topic, @RequestParam String connectorIdent,
                                                                     @Valid @RequestBody Map<String, Object> payload) {
        String path = topic.getPath();
        List<ProcessingContext<?>> result = null;
        String tenant = contextService.getContext().getTenant();
        log.info("Tenant {} - Test payload: {}, {}, {}", tenant, path, method,
                payload);

        try {
            boolean send = ("send").equals(method);
            try {
                AConnectorClient connectorClient = connectorRegistry
                        .getClientForTenant(tenant, connectorIdent);
                result = connectorClient.test(path, send, payload);
            } catch (ConnectorRegistryException e) {
                throw new RuntimeException(e);
            }
            return new ResponseEntity<List<ProcessingContext<?>>>(result, HttpStatus.OK);
        } catch (Exception ex) {
            log.error("Error transforming payload: {}", ex);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getLocalizedMessage());
        }
    }

    @RequestMapping(value = "/extension", method = RequestMethod.GET, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Extension>> getProcessorExtensions() {
        String tenant = contextService.getContext().getTenant();
        Map<String, Extension> result = c8yAgent.getProcessorExtensions(tenant);
        return ResponseEntity.status(HttpStatus.OK).body(result);
    }

    @RequestMapping(value = "/extension/{extensionName}", method = RequestMethod.GET, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Extension> getProcessorExtension(@PathVariable String extensionName) {
        String tenant = contextService.getContext().getTenant();
        Extension result = c8yAgent.getProcessorExtension(tenant, extensionName);
        if (result == null)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Extension with id " + extensionName + " could not be found.");
        return ResponseEntity.status(HttpStatus.OK).body(result);
    }

    @RequestMapping(value = "/extension/{extensionName}", method = RequestMethod.DELETE, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Extension> deleteProcessorExtension(@PathVariable String extensionName) {
        String tenant = contextService.getContext().getTenant();
        if (!userHasMappingAdminRole()) {
            log.error("Insufficient Permission, user does not have required permission to access this API");
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Insufficient Permission, user does not have required permission to access this API");
        }
        Extension result = c8yAgent.deleteProcessorExtension(tenant, extensionName);
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