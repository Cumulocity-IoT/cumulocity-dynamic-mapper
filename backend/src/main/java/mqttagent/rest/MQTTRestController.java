package mqttagent.rest;

import mqttagent.model.MQTTMapping;
import mqttagent.services.MQTTClient;
import mqttagent.services.MQTTConfiguration;
import mqttagent.services.ServiceOperation;
import mqttagent.services.ServiceStatus;

import java.util.List;

import javax.validation.Valid;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
public class MQTTRestController {

    @Autowired
    MQTTClient mqttClient;

    @RequestMapping(value = "/connection", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity configureConnectionToBroker(@Valid @RequestBody MQTTConfiguration configuration) {
        log.info("Getting mqtt broker configuration: {}", configuration.toString());
        try {
            mqttClient.saveConfiguration(configuration);
            return ResponseEntity.status(HttpStatus.CREATED).build();
        } catch (Exception ex) {
            log.error("Error getting mqtt broker configuration {}", ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @RequestMapping(value = "/operation", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity runOperation(@Valid @RequestBody ServiceOperation operation) {
        log.info("Getting operation: {}", operation.toString());
        try {
            mqttClient.runOperation(operation);
            return ResponseEntity.status(HttpStatus.CREATED).build();
        } catch (Exception ex) {
            log.error("Error getting mqtt broker configuration {}", ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @RequestMapping(value = "/connection", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<MQTTConfiguration> getConnectionDetails() {
        log.info("get connection details");
        try {
            final MQTTConfiguration configuration = mqttClient.getConnectionDetails();
            if (configuration == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            }
            // don't modify original copy
            final MQTTConfiguration configuration_clone = (MQTTConfiguration) configuration.clone();
            configuration_clone.setPassword("");

            return new ResponseEntity<>(configuration_clone, HttpStatus.OK);
        } catch (Exception ex) {
            log.error("Error on loading configuration {}", ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @RequestMapping(value = "/status", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ServiceStatus> getStatus() {
        log.info("query status: {}", mqttClient.isConnectionConfigured());
         
        if (mqttClient.isConnected()) {
            return new ResponseEntity<>(ServiceStatus.connected(), HttpStatus.OK);
        } else if (mqttClient.isConnectionActicated()) {
            return new ResponseEntity<>(ServiceStatus.activated(), HttpStatus.OK);
        } else if (mqttClient.isConnectionConfigured()) {
            return new ResponseEntity<>(ServiceStatus.configured(), HttpStatus.OK);
        }
        return new ResponseEntity<>(ServiceStatus.notReady(), HttpStatus.OK);
    }

    @RequestMapping(value = "/mapping", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<MQTTMapping>> getMappings() {
        log.info("Get mappings");
        List<MQTTMapping> result = mqttClient.getMappings();
        return ResponseEntity.status(HttpStatus.OK).body(result);
    }

    @RequestMapping(value = "/mapping/{tenant}/{id}", method = RequestMethod.DELETE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Long> deleteMapping (@PathVariable String tenant, @PathVariable Long id) {
        log.info("Delete mapping {} from tenant {} ", id, tenant);
        Long result = mqttClient.deleteMapping(tenant, id);
        return ResponseEntity.status(HttpStatus.OK).body(result);
    }

    @RequestMapping(value = "/mapping/{tenant}", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Long> addMapping (@PathVariable String tenant, @Valid @RequestBody MQTTMapping mapping) {
        log.info("Add mapping {} for tenant {} ", mapping, tenant);
        Long result = mqttClient.addMapping(tenant, mapping);
        return ResponseEntity.status(HttpStatus.OK).body(result);
    }

    @RequestMapping(value = "/mapping/{tenant}/{id}", method = RequestMethod.PUT, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Long> updateMapping (@PathVariable String tenant, @PathVariable Long id, @Valid @RequestBody MQTTMapping mapping) {
        log.info("Update mapping {}, {} for tenant {} ", mapping, id, tenant);
        Long result = mqttClient.updateMapping(tenant, id, mapping);
        return ResponseEntity.status(HttpStatus.OK).body(result);
    }
}
