package mqtt.mapping.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

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

import com.fasterxml.jackson.core.JsonProcessingException;

import lombok.Getter;
import lombok.Setter;
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

    public static final Long KEY_MONITORING_UNSPECIFIED = -1L;
    private static final String STATUS_MQTT_EVENT_TYPE = "mqtt_status_event";
    private static final String STATUS_MAPPING_EVENT_TYPE = "mqtt_mapping_event";
    private static final String STATUS_SERVICE_EVENT_TYPE = "mqtt_service_event";

    MQTTConfiguration mqttConfiguration;
    private MqttClient mqttClient;

    @Autowired
    private C8yAgent c8yAgent;

    @Autowired
    private GenericCallback genericCallback;
    private ExecutorService cachedThreadPool = Executors.newCachedThreadPool();

    private Future connectTask;
    private Future initTask;

    @Getter
    @Setter
    private boolean initilized = false;
    private Set<String> activeSubscriptionTopic = new HashSet<String>();
    private List<Mapping> activeMapping = new ArrayList<Mapping>();

    private TreeNode mappingTree = InnerNode.initTree();;
    private Set<Mapping> dirtyMappings = new HashSet<Mapping>();

    @Getter
    private Map<Long, MappingStatus> monitoring = new HashMap<Long, MappingStatus>();

    public void submitInitialize() {
        // test if init task is still running, then we don't need to start another task
        log.info("Called init(): {}", initTask == null ? false : initTask.isDone());
        if ((initTask != null && initTask.isDone()) || initTask == null) {
            initTask = cachedThreadPool.submit(() -> initialize());
        }
    }

    private void initialize() {
        while (!isInitilized()) {
            log.info("Try to retrieve MQTT connection configuration now ...");
            mqttConfiguration = c8yAgent.loadConfiguration();
            log.info("Configuration found: {}", mqttConfiguration);
            if (mqttConfiguration != null && mqttConfiguration.active) {
                try {
                    String prefix = mqttConfiguration.useTLS ? "ssl://" : "tcp://";
                    String broker = prefix + mqttConfiguration.mqttHost + ":" + mqttConfiguration.mqttPort;
                    mqttClient = new MqttClient(broker, mqttConfiguration.getClientId() + "_d",
                            new MemoryPersistence());
                    setInitilized(true);
                    log.info("MQTT client to broker {} is initialized", broker);
                } catch (MqttException e) {
                    log.error("Error initializing MQTT client", e);
                }
            }

            try {
                log.info("Try to retrieve MQTT connection configuration in 30s, active: {}...",
                        mqttConfiguration.active);
                Thread.sleep(10000);
            } catch (InterruptedException e) {
                log.error("Error initializing MQTT client: ", e);
            }

        }
    }

    public void submitConnect() {
        // test if connect task is still running, then we don't need to start another
        // task
        log.info("Called connect(): connectTask.isDone() {}",
                connectTask == null ? false : connectTask.isDone());
        if ((connectTask != null && connectTask.isDone()) || connectTask == null) {
            connectTask = cachedThreadPool.submit(() -> {
                connect();
            });
        }
    }

    private void connect() {
        log.info("Try to establish the MQTT connection now (phase I)");
        disconnect();
        while (!isConnected()) {
            log.debug("Try to establish the MQTT connection now (phase II)");
            // submitInitialize();
            try {
                if (isConnectionConfigured() && !isConnected()) {
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

            try {
                Thread.sleep(10000);
            } catch (InterruptedException e) {
                log.error("Error on reconnect: ", e);
            }
        }

        try {
            subscribe("$SYS/#", 0);
            activeSubscriptionTopic = new HashSet<String>();
            activeMapping = new ArrayList<Mapping>();
            reloadMappings();
        } catch (MqttException e) {
            log.error("Error on reconnect: ", e);
        }
    }

    public boolean isConnected() {
        return mqttClient != null ? mqttClient.isConnected() : false;
    }

    public void disconnect() {
        log.info("Disconnecting from MQTT broker: {}, isInitialized: {}",
                (mqttClient == null ? null : mqttClient.getServerURI()),
                isInitilized());
        try {
            if (isInitilized() && mqttClient != null && mqttClient.isConnected()) {
                log.debug("Disconnected from MQTT broker I: {}", mqttClient.getServerURI());
                mqttClient.unsubscribe("#");
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
        setInitilized(false);
        submitInitialize();
        submitConnect();
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
        // always keep monitoring entry for monitoring that can't be related to any
        // mapping and so they are unspecified
        if (!monitoring.containsKey(KEY_MONITORING_UNSPECIFIED)) {
            log.info("Adding: {}", KEY_MONITORING_UNSPECIFIED);
            monitoring.put(KEY_MONITORING_UNSPECIFIED, new MappingStatus(KEY_MONITORING_UNSPECIFIED, "#", 0, 0, 0, 0));
        }

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
            Optional<Integer> qosAcc = updatedMapping.stream().filter(m -> m.subscriptionTopic.equals(topic))
                    .map(m -> m.qos.ordinal()).reduce(Integer::max);
            int qos = 0;
            if (qosAcc.isPresent()) {
                qos = qosAcc.get();
            } else {
                log.error("Something went wrong, when subscribing to topic: {}, using 0 for QOS.", topic);
            }
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
        if (isInitilized() && mqttClient != null) {
            log.debug("Subscribing on topic {}", topic);
            c8yAgent.createEvent("Subscribing on topic " + topic, STATUS_MQTT_EVENT_TYPE, DateTime.now(), null);

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
            c8yAgent.createEvent("Unsubscribing on topic " + topic, STATUS_MQTT_EVENT_TYPE, DateTime.now(), null);

            mqttClient.unsubscribe(topic);
            log.debug("Successfully unsubscribed from topic {}", topic);
        }
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

    public Long deleteMapping(Long id) {
        Long[] result = { null };
        List<Mapping> mappings = c8yAgent.getMappings();
        List<Mapping> updatedMappings = new ArrayList<Mapping>();
        MutableInt i = new MutableInt(0);
        mappings.forEach(m -> {
            if (m.id == id) {
                result[0] = id;
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
        return result[0];
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

    public Long updateMapping(Long id, Mapping mapping, boolean reload) throws JsonProcessingException {
        Long[] result = { null };
        ArrayList<Mapping> mappings = c8yAgent.getMappings();
        ArrayList<ValidationError> errors = MappingsRepresentation.isMappingValid(mappings, mapping);

        if (errors.size() == 0) {
            MutableInt i = new MutableInt(0);
            mappings.forEach(m -> {
                if (m.id == id) {
                    log.info("Update mapping with id: {}", m.id);
                    m.copyFrom(mapping);
                    m.lastUpdate = System.currentTimeMillis();
                    result[0] = m.id;
                }
                i.increment();
            });
        } else {
            String errorList = errors.stream().map(e -> e.toString()).reduce("",
                    (res, error) -> res + "[ " + error + " ]");
            throw new RuntimeException("Validation errors:" + errorList);
        }
        try {
            c8yAgent.saveMappings(mappings);
        } catch (JsonProcessingException ex) {
            log.error("Cound not process parse mappings as json: {}", ex);
            throw ex;
        }

        // update cached mappings
        if (reload) {
            reloadMappings();
        }
        return result[0];
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
