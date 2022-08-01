package mqttagent.services;

import com.cumulocity.microservice.subscription.model.MicroserviceSubscriptionAddedEvent;
import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.mutable.MutableBoolean;
import com.fasterxml.jackson.core.JsonProcessingException;

import mqttagent.callbacks.GenericCallback;
import mqttagent.configuration.MQTTConfiguration;

import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import javax.sound.midi.Receiver;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpServerErrorException;

@Configuration
@EnableScheduling
@Service
public class MQTTClient {

    private final Logger logger = LoggerFactory.getLogger(MQTTClient.class);

    MQTTConfiguration mqttConfiguration;

    @Autowired
    private CredentialsConfigurationService configurationService;

    private MqttClient mqttClient;

    @Autowired
    private C8yAgent c8yAgent;

    @Autowired
    private MicroserviceSubscriptionsService subscriptionsService;

    @Autowired
    private GenericCallback genericCallback;

    private ExecutorService newCachedThreadPool = Executors.newCachedThreadPool();

    private Future reconnectTask;

    private Future initTask;

    private boolean initilized = false;

    private void runInit() {
        while (!isInitilized()) {
            logger.info("Try to retrieve MQTT connection configuration now ...");
            subscriptionsService.runForTenant(c8yAgent.tenant, () -> {
                final Optional<MQTTConfiguration> optionalConfiguration = configurationService.loadConfiguration();
                if (optionalConfiguration.isEmpty()) {
                    logger.info("No configuration found");
                } else {
                    try {
                        mqttConfiguration = optionalConfiguration.get();
                        String prefix = mqttConfiguration.useTLS ? "ssl://" : "tcp://";
                        String broker = prefix + mqttConfiguration.mqttHost + ":" + mqttConfiguration.mqttPort;
                        mqttClient = new MqttClient(broker, mqttConfiguration.getClientId());
                        setInitilized(true);
                        logger.info("Connecting to MQTT Broker {}", broker);
                    } catch (HttpServerErrorException e) {
                        logger.error("Failed to authenticate to MQTT broker", e);

                    } catch (MqttException e) {
                        logger.error("Failed to connect to MQTT broker", e);
                    }
                }
            });
            if (!isInitilized()) {
                try {
                    logger.info("Try to retrieve MQTT connection configuration in 30s ...");
                    Thread.sleep(10000);
                } catch (InterruptedException e) {
                    logger.error("Error on reconnect: ", e);
                }

            }
        }
    }

    public void init() {
        // test if init task is still running, then we don't need to start another task
        logger.info("Called init(): {}", initTask == null ? false : initTask.isDone());
        if ((initTask != null && initTask.isDone()) || initTask == null) {
            initTask = newCachedThreadPool.submit(() -> runInit());
        }
    }

    private void runReconnect() {
        // subscriptionsService.runForTenant(c8yAgent.tenant, () -> {
        logger.info("Try to reestablish the MQTT connection now I");
        disconnect();
        while (!isConnected()) {
            logger.debug("Try to reestablish the MQTT connection now II");
            // TODO If MQTT Connection is down due to MQTT Broker issues it will not
            // reconnect.
            init();
            try {
                connect();
                // Uncomment this if you want to subscribe on start on "#"
                subscribe("#", null);
                subscribe("$SYS/#", null);
            } catch (MqttException e) {
                logger.error("Error on reconnect: ", e);
            }

            try {
                Thread.sleep(10000);
            } catch (InterruptedException e) {
                logger.error("Error on reconnect: ", e);
            }
        }
        // });

    }

    public void reconnect() {
        // test if reconnect task is still running, then we don't need to start another
        // task
        logger.info("Called reconnect(): {}, {}", reconnectTask,
                reconnectTask == null ? false : reconnectTask.isDone());
        if ((reconnectTask != null && reconnectTask.isDone()) || reconnectTask == null) {
            reconnectTask = newCachedThreadPool.submit(() -> {
                runReconnect();
            });
        }
    }

    @Scheduled(fixedRate = 30000)
    public void startReporting() {

        String statusReconnectTask = (reconnectTask == null ? "stopped"
                : reconnectTask.isDone() ? "stopped" : "running");
        String statusInitTask = (initTask == null ? "stopped" : initTask.isDone() ? "stopped" : "running");

        logger.info("Status of reconnectTask: {}, initTask {}, isConnected {}", statusReconnectTask,
                statusInitTask, isConnected());

    }

