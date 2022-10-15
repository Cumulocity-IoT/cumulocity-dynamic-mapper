package mqtt.mapping.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.joda.time.DateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import mqtt.mapping.callback.GenericCallback;
import mqtt.mapping.configuration.MQTTConfiguration;
import mqtt.mapping.core.C8yAgent;
import mqtt.mapping.model.InnerNode;
import mqtt.mapping.model.Mapping;
import mqtt.mapping.model.MappingStatus;
import mqtt.mapping.model.MappingsRepresentation;
import mqtt.mapping.model.ResolveException;
import mqtt.mapping.model.TreeNode;
import mqtt.mapping.model.ValidationError;

@Slf4j
@Configuration
@EnableScheduling
@Service
public class MQTTClient {

    private static final int WAIT_PERIOD_MS = 10000;
    public static final Long KEY_MONITORING_UNSPECIFIED = -1L;
    private static final String STATUS_MQTT_EVENT_TYPE = "mqtt_status_event";
    private static final String STATUS_MAPPING_EVENT_TYPE = "mqtt_mapping_event";
    private static final String STATUS_SERVICE_EVENT_TYPE = "mqtt_service_event";

    private MQTTConfiguration mqttConfiguration;
    private MqttClient mqttClient;

    @Autowired
    private C8yAgent c8yAgent;

    @Autowired
    private GenericCallback genericCallback;

    @Autowired
    @Qualifier("cachedThreadPool")
    private ExecutorService cachedThreadPool;

    private Future<Boolean> connectTask;
    private Future<Boolean> initTask;

    private Set<String> activeSubscriptionTopic = new HashSet<String>();
    private List<Mapping> activeMapping = new ArrayList<Mapping>();

    private TreeNode mappingTree = InnerNode.initTree();;
    private Set<Mapping> dirtyMappings = new HashSet<Mapping>();

    private Map<Long, MappingStatus> monitoring = new HashMap<Long, MappingStatus>();

    public void submitInitialize() {
        // test if init task is still running, then we don't need to start another task
        log.info("Called initialize(): {}", initTask == null || initTask.isDone());
        if ((initTask == null || initTask.isDone())) {
            initTask = cachedThreadPool.submit(() -> initialize());
        }
    }

    private boolean initialize() {
        var firstRun = true;
        while (!MQTTConfiguration.isValid(mqttConfiguration) || !MQTTConfiguration.isActive(mqttConfiguration)) {
            if (!firstRun) {
                try {
                    log.info("Try to retrieve MQTT configuration in {}s, connectctionActive: {} ...",
                            WAIT_PERIOD_MS / 1000,
                            mqttConfiguration.active);
                    Thread.sleep(WAIT_PERIOD_MS);
                } catch (InterruptedException e) {
                    log.error("Error initializing MQTT client: ", e);
                }
            }
            // always keep monitoring entry for monitoring that can't be related to any
            // mapping, so they are monitored as well
            if (!monitoring.containsKey(KEY_MONITORING_UNSPECIFIED)) {
                log.info("Adding: {}", KEY_MONITORING_UNSPECIFIED);
                monitoring.put(KEY_MONITORING_UNSPECIFIED,
                        new MappingStatus(KEY_MONITORING_UNSPECIFIED, "#", 0, 0, 0, 0));
            }
            mqttConfiguration = c8yAgent.loadConfiguration();
            firstRun = false;
        }
        return true;
    }

    public void submitConnect() {
        // test if connect task is still running, then we don't need to start another
        // task
        log.info("Called connect(): connectTask.isDone() {}",
                connectTask == null || connectTask.isDone());
        if (connectTask == null || connectTask.isDone()) {
            connectTask = cachedThreadPool.submit(() -> connect());
        }
    }

    private boolean connect() {
        log.info("Try to establish the MQTT connection now (phase I)");
        if (isConnected()) {
            disconnect();
        }
        var firstRun = true;
        while (!isConnected()) {
            log.info("Try to establish the MQTT connection now (phase II)");
            if (!firstRun) {
                try {
                    Thread.sleep(WAIT_PERIOD_MS);
                } catch (InterruptedException e) {
                    log.error("Error on reconnect: ", e);
                }
            }
            try {
                if (isConnectionConfigured()) {
                    String prefix = mqttConfiguration.useTLS ? "ssl://" : "tcp://";
                    String broker = prefix + mqttConfiguration.mqttHost + ":" + mqttConfiguration.mqttPort;
                    mqttClient = new MqttClient(broker, mqttConfiguration.getClientId() + "_d1",
                            new MemoryPersistence());
                    mqttClient.setCallback(genericCallback);
                    MqttConnectOptions connOpts = new MqttConnectOptions();
                    connOpts.setCleanSession(true);
                    connOpts.setAutomaticReconnect(false);
                    connOpts.setUserName(mqttConfiguration.getUser());
                    connOpts.setPassword(mqttConfiguration.getPassword().toCharArray());
                    mqttClient.connect(connOpts);
                    log.info("Successfully connected to broker {}", mqttClient.getServerURI());
                    c8yAgent.createEvent("Successfully connected to broker " + mqttClient.getServerURI(),
                            STATUS_MQTT_EVENT_TYPE,
                            DateTime.now(), null);
                }
            } catch (MqttException e) {
                log.error("Error on reconnect: ", e);
            }
            firstRun = false;
        }

        try {
            Thread.sleep(WAIT_PERIOD_MS / 30);
        } catch (InterruptedException e) {
            log.error("Error on reconnect: ", e);
        }

        try {
            subscribe("$SYS/#", 0);
            activeSubscriptionTopic = new HashSet<String>();
            activeMapping = new ArrayList<Mapping>();
            reloadMappings();
        } catch (MqttException e) {
            log.error("Error on reconnect: ", e);
            return false;
        }
        return true;
    }

