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

import java.net.URI;
import java.util.List;
import java.util.Map;

import javax.validation.Valid;

import org.springframework.beans.factory.annotation.Autowired;
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

import com.fasterxml.jackson.core.JsonProcessingException;

import lombok.extern.slf4j.Slf4j;
import mqtt.mapping.configuration.ConfigurationConnection;
import mqtt.mapping.configuration.ServiceConfiguration;
import mqtt.mapping.core.C8YAgent;
import mqtt.mapping.core.MappingComponent;
import mqtt.mapping.core.ServiceOperation;
import mqtt.mapping.core.ServiceStatus;
import mqtt.mapping.model.Extension;
import mqtt.mapping.model.Mapping;
import mqtt.mapping.model.MappingStatus;
import mqtt.mapping.model.TreeNode;
import mqtt.mapping.processor.model.ProcessingContext;
import mqtt.mapping.service.MQTTClient;

@Slf4j
@RestController
public class MQTTMappingRestController {

    @Autowired
    MQTTClient mqttClient;

    @Autowired
    C8YAgent c8yAgent;

    @Autowired
    private MappingComponent mappingStatusComponent;

    @RequestMapping(value = "/configuration/connection", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ConfigurationConnection> getConnectionConfiguration() {
        log.info("Get connection details");
        try {
            final ConfigurationConnection configuration = c8yAgent
                    .loadConnectionConfiguration();
            if (configuration == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "MQTT connection not available");
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
            mqttClient.runOperation(operation);
            return ResponseEntity.status(HttpStatus.CREATED).build();
        } catch (Exception ex) {
            log.error("Error getting mqtt broker configuration {}", ex);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getLocalizedMessage());
        }
    }

    @RequestMapping(value = "/status/service", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ServiceStatus> getServiceStatus() {
        ServiceStatus st = mqttClient.getServiceStatus();
        log.info("Get status: {}", st);
        return new ResponseEntity<>(st, HttpStatus.OK);
    }

    @RequestMapping(value = "/status/mapping", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<MappingStatus>> getMappingStatus() {
        List<MappingStatus> ms = mappingStatusComponent.getMappingStatus();
        log.info("Get mapping status: {}", ms);
        return new ResponseEntity<List<MappingStatus>>(ms, HttpStatus.OK);
    }

    @RequestMapping(value = "/mapping", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<Mapping>> getMappings() {
        log.info("Get mappings");
        List<Mapping> result = c8yAgent.getMappings();
        return ResponseEntity.status(HttpStatus.OK).body(result);
    }

    @RequestMapping(value = "/mapping/{id}", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Mapping> getMapping(@PathVariable String id) {
        log.info("Get mapping: {}", id);
        Mapping result = c8yAgent.getMapping(id);
        if (result == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(result);
        }
        return ResponseEntity.status(HttpStatus.OK).body(result);
    }

    @RequestMapping(value = "/mapping/{id}", method = RequestMethod.DELETE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> deleteMapping(@PathVariable String id) {
        log.info("Delete mapping: {}", id);
        String result = null;

        try {
            result = c8yAgent.deleteMapping(id);
        } catch (Exception ex) {
            log.error("Deleting active mappings is not allowed {}", ex);
            throw new ResponseStatusException(HttpStatus.NOT_ACCEPTABLE, ex.getLocalizedMessage());
        }
        if (result == null)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Mapping with id " + id + " could not be found.");
        return ResponseEntity.status(HttpStatus.OK).body(result);

    }

    @RequestMapping(value = "/mapping", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Mapping> createMapping(@Valid @RequestBody Mapping mapping) {
        try {
            log.info("Add mapping: {}", mapping);
            Mapping result = c8yAgent.createMapping(mapping);
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
            Mapping result = c8yAgent.updateMapping(mapping, id, false);
            return ResponseEntity.status(HttpStatus.OK).body(result);
        } catch (Exception ex) {
            if (ex instanceof IllegalArgumentException) {
                log.error("Updating active mappings is not allowed {}", ex);
                throw new ResponseStatusException(HttpStatus.NOT_ACCEPTABLE, ex.getLocalizedMessage());
            }
            else if (ex instanceof RuntimeException)
                throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getLocalizedMessage());
            else if (ex instanceof JsonProcessingException)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getLocalizedMessage());
            else
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getLocalizedMessage());
        }
    }

    @RequestMapping(value = "/tree", method = RequestMethod.GET, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TreeNode> getActiveTreeNode() {
        TreeNode result = mqttClient.getMappingTree();
        log.info("Get mapping tree!");
        return ResponseEntity.status(HttpStatus.OK).body(result);
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
        Extension result = c8yAgent.deleteProcessorExtension(extensionName);
        if (result == null)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Extension with id " + extensionName + " could not be found.");
        return ResponseEntity.status(HttpStatus.OK).body(result);
    }

}