    private void connect() throws MqttException {
        if (isValidConfigurationAvailable()) {
            MqttConnectOptions connOpts = new MqttConnectOptions();
            connOpts.setCleanSession(true);
            connOpts.setAutomaticReconnect(true);
            connOpts.setUserName(mqttConfiguration.getUser());
            connOpts.setPassword(mqttConfiguration.getPassword().toCharArray());
            mqttClient.connect(connOpts);
            logger.info("Successfully connected to Broker {}", mqttClient.getServerURI());
            subscriptionsService.runForTenant(c8yAgent.tenant, () -> {
                c8yAgent.createEvent("Successfully connected to Broker " + mqttClient.getServerURI(),
                        "mqtt_status_event",
                        DateTime.now(), null);
            });
        }
    }

    public boolean isConnected() {
        return mqttClient != null ? mqttClient.isConnected() : false;
    }

    public void disconnect() {
        logger.info("Disconnecting from MQTT Broker: {}, {}", mqttClient, isInitilized());
        try {
            if (isInitilized() && mqttClient != null && mqttClient.isConnected()) {
                logger.debug("Disconnected from MQTT Broker I: {}", mqttClient.getServerURI());
                mqttClient.unsubscribe("#");
                mqttClient.unsubscribe("$SYS");
                mqttClient.disconnect();
                logger.debug("Disconnected from MQTT Broker II: {}", mqttClient.getServerURI());
            }
        } catch (MqttException e) {
            logger.error("Error on disconnecting MQTT Client: ", e);
        }
    }

    public void subscribe(String topic, Integer qos) throws MqttException {
        if (isInitilized() && mqttClient != null) {
            logger.info("Subscribing on topic {}", topic);
            subscriptionsService.runForTenant(c8yAgent.tenant, () -> {
                c8yAgent.createEvent("Subscribing on topic " + topic, "mqtt_status_event", DateTime.now(), null);
            });
            mqttClient.setCallback(genericCallback);
            if (mqttClient.isConnected())
                if (qos != null)
                    mqttClient.subscribe(topic, qos);
                else
                    mqttClient.subscribe(topic);
            else {
                connect();
                if (qos != null)
                    mqttClient.subscribe(topic, qos);
                else
                    mqttClient.subscribe(topic);
            }
            logger.info("Successfully subscribed on topic {}", topic);
        }
    }

    public void unsubscribe(String topic) throws MqttException {
        if (isInitilized() && mqttClient != null) {
            logger.info("Unsubscribing on topic {}", topic);
            subscriptionsService.runForTenant(c8yAgent.tenant, () -> {
                c8yAgent.createEvent("Unsubscribing on topic " + topic, "mqtt_status_event", DateTime.now(), null);
            });
            mqttClient.unsubscribe(topic);
            logger.info("Successfully unsubscribed on topic {}", topic);
        }
    }

    public void clearConnection() {
        configurationService.deleteConfiguration();
    }

    public Optional<MQTTConfiguration> getConnectionDetails() {
        return configurationService.loadConfiguration();
    }

    public void configureConnection(final MQTTConfiguration configuration) {
        try {
            configurationService.saveConfiguration(configuration);
            // reconnect after configuration changed
            // TODO We should actually do the initial connect() here and not the
            // reconnect(). In the reconnect we can then enable the retries again.
            reconnect();
        } catch (JsonProcessingException e) {
            logger.error("Failed to store configuration");
        }
    }

    public boolean isValidConfigurationAvailable() {
        return (mqttConfiguration != null) && !StringUtils.isEmpty(mqttConfiguration.mqttHost) &&
                !(mqttConfiguration.mqttPort == 0) &&
                !StringUtils.isEmpty(mqttConfiguration.user) &&
                !StringUtils.isEmpty(mqttConfiguration.password) &&
                !StringUtils.isEmpty(mqttConfiguration.clientId);
    }

    private boolean isInitilized() {
        return initilized;
    }

    private void setInitilized(boolean init) {
        initilized = init;
    }

}
