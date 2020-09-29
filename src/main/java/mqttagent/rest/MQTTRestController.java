package mqttagent.rest;

import mqttagent.services.MQTTClient;
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

}
