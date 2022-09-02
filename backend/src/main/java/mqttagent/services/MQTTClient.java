package mqttagent.services;

import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;
import org.apache.commons.lang3.StringUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.apache.commons.lang3.mutable.MutableInt;
import org.apache.commons.lang3.mutable.MutableLong;

import lombok.extern.slf4j.Slf4j;
import mqttagent.callbacks.GenericCallback;
import mqttagent.model.MQTTMapping;
import mqttagent.model.MQTTMappingsRepresentation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.joda.time.DateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpServerErrorException;

@Slf4j
@Configuration
@EnableScheduling
@Service
public class MQTTClient {

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

    // mappings: tenant -> ( topic -> mqtt_mappping)
    private Map<String, Map<String, MQTTMapping>> instanceMappings = new HashMap<String, Map<String, MQTTMapping>>();

    private void runInit() {
        while (!isInitilized()) {
            log.info("Try to retrieve MQTT connection configuration now ...");
            subscriptionsService.runForTenant(c8yAgent.tenant, () -> {
                final MQTTConfiguration configuration = configurationService.loadConfiguration();
                mqttConfiguration = configuration;
                log.info("Configuration found: {}", configuration);
                if (configuration != null && configuration.active) {
                    try {
                        String prefix = mqttConfiguration.useTLS ? "ssl://" : "tcp://";
                        String broker = prefix + mqttConfiguration.mqttHost + ":" + mqttConfiguration.mqttPort;
                        mqttClient = new MqttClient(broker, mqttConfiguration.getClientId());
                        setInitilized(true);
                        log.info("Connecting to MQTT Broker {}", broker);
                    } catch (HttpServerErrorException e) {
                        log.error("Failed to authenticate to MQTT broker", e);

                    } catch (MqttException e) {
                        log.error("Failed to connect to MQTT broker", e);
                    }
                }
            });
            try {
                log.info("Try to retrieve MQTT connection configuration in 30s ...");
                Thread.sleep(10000);
            } catch (InterruptedException e) {
                log.error("Error on reconnect: ", e);
            }

        }
    }

    public void init() {
        // test if init task is still running, then we don't need to start another task
        log.info("Called init(): {}", initTask == null ? false : initTask.isDone());
        if ((initTask != null && initTask.isDone()) || initTask == null) {
            initTask = newCachedThreadPool.submit(() -> runInit());
        }
    }

    private void runReconnect() {
        // subscriptionsService.runForTenant(c8yAgent.tenant, () -> {
        log.info("Try to reestablish the MQTT connection now I");
        disconnect();
        while (!isConnected()) {
            log.debug("Try to reestablish the MQTT connection now II");
            // TODO If MQTT Connection is down due to MQTT Broker issues it will not
            // reconnect.
            init();
            try {
                connect();
                // Uncomment this if you want to subscribe on start on "#"
                subscribe("#", 0);
                subscribe("$SYS/#", 0);
            } catch (MqttException e) {
                log.error("Error on reconnect: ", e);
            }

            try {
                Thread.sleep(10000);
            } catch (InterruptedException e) {
                log.error("Error on reconnect: ", e);
            }
        }
        // });

    }

    public void reconnect() {
        // test if reconnect task is still running, then we don't need to start another
        // task
        log.info("Called reconnect(): {}",
                reconnectTask == null ? false : reconnectTask.isDone());
        if ((reconnectTask != null && reconnectTask.isDone()) || reconnectTask == null) {
            reconnectTask = newCachedThreadPool.submit(() -> {
                runReconnect();
            });
        }
    }

