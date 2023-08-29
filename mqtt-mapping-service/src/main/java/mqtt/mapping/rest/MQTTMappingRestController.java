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

import com.cumulocity.microservice.security.service.RoleService;
import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.extern.slf4j.Slf4j;
import mqtt.mapping.configuration.ConfigurationConnection;
import mqtt.mapping.configuration.ServiceConfiguration;
import mqtt.mapping.core.C8YAgent;
import mqtt.mapping.core.MappingComponent;
import mqtt.mapping.core.Operation;
import mqtt.mapping.core.ServiceOperation;
import mqtt.mapping.core.ServiceStatus;
import mqtt.mapping.model.*;
import mqtt.mapping.model.Mapping;
import mqtt.mapping.processor.model.ProcessingContext;
import mqtt.mapping.service.MQTTClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import javax.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
public class MQTTMappingRestController {

    @Autowired
    MQTTClient mqttClient;

    @Autowired
    C8YAgent c8yAgent;

    @Autowired
    MappingComponent mappingComponent;

    @Autowired
    private RoleService roleService;

    @Autowired
    private MappingComponent mappingStatusComponent;

    @Value("${APP.externalExtensionsEnabled}")
    private boolean externalExtensionsEnabled;

    @Value("${APP.outputMappingEnabled}")
    private boolean outputMappingEnabled;

    @Value("${APP.userRolesEnabled}")
    private Boolean userRolesEnabled;

    @Value("${APP.mqttMappingAdminRole}")
    private String mqttMappingAdminRole;

    @Value("${APP.mqttMappingCreateRole}")
    private String mqttMappingCreateRole;

