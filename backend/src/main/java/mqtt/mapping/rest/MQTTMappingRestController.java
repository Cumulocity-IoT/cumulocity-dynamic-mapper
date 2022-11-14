package mqtt.mapping.rest;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
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
import mqtt.mapping.model.InnerNode;
import mqtt.mapping.model.Mapping;
import mqtt.mapping.model.MappingStatus;
import mqtt.mapping.model.TreeNode;
import mqtt.mapping.processor.ProcessingContext;
import mqtt.mapping.service.MQTTClient;
import mqtt.mapping.service.ServiceOperation;
import mqtt.mapping.service.ServiceStatus;

@Slf4j
@RestController
public class MQTTMappingRestController {

    private static final String BASE_PACKAGE_NAME_TYPES = "mqtt.mapping.processor";

    @Autowired
    MQTTClient mqttClient;

    @Autowired
    C8YAgent c8yAgent;

    @RequestMapping(value = "/configuration/connection", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ConfigurationConnection> getConnectionConfiguration() {
        log.info("Get connection details");
        try {
            final ConfigurationConnection configuration = mqttClient.loadConnectionConfiguration();
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
            mqttClient.saveConnectionConfiguration(configuration);
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
            final ServiceConfiguration configuration = mqttClient.loadServiceConfiguration();
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
        List<MappingStatus> ms = mqttClient.getMappingStatus();
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
    public ResponseEntity<Mapping> getMapping(@PathVariable Long id) {
        log.info("Get mapping: {}", id);
        Mapping result = c8yAgent.getMapping(id);
        if (result == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(result);
        }
        return ResponseEntity.status(HttpStatus.OK).body(result);
    }

    @RequestMapping(value = "/mapping/{id}", method = RequestMethod.DELETE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Long> deleteMapping(@PathVariable Long id) {
        log.info("Delete mapping: {}", id);
        Long result;
        try {
            result = mqttClient.deleteMapping(id);
            if (result == -1)
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Mapping with id " + id + " could not be found.");
            return ResponseEntity.status(HttpStatus.OK).body(result);
        } catch (JsonProcessingException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getLocalizedMessage());
        }
    }

    @RequestMapping(value = "/mapping", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Long> addMapping(@Valid @RequestBody Mapping mapping) {
        try {
            log.info("Add mapping: {}", mapping);
            Long result = mqttClient.addMapping(mapping);
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
    public ResponseEntity<Long> updateMapping(@PathVariable Long id, @Valid @RequestBody Mapping mapping) {
        try {
            log.info("Update mapping: {}, {}", mapping, id);
            Long result = mqttClient.updateMapping(id, mapping, false);
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

    @RequestMapping(value = "/tree", method = RequestMethod.GET, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TreeNode> getActiveTreeNode() {
        TreeNode result = mqttClient.getMappingTree();
        InnerNode innerNode = null;
        if (result instanceof InnerNode) {
            innerNode = (InnerNode) result;
        }
        log.info("Get mapping tree: {}", result, innerNode);
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

    @RequestMapping(value = "/registry", method = RequestMethod.GET, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String,String>> getProcessorExtensions() {
        Map <String,String> result = c8yAgent.getProcessorExtensions();
        //List<String> result = new ArrayList<String>(Arrays.asList("CustomMeasurement"));
        // String javaFolder = null;
        // try {
        //     javaFolder = MappingType.valueOf(type).packageName;
            
        // } catch (Exception e) {
        //     return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        // }
        // String packageName = BASE_PACKAGE_NAME_TYPES.concat(".").concat(javaFolder);
        // log.info("Finding registered types in: {}", packageName.replaceAll("[.]", "/"));
        // try {
        //     InputStream stream = 
        //     ClassUtils.getDefaultClassLoader()
        //     .getResourceAsStream(packageName.replaceAll("[.]", "/"));
        //     BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
        //     result = reader.lines()
        //             .peek(line->log.info("FOUND:" + line))
        //             .filter(line -> line.endsWith(".class"))
        //             .map(line -> getClass(line, packageName))
        //              .filter(cl -> (com.google.protobuf.GeneratedMessageV3.class.isAssignableFrom(cl)
        //                      | com.google.protobuf.GeneratedMessageLite.class.isAssignableFrom(cl)))
        //             .map(cl -> cl.getCanonicalName())
        //             .collect(Collectors.toList());

        //     log.info("Get all registered protobuf types: {}", result);
        // } catch (Exception ex) {
        //     ex.printStackTrace();
        //     log.error("No types registered for: {}", type);
        //     throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getLocalizedMessage());
        // }
        return ResponseEntity.status(HttpStatus.OK).body(result);
    }

}