    private void connect() throws MqttException {
        if (isConnectionConfigured() && !isConnected()) {
            MqttConnectOptions connOpts = new MqttConnectOptions();
            connOpts.setCleanSession(true);
            connOpts.setAutomaticReconnect(true);
            connOpts.setUserName(mqttConfiguration.getUser());
            connOpts.setPassword(mqttConfiguration.getPassword().toCharArray());
            mqttClient.connect(connOpts);
            log.info("Successfully connected to Broker {}", mqttClient.getServerURI());
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
        log.info("Disconnecting from MQTT Broker: {}, {}", (mqttClient == null ? null : mqttClient.getServerURI()),
                isInitilized());
        try {
            if (isInitilized() && mqttClient != null && mqttClient.isConnected()) {
                log.debug("Disconnected from MQTT Broker I: {}", mqttClient.getServerURI());
                mqttClient.unsubscribe("#");
                mqttClient.unsubscribe("$SYS");
                mqttClient.disconnect();
                log.debug("Disconnected from MQTT Broker II: {}", mqttClient.getServerURI());
            }
        } catch (MqttException e) {
            log.error("Error on disconnecting MQTT Client: ", e);
        }
    }

    public void disconnectFromBroker() {
        mqttConfiguration = configurationService.setConfigurationActive(false);
        disconnect();
    }

    public void connectToBroker() {
        mqttConfiguration = configurationService.setConfigurationActive(true);
        reconnect();
    }

    public MQTTConfiguration getConnectionDetails() {
        return configurationService.loadConfiguration();
    }

    public void saveConfiguration(final MQTTConfiguration configuration) {
        try {
            configurationService.saveConfiguration(configuration);
            // invalidate broker client
            setInitilized(false);
            reconnect();
        } catch (JsonProcessingException e) {
            log.error("Failed to store configuration");
        }
    }

    public boolean isConnectionConfigured() {
        return (mqttConfiguration != null) && !StringUtils.isEmpty(mqttConfiguration.mqttHost) &&
                !(mqttConfiguration.mqttPort == 0) &&
                !StringUtils.isEmpty(mqttConfiguration.user) &&
                !StringUtils.isEmpty(mqttConfiguration.password) &&
                !StringUtils.isEmpty(mqttConfiguration.clientId);
    }

    public boolean isConnectionActicated() {
        return (mqttConfiguration != null) && mqttConfiguration.active;
    }

    private boolean isInitilized() {
        return initilized;
    }

    private void setInitilized(boolean init) {
        initilized = init;
    }

    // TODO change this to respect the tenant name
    public void reloadMappings(String tenant) {
        subscriptionsService.runForTenant(tenant, () -> {
            List<MQTTMapping> mappings = c8yAgent.getMQTTMappings();
            // convert list -> map
            Map<String, MQTTMapping> updatedMappingsMap = new HashMap<String, MQTTMapping>();
            mappings.forEach(m -> {
                updatedMappingsMap.put(m.topic, m);
                log.info("Processing addition for topic: {}", m.topic);
            });
            // process changes
            final Map<String, MQTTMapping> activeMappingsMap = instanceMappings.getOrDefault(tenant,
                    new HashMap<String, MQTTMapping>());

            // unsubscribe not used topics
            activeMappingsMap.forEach((topic, map) -> {
                log.info("Processing unsubscribe for topic: {}", topic);
                boolean unsubscribe = false;
                // topic was deleted -> unsubscribe
                if (updatedMappingsMap.get(topic) == null) {
                    unsubscribe = true;
                    // test if existing mapping was updated
                } else if (updatedMappingsMap.get(topic) != null
                        && updatedMappingsMap.get(topic).lastUpdate != activeMappingsMap.get(topic).lastUpdate) {
                    unsubscribe = true;
                }
                if (unsubscribe) {
                    try {
                        log.debug("Unsubscribe from topic: {} ...", topic);
                        unsubscribe(topic);
                    } catch (MqttException e) {
                        log.error("Could not unsubscribe topic: {}", topic);
                    }
                }
            });

            // subscribe to new topics
            updatedMappingsMap.forEach((topic, map) -> {
                log.info("Processing subscribe for topic: {}", topic);
                boolean subscribe = false;
                // topic was deleted -> unsubscribe
                if (activeMappingsMap.get(topic) == null) {
                    subscribe = true;
                    // test if existing mapping was updated
                } else if (activeMappingsMap.get(topic) != null
                        && activeMappingsMap.get(topic).lastUpdate != updatedMappingsMap.get(topic).lastUpdate) {
                    subscribe = true;
                }
                if (subscribe && map.active && !map.snoopPayload) {
                    try {
                        log.debug("Subscribing to topic: {} ...", topic);
                        subscribe(topic, map);
                    } catch (MqttException | IllegalArgumentException e) {
                        log.error("Could not subscribe topic: {}", topic);
                    }
                }
            });
            // update mappings
            instanceMappings.put(tenant, updatedMappingsMap);
        });
    }

    public List<MQTTMapping> getMappings() {
        List<MQTTMapping> result = new ArrayList<MQTTMapping>();
        // TODO enhance method for multi tenancy
        subscriptionsService.runForTenant(c8yAgent.tenant, () -> {
            result.addAll(c8yAgent.getMQTTMappings());
        });
        return result;
    }

    public void subscribe(String topic, Integer qos) throws MqttException {
        if (isInitilized() && mqttClient != null) {
            log.info("Subscribing on topic {}", topic);
            subscriptionsService.runForTenant(c8yAgent.tenant, () -> {
                c8yAgent.createEvent("Subscribing on topic " + topic, "mqtt_status_event", DateTime.now(), null);
            });
            mqttClient.setCallback(genericCallback);
            if (qos != null)
                mqttClient.subscribe(topic, qos);
            else
                mqttClient.subscribe(topic);
            log.debug("Successfully subscribed on topic {}", topic);
        }
    }

    private void subscribe(String topic, MQTTMapping map) throws MqttException {
        if (isInitilized() && mqttClient != null) {
            log.info("Subscribing on topic {}", topic);
            subscriptionsService.runForTenant(c8yAgent.tenant, () -> {
                c8yAgent.createEvent("Subscribing on topic " + topic, "mqtt_status_event", DateTime.now(), null);
            });
            mqttClient.setCallback(genericCallback);
            if (map != null)
                mqttClient.subscribe(topic, (int) map.qos);
            else
                mqttClient.subscribe(topic);
            log.debug("Successfully subscribed on topic {}", topic);
        }
    }

    private void unsubscribe(String topic) throws MqttException {
        if (isInitilized() && mqttClient != null) {
            log.info("Unsubscribing from topic {}", topic);
            subscriptionsService.runForTenant(c8yAgent.tenant, () -> {
                c8yAgent.createEvent("Unsubscribing on topic " + topic, "mqtt_status_event", DateTime.now(), null);
            });
            mqttClient.unsubscribe(topic);
            log.debug("Successfully unsubscribed from topic {}", topic);
        }
    }

    @Scheduled(fixedRate = 30000)
    public void startReporting() {

        String statusReconnectTask = (reconnectTask == null ? "stopped"
                : reconnectTask.isDone() ? "stopped" : "running");
        String statusInitTask = (initTask == null ? "stopped" : initTask.isDone() ? "stopped" : "running");

        log.info("Status of reconnectTask: {}, initTask {}, isConnected {}", statusReconnectTask,
                statusInitTask, isConnected());

    }

    public Map<String, MQTTMapping> getMappingsPerTenant(String tenant) {
        // private Map<String, Map<String, MQTTMapping>> instanceMappings = new
        // HashMap<String, Map<String, MQTTMapping>>();
        return instanceMappings.get(tenant);
    }

    public Long deleteMapping(String tenant, Long id) {
        subscriptionsService.runForTenant(tenant, () -> {
            List<MQTTMapping> mappings = c8yAgent.getMQTTMappings();
            List<MQTTMapping> updatedMappings = new ArrayList <MQTTMapping>();
            MutableInt i = new MutableInt(0);
            mappings.forEach(m -> {
                if (m.id == id) {
                    log.info("Deleted mapping with id: {}", m.id);
                } else {
                    updatedMappings.add(m);
                }
                i.increment();
            });
            try {
                c8yAgent.saveMQTTMappings(updatedMappings);
            } catch (JsonProcessingException ex) {
                log.error("Cound not process parse mappings as json: {}", ex);
                throw new RuntimeException(ex);
            }
        });
        // update cached mappings
        reloadMappings(tenant);
        return id;
    }

	public Long addMapping(String tenant, MQTTMapping mapping) {
        MutableLong result = null;
        subscriptionsService.runForTenant(tenant, () -> {
            ArrayList<MQTTMapping> mappings = c8yAgent.getMQTTMappings();
            if (MQTTMappingsRepresentation.checkTopicIsUnique(mappings, mapping)) {
                mapping.lastUpdate = System.currentTimeMillis();
                mapping.id = MQTTMappingsRepresentation.nextId(mappings);
                mappings.add(mapping);
                result.setValue(mapping.id);;
            } else {
                throw new RuntimeException("Topic name is not unique!");
            }
            try {
                c8yAgent.saveMQTTMappings(mappings);
            } catch (JsonProcessingException ex) {
                log.error("Cound not process parse mappings as json: {}", ex);
                throw new RuntimeException(ex);
            }
        });
        // update cached mappings
        reloadMappings(tenant);
        return result.getValue();
	}
    
    public Long updateMapping(String tenant, Long id, MQTTMapping mapping) {
        MutableLong result = null;
        subscriptionsService.runForTenant(tenant, () -> {
            ArrayList<MQTTMapping> mappings = c8yAgent.getMQTTMappings();
            if (MQTTMappingsRepresentation.checkTopicIsUnique(mappings, mapping)) {
                MutableInt i = new MutableInt(0);
                mappings.forEach(m -> {
                    if (m.id == id) {
                        log.info("Update mapping with id: {}", m.id);
                        m.copyFrom(mapping);
                        m.lastUpdate = System.currentTimeMillis();
                    }
                    i.increment();
                });
                result.setValue(mapping.id);;
            } else {
                throw new RuntimeException("Topic name is not unique!");
            }
            try {
                c8yAgent.saveMQTTMappings(mappings);
            } catch (JsonProcessingException ex) {
                log.error("Cound not process parse mappings as json: {}", ex);
                throw new RuntimeException(ex);
            }
        });
        // update cached mappings
        reloadMappings(tenant);
        return result.getValue();
    }

    public void runOperation(ServiceOperation operation) {
        if ( operation.getOperation().equals(Operation.RELOAD)){
            reloadMappings(operation.getTenant());
        } else if ( operation.getOperation().equals(Operation.CONNECT)){
            connectToBroker();
        } else if ( operation.getOperation().equals(Operation.DISCONNECT)){
            disconnectFromBroker();
        }
    }

}