    @RequestMapping(value = "/feature", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Feature> getFeatures() {
        log.info("Get Feature status");
        Feature feature = new Feature();
        feature.setOutputMappingEnabled(outputMappingEnabled);
        feature.setExternalExtensionsEnabled(externalExtensionsEnabled);
        feature.setUserHasMQTTMappingCreateRole(userHasMQTTMappingCreateRole());
        feature.setUserHasMQTTMappingAdminRole(userHasMQTTMappingAdminRole());
        return new ResponseEntity<Feature>(feature, HttpStatus.OK);
    }

    @RequestMapping(value = "/configuration/connection", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ConfigurationConnection> getConnectionConfiguration() {
        log.info("Get connection details");
        try {
            ConfigurationConnection configuration = c8yAgent
                    .loadConnectionConfiguration();
            if (configuration == null) {
                // throw new ResponseStatusException(HttpStatus.NOT_FOUND, "MQTT connection not
                // available");
                configuration = new ConfigurationConnection();
            }
            // don't modify original copy
            ConfigurationConnection configurationClone = (ConfigurationConnection) configuration.clone();
            configurationClone.setPassword("");
            return new ResponseEntity<ConfigurationConnection>(configurationClone, HttpStatus.OK);
        } catch (Exception ex) {
            log.error("Error on loading configuration {}", ex);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getLocalizedMessage());
        }
    }

    @RequestMapping(value = "/configuration/connection", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<HttpStatus> configureConnectionToBroker(
            @Valid @RequestBody ConfigurationConnection configuration) {

        if (!userHasMQTTMappingAdminRole()) {
            log.error("Insufficient Permission, user does not have required permission to access this API");
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Insufficient Permission, user does not have required permission to access this API");
        }

        // don't modify original copy
        ConfigurationConnection configurationClone = (ConfigurationConnection) configuration.clone();
        configurationClone.setPassword("");
        log.info("Post MQTT broker configuration: {}", configurationClone.toString());
        try {
            c8yAgent.saveConnectionConfiguration(configuration);
            return ResponseEntity.status(HttpStatus.CREATED).build();
        } catch (Exception ex) {
            log.error("Error getting mqtt broker configuration {}", ex);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getLocalizedMessage());
        }
    }

    @RequestMapping(value = "/configuration/service", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ServiceConfiguration> getServiceConfiguration() {
        log.info("Get connection details");

        try {
            final ServiceConfiguration configuration = c8yAgent.loadServiceConfiguration();
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

        if (!userHasMQTTMappingAdminRole()) {
            log.error("Insufficient Permission, user does not have required permission to access this API");
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Insufficient Permission, user does not have required permission to access this API");
        }

        try {
            mqttClient.saveServiceConfiguration(configuration);
            return ResponseEntity.status(HttpStatus.CREATED).build();
        } catch (Exception ex) {
            log.error("Error getting mqtt broker configuration {}", ex);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getLocalizedMessage());
        }
    }

    @RequestMapping(value = "/operation", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<HttpStatus> runOperation(@Valid @RequestBody ServiceOperation operation) {
        log.info("Post operation: {}", operation.toString());
        try {
            if (operation.getOperation().equals(Operation.RELOAD_MAPPINGS)) {
                mappingComponent.rebuildOutboundMappingCache();
                // in order to keep MappingInboundCache and ActiveSubscriptionMappingInbound in
                // sync, the ActiveSubscriptionMappingInbound is build on the
                // reviously used updatedMappings
                List<Mapping> updatedMappings = mappingComponent.rebuildMappingInboundCache();
                mqttClient.updateActiveSubscriptionMappingInbound(updatedMappings, false);
            } else if (operation.getOperation().equals(Operation.CONNECT)) {
                mqttClient.connectToBroker();
            } else if (operation.getOperation().equals(Operation.DISCONNECT)) {
                mqttClient.disconnectFromBroker();
            } else if (operation.getOperation().equals(Operation.REFRESH_STATUS_MAPPING)) {
                mappingComponent.sendStatusMapping();
            } else if (operation.getOperation().equals(Operation.RESET_STATUS_MAPPING)) {
                mappingComponent.resetMappingStatus();
            } else if (operation.getOperation().equals(Operation.RELOAD_EXTENSIONS)) {
                c8yAgent.reloadExtensions();
            } else if (operation.getOperation().equals(Operation.ACTIVATE_MAPPING)) {
                mappingComponent.setActivationMapping(operation.getParameter());
            } else if (operation.getOperation().equals(Operation.REFRESH_NOTFICATIONS_SUBSCRIPTIONS)) {
                c8yAgent.notificationSubscriberReconnect();
            }
            return ResponseEntity.status(HttpStatus.CREATED).build();
        } catch (Exception ex) {
            log.error("Error getting mqtt broker configuration {}", ex);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getLocalizedMessage());
        }
    }

    @RequestMapping(value = "/monitoring/status/service", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ServiceStatus> getServiceStatus() {
        ServiceStatus st = mqttClient.getServiceStatus();
        log.info("Get status: {}", st);
        return new ResponseEntity<>(st, HttpStatus.OK);
    }

    @RequestMapping(value = "/monitoring/status/mapping", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<MappingStatus>> getMappingStatus() {
        List<MappingStatus> ms = mappingStatusComponent.getMappingStatus();
        log.info("Get mapping status: {}", ms);
        return new ResponseEntity<List<MappingStatus>>(ms, HttpStatus.OK);
    }

    @RequestMapping(value = "/monitoring/tree", method = RequestMethod.GET, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TreeNode> getActiveMappingTree() {
        TreeNode result = mqttClient.getActiveMappingTree();
        log.info("Get mapping tree!");
        return ResponseEntity.status(HttpStatus.OK).body(result);
    }

    @RequestMapping(value = "/monitoring/subscription", method = RequestMethod.GET, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Integer>> getActiveSubscriptions() {
        Map<String, Integer> result = mqttClient.getActiveSubscriptions();
        log.info("Get active subscriptions!");
        return ResponseEntity.status(HttpStatus.OK).body(result);
    }

    @RequestMapping(value = "/mapping", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<Mapping>> getMappings() {
        log.info("Get mappings");
        List<Mapping> result = mappingComponent.getMappings();
        return ResponseEntity.status(HttpStatus.OK).body(result);
    }

    @RequestMapping(value = "/mapping/{id}", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Mapping> getMapping(@PathVariable String id) {
        log.info("Get mapping: {}", id);
        Mapping result = mappingComponent.getMapping(id);
        if (result == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(result);
        }
        return ResponseEntity.status(HttpStatus.OK).body(result);
    }

    @RequestMapping(value = "/mapping/{id}", method = RequestMethod.DELETE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> deleteMapping(@PathVariable String id) {
        log.info("Delete mapping: {}", id);
        Mapping mapping = null;

        try {
            mapping = mappingComponent.deleteMapping(id);
            if (mapping == null)
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Mapping with id " + id + " could not be found.");

            Mapping deletedMapping = mappingComponent.deleteFromMappingCache(mapping);

            if (!Direction.OUTBOUND.equals(mapping.direction)) {
                mqttClient.deleteActiveSubscriptionMappingInbound(mapping);
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
            Mapping result = mappingComponent.createMapping(mapping);
            if (Direction.OUTBOUND.equals(mapping.direction)) {
                mappingComponent.rebuildOutboundMappingCache();
            } else {
                mqttClient.upsertActiveSubscriptionMappingInbound(mapping);
                mappingComponent.deleteFromCacheMappingInbound(mapping);
                mappingComponent.addToCacheMappingInbound(mapping);
                mappingComponent.getActiveMappingInbound().put(mapping.id, mapping);
            }
            return ResponseEntity.status(HttpStatus.OK).body(result);
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
            Mapping result = mappingComponent.updateMapping(mapping, false);
            if (Direction.OUTBOUND.equals(mapping.direction)) {
                mappingComponent.rebuildOutboundMappingCache();
            } else {
                mqttClient.upsertActiveSubscriptionMappingInbound(mapping);
                mappingComponent.deleteFromCacheMappingInbound(mapping);
                mappingComponent.addToCacheMappingInbound(mapping);
                mappingComponent.getActiveMappingInbound().put(mapping.id, mapping);
            }
            return ResponseEntity.status(HttpStatus.OK).body(result);
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
        log.info("Test payload: {}, {}, {}", path, method, payload);
        try {
            boolean send = ("send").equals(method);
            List<ProcessingContext<?>> result = mqttClient.test(path, send, payload);
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

        if (!userHasMQTTMappingAdminRole()) {
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

    private boolean userHasMQTTMappingAdminRole() {
        return !userRolesEnabled || (userRolesEnabled && roleService.getUserRoles().contains(mqttMappingCreateRole));
    }

    private boolean userHasMQTTMappingCreateRole() {
        return !userRolesEnabled || userHasMQTTMappingAdminRole()
                || (userRolesEnabled && roleService.getUserRoles().contains(mqttMappingCreateRole));
    }

}