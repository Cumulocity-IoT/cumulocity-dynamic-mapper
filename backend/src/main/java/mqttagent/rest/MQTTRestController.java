package mqttagent.rest;

import mqttagent.configuration.TO_MQTTConfiguration;
import mqttagent.services.MQTTClient;
import mqttagent.services.ServiceStatus;

import java.util.Optional;

import javax.validation.Valid;

import org.eclipse.paho.client.mqttv3.MqttException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;


@RestController
public class MQTTRestController {

    final Logger logger = LoggerFactory.getLogger(MQTTRestController.class);

    @Autowired
    MQTTClient mqttClient;

    @RequestMapping(value = "/subscribe", method = RequestMethod.POST, consumes = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity subscribe(@RequestBody String topic) {
        logger.info("Subscription Messages received for topic {}", topic);
        try {
            mqttClient.subscribe(topic, null);
            return ResponseEntity.ok().build();
        } catch (MqttException e) {
            logger.error("Error for subscribing on topic {}", topic, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error for subscribing on topic "+topic);
        }
    }

    @RequestMapping(value = "/subscribe", method = RequestMethod.DELETE, consumes = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity unsubscribe(@RequestBody String topic) {
        logger.info("Unsubscription Message received for topic {}", topic);
        try {
            mqttClient.unsubscribe(topic);
            return ResponseEntity.ok().build();
        } catch (MqttException e) {
            logger.error("Error for unsubscribing on topic {}", topic, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error for unsubscribing on topic "+topic);
        }
    }


    @RequestMapping(value = "/connection", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity configureConnectionToMQTT(@Valid @RequestBody TO_MQTTConfiguration configuration) {
        logger.info("Getting mqtt broker configuration: {}", configuration.toString());
        try {
            mqttClient.configureConnection(configuration);
            return ResponseEntity.status(HttpStatus.CREATED).build();
        } catch (Exception ex) {
            logger.error("Error getting mqtt broker configuration {}", ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @RequestMapping(value = "/connection", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TO_MQTTConfiguration> getConnectionDetails() {
        logger.info("get connection details");
        try {
            final Optional<TO_MQTTConfiguration> configurationOptional = mqttClient.getConnectionDetails();
            if (configurationOptional.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            }

            final TO_MQTTConfiguration configuration = configurationOptional.get();
            configuration.setPassword("");

            return new ResponseEntity<>(configuration, HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("Error on loading configuration {}", ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @RequestMapping(value = "/connection", method = RequestMethod.DELETE)
    public ResponseEntity deleteConnectionToMQTT() {
        logger.info("delete connection");
        try {
            mqttClient.clearConnection();
            return ResponseEntity.status(HttpStatus.OK).build();
        } catch (Exception ex) {
            logger.error("Error getting oAuth token {}", ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @RequestMapping(value = "/status", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ServiceStatus> getStatus() {
        logger.info("query status: {}", mqttClient.isValidConfigurationAvailable());
        if (!mqttClient.isValidConfigurationAvailable()) {
            return new ResponseEntity<>(ServiceStatus.notAuthenticated(), HttpStatus.OK);
        }

        return new ResponseEntity<>(ServiceStatus.ready(), HttpStatus.OK);
    }

}
