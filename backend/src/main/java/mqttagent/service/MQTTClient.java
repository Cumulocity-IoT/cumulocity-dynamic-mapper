package mqttagent.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.mutable.MutableInt;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.joda.time.DateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpServerErrorException;

import com.fasterxml.jackson.core.JsonProcessingException;

import lombok.extern.slf4j.Slf4j;
import mqttagent.callback.GenericCallback;
import mqttagent.configuration.MQTTConfiguration;
import mqttagent.core.C8yAgent;
import mqttagent.model.InnerNode;
import mqttagent.model.Mapping;
import mqttagent.model.MappingsRepresentation;
import mqttagent.model.ResolveException;
import mqttagent.model.TreeNode;
import mqttagent.model.ValidationError;

@Slf4j
@Configuration
@EnableScheduling
@Service
public class MQTTClient {

    MQTTConfiguration mqttConfiguration;

    private MqttClient mqttClient;

    @Autowired
    private C8yAgent c8yAgent;

    @Autowired
    private GenericCallback genericCallback;

    private ExecutorService newCachedThreadPool = Executors.newCachedThreadPool();

    private Future reconnectTask;

    private Future initTask;

    private boolean initilized = false;

    private Set<String> activeSubscriptionsSet = new HashSet<String>();

    // mappings: tenant -> ( topic -> mqtt_mappping)
    private TreeNode mappingTree = InnerNode.initTree();;
    private Set<Mapping> dirtyMappings = new HashSet<Mapping>();