    public boolean isConnected() {
        return mqttClient != null ? mqttClient.isConnected() : false;
    }

    public void disconnect() {
        log.info("Disconnecting from MQTT broker: {}",
                (mqttClient == null ? null : mqttClient.getServerURI()));
        try {
            if (mqttClient.isConnected()) {
                log.debug("Disconnected from MQTT broker I: {}", mqttClient.getServerURI());
                activeSubscriptionTopic.forEach(topic -> {
                    try {
                        mqttClient.unsubscribe(topic);
                    } catch (MqttException e) {
                        log.error("Exception when unsubsribing from topic {}, {}", topic, e);
                    }
                });
                mqttClient.unsubscribe("$SYS");
                mqttClient.disconnect();
                log.debug("Disconnected from MQTT broker II: {}", mqttClient.getServerURI());
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
        submitConnect();
    }

    public MQTTConfiguration getConnectionDetails() {
        return c8yAgent.loadConfiguration();
    }

    public void saveConfiguration(final MQTTConfiguration configuration) {
        c8yAgent.saveConfiguration(configuration);
        disconnect();
        // invalidate broker client
        mqttConfiguration = null;
        submitInitialize();
        submitConnect();
    }

    public boolean isConnectionConfigured() {
        return MQTTConfiguration.isValid(mqttConfiguration);
    }

    public boolean isConnectionActicated() {
        return (mqttConfiguration != null) && mqttConfiguration.active;
    }

    public void reloadMappings() {
        List<Mapping> updatedMapping = c8yAgent.getMappings();
        Set<Long> updatedMappingSet = updatedMapping.stream().map(m -> m.id).collect(Collectors.toSet());
        Set<Long> activeMappingSet = activeMapping.stream().map(m -> m.id).collect(Collectors.toSet());
        Set<String> updatedSubscriptionTopic = updatedMapping.stream().map(m -> m.subscriptionTopic)
                .collect(Collectors.toSet());
        Set<String> unsubscribeTopic = activeSubscriptionTopic.stream()
                .filter(e -> !updatedSubscriptionTopic.contains(e)).collect(Collectors.toSet());
        Set<String> subscribeTopic = updatedSubscriptionTopic.stream()
                .filter(e -> !activeSubscriptionTopic.contains(e)).collect(Collectors.toSet());
        Set<Long> removedMapping = activeMappingSet.stream().filter(m -> !updatedMappingSet.contains(m))
                .collect(Collectors.toSet());

        // remove monitorings for deleted maps
        removedMapping.forEach(id -> {
            log.info("Removing monitoring not used: {}", id);
            monitoring.remove(id);
        });

        // unsubscribe topics not used
        unsubscribeTopic.forEach((topic) -> {
            log.info("Unsubscribe from topic: {}", topic);
            try {
                unsubscribe(topic);
            } catch (MqttException e1) {
                log.error("Exception when unsubsribing from topic {}, {}", topic, e1);
            }
        });

        // subscribe to new topics
        subscribeTopic.forEach(topic -> {
            int qos = updatedMapping.stream().filter(m -> m.subscriptionTopic.equals(topic))
                    .map(m -> m.qos.ordinal()).reduce(Integer::max).orElse(0);
            log.info("Processing subscribe to new topic: {}, qos: {}", topic, qos);
            try {
                subscribe(topic, qos);
            } catch (MqttException e1) {
                log.error("Exception when subsribing to topic {}, {}", topic, e1);
            }
        });
        activeSubscriptionTopic = updatedSubscriptionTopic;
        activeMapping = updatedMapping;
        // update mappings tree
        mappingTree = rebuildMappingTree(updatedMapping);
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

        log.debug("Subscribing on topic {}", topic);
        c8yAgent.createEvent("Subscribing on topic " + topic, STATUS_MQTT_EVENT_TYPE, DateTime.now(), null);
        if (qos != null)
            mqttClient.subscribe(topic, qos);
        else
            mqttClient.subscribe(topic);
        log.debug("Successfully subscribed on topic {}", topic);

    }

    private void unsubscribe(String topic) throws MqttException {
        log.info("Unsubscribing from topic {}", topic);
        c8yAgent.createEvent("Unsubscribing on topic " + topic, STATUS_MQTT_EVENT_TYPE, DateTime.now(), null);
        mqttClient.unsubscribe(topic);
    }

    @Scheduled(fixedRate = 30000)
    public void runHouskeeping() {
        try {
            String statusReconnectTask = (connectTask == null ? "stopped"
                    : connectTask.isDone() ? "stopped" : "running");
            String statusInitTask = (initTask == null ? "stopped" : initTask.isDone() ? "stopped" : "running");
            log.info("Status: reconnectTask {}, initTask {}, isConnected {}", statusReconnectTask,
                    statusInitTask, isConnected());
            cleanDirtyMappings();
            sendStatusMonitoring();
            sendStatusConfiguration();
        } catch (Exception ex) {
            log.error("Error during house keeping execution: {}", ex);
        }
    }

    private void sendStatusMonitoring() {
        c8yAgent.sendStatusMonitoring(STATUS_MAPPING_EVENT_TYPE, monitoring);
    }

    private void sendStatusConfiguration() {
        ServiceStatus serviceStatus;
        if (isConnected()) {
            serviceStatus = ServiceStatus.connected();
        } else if (isConnectionActicated()) {
            serviceStatus = ServiceStatus.activated();
        } else if (isConnectionConfigured()) {
            serviceStatus = ServiceStatus.configured();
        } else {
            serviceStatus = ServiceStatus.notReady();
        }
        c8yAgent.sendStatusConfiguration(STATUS_SERVICE_EVENT_TYPE, serviceStatus);
    }

    private void cleanDirtyMappings() throws JsonProcessingException {
        // test if for this tenant dirty mappings exist
        log.debug("Testing for dirty maps");
        for (Mapping mqttMapping : dirtyMappings) {
            log.info("Found mapping to be saved: {}, {}", mqttMapping.id, mqttMapping.snoopTemplates);
            // no reload required
            updateMapping(mqttMapping.id, mqttMapping, false);
        }
        // reset dirtySet
        dirtyMappings = new HashSet<Mapping>();
    }

    public TreeNode getMappingTree() {
        return mappingTree;
    }

    public void setMappingDirty(Mapping mapping) {
        log.info("Setting dirty: {}", mapping);
        dirtyMappings.add(mapping);
    }

    public MappingStatus getMappingStatus(Mapping m, boolean unspecified){
        long key = -1;
        String t = "#";
        if ( !unspecified) {
            key = m.id;
            t = m.subscriptionTopic;
        }
        MappingStatus ms = monitoring.get(key);
        if (ms == null) {
            log.info("Adding: {}", key);
            ms = new MappingStatus(key, t, 0, 0, 0, 0);
            monitoring.put(key, ms);
        }
        return ms;
    }

    public Long deleteMapping(Long id) throws JsonProcessingException {
        List<Mapping> mappings = c8yAgent.getMappings();
        Predicate<Mapping> isMapping = m -> m.id == id;
        boolean removed = mappings.removeIf(isMapping);
        if (removed) {
            c8yAgent.saveMappings(mappings);
            reloadMappings();
        }
        return (removed ? id : -1);
    }

    public Long addMapping(Mapping mapping) throws JsonProcessingException {
        Long result = null;
        ArrayList<Mapping> mappings = c8yAgent.getMappings();
        ArrayList<ValidationError> errors = MappingsRepresentation.isMappingValid(mappings, mapping);

        if (errors.size() == 0) {
            mapping.lastUpdate = System.currentTimeMillis();
            mapping.id = MappingsRepresentation.nextId(mappings);
            mappings.add(mapping);
            result = mapping.id;
        } else {
            String errorList = errors.stream().map(e -> e.toString()).reduce("",
                    (res, error) -> res + "[ " + error + " ]");
            throw new RuntimeException("Validation errors:" + errorList);
        }

        c8yAgent.saveMappings(mappings);
        reloadMappings();
        return result;
    }

    public Long updateMapping(Long id, Mapping mapping, boolean reload) throws JsonProcessingException {
        int upateMapping = -1;
        ArrayList<Mapping> mappings = c8yAgent.getMappings();
        ArrayList<ValidationError> errors = MappingsRepresentation.isMappingValid(mappings, mapping);
        if (errors.size() == 0) {
            upateMapping = IntStream.range(0, mappings.size())
                    .filter(i -> id.equals(mappings.get(i))).findFirst().orElse(-1);
            if (upateMapping != -1) {
                log.info("Update mapping with id: {}", mappings.get(upateMapping).id);
                mapping.lastUpdate = System.currentTimeMillis();
                mappings.set(upateMapping, mapping);
                c8yAgent.saveMappings(mappings);
                if (reload) {
                    reloadMappings();
                }
            }
        } else {
            String errorList = errors.stream().map(e -> e.toString()).reduce("",
                    (res, error) -> res + "[ " + error + " ]");
            throw new RuntimeException("Validation errors:" + errorList);
        }

        return (upateMapping == -1 ? -1 : mappings.get(upateMapping).id);
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