    private void runInit() {
        while (!isInitilized()) {
            log.info("Try to retrieve MQTT connection configuration now ...");
            mqttConfiguration = c8yAgent.loadConfiguration();
            log.info("Configuration found: {}", mqttConfiguration);
            if (mqttConfiguration != null && mqttConfiguration.active) {
                try {
                    String prefix = mqttConfiguration.useTLS ? "ssl://" : "tcp://";
                    String broker = prefix + mqttConfiguration.mqttHost + ":" + mqttConfiguration.mqttPort;
                    mqttClient = new MqttClient(broker, mqttConfiguration.getClientId(), new MemoryPersistence());
                    setInitilized(true);
                    log.info("Connecting to MQTT Broker {}", broker);
                } catch (HttpServerErrorException e) {
                    log.error("Failed to authenticate to MQTT broker", e);

                } catch (MqttException e) {
                    log.error("Failed to connect to MQTT broker", e);
                }
            }

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
            connOpts.setAutomaticReconnect(false);
            connOpts.setUserName(mqttConfiguration.getUser());
            connOpts.setPassword(mqttConfiguration.getPassword().toCharArray());
            mqttClient.connect(connOpts);
            log.info("Successfully connected to Broker {}", mqttClient.getServerURI());
            c8yAgent.createEvent("Successfully connected to Broker " + mqttClient.getServerURI(),
                    "mqtt_status_event",
                    DateTime.now(), null);

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
        mqttConfiguration = c8yAgent.setConfigurationActive(false);
        disconnect();
    }

    public void connectToBroker() {
        mqttConfiguration = c8yAgent.setConfigurationActive(true);
        reconnect();
    }

    public MQTTConfiguration getConnectionDetails() {
        return c8yAgent.loadConfiguration();
    }

    public void saveConfiguration(final MQTTConfiguration configuration) {

        c8yAgent.saveConfiguration(configuration);
        // invalidate broker client
        setInitilized(false);
        reconnect();

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
    public void reloadMappings() {

        List<Mapping> mappings = c8yAgent.getMappings();
        // convert list -> map, set
        Set<String> updatedSubscriptionsSet = new HashSet<String>();
        Map<String, Mapping> updatedSubscriptionsMap = new HashMap<String, Mapping>();
        mappings.forEach(m -> {
            if (m.active) {
                updatedSubscriptionsSet.add(m.topic);
                updatedSubscriptionsMap.put(m.topic, m);
            }
            log.info("Processing addition for topic: {}", m.topic);
        });

        // unsubscribe not used topics
        activeSubscriptionsSet.forEach((topic) -> {
            log.info("Processing unsubscribe for topic: {}", topic);
            // topic was deleted -> unsubscribe
            if (!updatedSubscriptionsSet.contains(topic)) {
                try {
                    log.debug("Unsubscribe from topic: {} ...", topic);
                    unsubscribe(topic);
                } catch (MqttException e) {
                    log.error("Could not unsubscribe topic: {}", topic);
                }
            }
        });

        // subscribe to new topics
        updatedSubscriptionsSet.forEach((topic) -> {
            log.info("Processing subscribe for topic: {}", topic);
            // topic was deleted -> unsubscribe
            if (!activeSubscriptionsSet.contains(topic)) {
                try {
                    log.debug("Subscribing to topic: {} ...", topic);
                    subscribe(topic, (int) updatedSubscriptionsMap.get(topic).qos);
                } catch (MqttException | IllegalArgumentException e) {
                    log.error("Could not subscribe topic: {}", topic);
                }
            }
        });
        // update mappings tree
        mappingTree = rebuildMappingTree(mappings);
    }

    private TreeNode rebuildMappingTree(List<Mapping> mappings) {
        InnerNode in = InnerNode.initTree();
        mappings.forEach(m -> {
            try {
                in.insertMapping(m);
            } catch (ResolveException e) {
                log.error("Could not insert mapping {}, ignoring mapping", m);
            }
        });
        return in;
    }

    public void subscribe(String topic, Integer qos) throws MqttException {
        if (isInitilized() && mqttClient != null) {
            log.info("Subscribing on topic {}", topic);
            c8yAgent.createEvent("Subscribing on topic " + topic, "mqtt_status_event", DateTime.now(), null);

            mqttClient.setCallback(genericCallback);
            if (qos != null)
                mqttClient.subscribe(topic, qos);
            else
                mqttClient.subscribe(topic);
            log.debug("Successfully subscribed on topic {}", topic);
        }
    }

    private void unsubscribe(String topic) throws MqttException {
        if (isInitilized() && mqttClient != null) {
            log.info("Unsubscribing from topic {}", topic);
            c8yAgent.createEvent("Unsubscribing on topic " + topic, "mqtt_status_event", DateTime.now(), null);

            mqttClient.unsubscribe(topic);
            log.debug("Successfully unsubscribed from topic {}", topic);
        }
    }

    @Scheduled(fixedRate = 30000)
    public void runHouskeeping() {
        try {
            String statusReconnectTask = (reconnectTask == null ? "stopped"
                        : reconnectTask.isDone() ? "stopped" : "running");
            String statusInitTask = (initTask == null ? "stopped" : initTask.isDone() ? "stopped" : "running");
            log.info("Status: reconnectTask {}, initTask {}, isConnected {}", statusReconnectTask,
                    statusInitTask, isConnected());
            cleanDirtyMappings();
        } catch (Exception ex) {
            log.error("Error during house keeping execution: {}", ex);
        }
    }

    private void cleanDirtyMappings() throws JsonProcessingException {
        // test if for this tenant dirty mappings exist
        log.debug("Testing for dirty maps");
        for (Mapping mqttMapping : dirtyMappings) {
            log.info("Found mapping to be saved: {}, {}", mqttMapping.id, mqttMapping.snoopTemplates);
            updateMapping(mqttMapping.id, mqttMapping);
        }
        // reset dirtySet
        dirtyMappings = new HashSet<Mapping>();
    }

    public TreeNode getActiveMappings() {
        return mappingTree;
    }

    public void setMappingDirty(Mapping mapping) {
        log.info("Setting dirty: {}", mapping);
        dirtyMappings.add(mapping);
    }

    public Long deleteMapping(Long id) {

        List<Mapping> mappings = c8yAgent.getMappings();
        List<Mapping> updatedMappings = new ArrayList<Mapping>();
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
            c8yAgent.saveMappings(updatedMappings);
        } catch (JsonProcessingException ex) {
            log.error("Cound not process parse mappings as json: {}", ex);
            throw new RuntimeException(ex);
        }

        // update cached mappings
        reloadMappings();
        return id;
    }

    public Long addMapping(Mapping mapping) throws JsonProcessingException {
        Long result = null;

        ArrayList<Mapping> mappings = c8yAgent.getMappings();
        ArrayList<ValidationError> errors= MappingsRepresentation.isMappingValid(mappings, mapping);

        if (errors.size() == 0) {
            mapping.lastUpdate = System.currentTimeMillis();
            mapping.id = MappingsRepresentation.nextId(mappings);
            mappings.add(mapping);
            result = mapping.id;
        } else {
            String errorList = errors.stream().map(e -> e.toString()).reduce("", (res, error) -> res + "[ " + error + " ]");
            throw new RuntimeException("Validation errors:" + errorList);
        }
        try {
            c8yAgent.saveMappings(mappings);
        } catch (JsonProcessingException ex) {
            log.error("Cound not process parse mappings as json: {}", ex);
            throw ex;
        }

        // update cached mappings
        reloadMappings();
        return result;
    }

    public Long updateMapping(Long id, Mapping mapping) throws JsonProcessingException {
        Long result = null;
        ArrayList<Mapping> mappings = c8yAgent.getMappings();
        ArrayList<ValidationError> errors= MappingsRepresentation.isMappingValid(mappings, mapping);

        if (errors.size() == 0) {
            MutableInt i = new MutableInt(0);
            mappings.forEach(m -> {
                if (m.id == id) {
                    log.info("Update mapping with id: {}", m.id);
                    m.copyFrom(mapping);
                    m.lastUpdate = System.currentTimeMillis();
                }
                i.increment();
            });
            result = mapping.id;
        } else {
            String errorList = errors.stream().map(e -> e.toString()).reduce("", (res, error) -> res + ", " + error);
            throw new RuntimeException("Validation errors:" + errorList);
        }
        try {
            c8yAgent.saveMappings(mappings);
        } catch (JsonProcessingException ex) {
            log.error("Cound not process parse mappings as json: {}", ex);
            throw ex;
        }

        // update cached mappings
        reloadMappings();
        return result;
    }

    public void runOperation(ServiceOperation operation) {
        if (operation.getOperation().equals(Operation.RELOAD)) {
            reloadMappings();
        } else if (operation.getOperation().equals(Operation.CONNECT)) {
            connectToBroker();
        } else if (operation.getOperation().equals(Operation.DISCONNECT)) {
            disconnectFromBroker();
        }
    }

}
