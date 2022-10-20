package mqtt.mapping.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;
import mqtt.mapping.configuration.MQTTConfiguration;
import mqtt.mapping.core.C8yAgent;
import mqtt.mapping.model.InnerNode;
import mqtt.mapping.model.Mapping;
import mqtt.mapping.model.MappingStatus;
import mqtt.mapping.model.MappingsRepresentation;
import mqtt.mapping.model.ResolveException;
import mqtt.mapping.model.TreeNode;
import mqtt.mapping.model.ValidationError;
import mqtt.mapping.processor.PayloadProcessor;
import mqtt.mapping.processor.ProcessingContext;

@Slf4j
@Configuration
@EnableScheduling
@Service
public class MQTTClient {

    private static final String ADDITION_TEST_DUMMY = "_d1";
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
    private PayloadProcessor payloadProcessor;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    @Qualifier("cachedThreadPool")
    private ExecutorService cachedThreadPool;

    private Future<Boolean> connectTask;
    private Future<Boolean> initializeTask;

    private Set<String> activeSubscriptionTopic = new HashSet<String>();
    private List<Mapping> activeMapping = new ArrayList<Mapping>();

    private TreeNode mappingTree = InnerNode.initTree();;
    private Set<Mapping> dirtyMappings = new HashSet<Mapping>();

    private Map<String, MappingStatus> statusMapping = new HashMap<String, MappingStatus>();

    public void submitInitialize() {
        // test if init task is still running, then we don't need to start another task
        log.info("Called initialize(): {}", initializeTask == null || initializeTask.isDone());
        if ((initializeTask == null || initializeTask.isDone())) {
            initializeTask = cachedThreadPool.submit(() -> initialize());
        }
    }

    private boolean initialize() {
        var firstRun = true;
        // initialize entry to record errors that can't be assigned to any map, i.e. UNSPECIFIED
        getMappingStatus(null,true);
        while ( !MQTTConfiguration.isActive(mqttConfiguration)) {
            if (!firstRun) {
                try {
                    log.info("Retrieving MQTT configuration in {}s ...",
                            WAIT_PERIOD_MS / 1000);
                    Thread.sleep(WAIT_PERIOD_MS);
                } catch (InterruptedException e) {
                    log.error("Error initializing MQTT client: ", e);
                }
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
        log.info("Establishing the MQTT connection now (phase I), shouldConnect:", shouldConnect());
        if (isConnected()) {
            disconnect();
        }
        var firstRun = true;
        while (!isConnected() && shouldConnect()) {
            log.debug("Establishing the MQTT connection now (phase II): {}, {}", MQTTConfiguration.isValid(mqttConfiguration), MQTTConfiguration.isActive(mqttConfiguration));
            if (!firstRun) {
                try {
                    Thread.sleep(WAIT_PERIOD_MS);
                } catch (InterruptedException e) {
                    log.error("Error on reconnect: ", e);
                }
            }
            try {
                if (MQTTConfiguration.isActive(mqttConfiguration)) {
                    String prefix = mqttConfiguration.useTLS ? "ssl://" : "tcp://";
                    String broker = prefix + mqttConfiguration.mqttHost + ":" + mqttConfiguration.mqttPort;
                    mqttClient = new MqttClient(broker, mqttConfiguration.getClientId() + ADDITION_TEST_DUMMY,
                            new MemoryPersistence());
                    mqttClient.setCallback(payloadProcessor);
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

    private boolean shouldConnect() {
        return !MQTTConfiguration.isValid(mqttConfiguration) || MQTTConfiguration.isActive(mqttConfiguration);
    }

    public boolean isConnected() {
        return mqttClient != null ? mqttClient.isConnected() : false;
    }

    public void disconnect() {
        log.info("Disconnecting from MQTT broker: {}",
                (mqttClient == null ? null : mqttClient.getServerURI()));
        try {
            if (isConnected()) {
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
        sendStatusService();
    }

    public void connectToBroker() {
        mqttConfiguration = c8yAgent.setConfigurationActive(true);
        submitConnect();
        sendStatusService();
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

    public void reloadMappings() {
        List<Mapping> updatedMapping = c8yAgent.getMappings();
        Set<String> updatedMappingSet = updatedMapping.stream().map(m -> m.ident).collect(Collectors.toSet());
        Set<String> activeMappingSet = activeMapping.stream().map(m -> m.ident).collect(Collectors.toSet());
        Set<String> updatedSubscriptionTopic = updatedMapping.stream().map(m -> m.subscriptionTopic)
                .collect(Collectors.toSet());
        Set<String> unsubscribeTopic = activeSubscriptionTopic.stream()
                .filter(e -> !updatedSubscriptionTopic.contains(e)).collect(Collectors.toSet());
        Set<String> subscribeTopic = updatedSubscriptionTopic.stream()
                .filter(e -> !activeSubscriptionTopic.contains(e)).collect(Collectors.toSet());
        Set<String> removedMapping = activeMappingSet.stream().filter(m -> !updatedMappingSet.contains(m))
                .collect(Collectors.toSet());

        // remove monitorings for deleted maps
        removedMapping.forEach(ident -> {
            log.info("Removing monitoring not used: {}", ident);
            statusMapping.remove(ident);
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
            log.info("Subscribing to topic: {}, qos: {}", topic, qos);
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
            String statusConnectTask = (connectTask == null ? "stopped"
                    : connectTask.isDone() ? "stopped" : "running");
            String statusInitializeTask = (initializeTask == null ? "stopped" : initializeTask.isDone() ? "stopped" : "running");
            log.info("Status: connectTask {}, initializeTask {}, isConnected {}", statusConnectTask,
                    statusInitializeTask, isConnected());
            cleanDirtyMappings();
            sendStatusMapping();
            sendStatusService();
        } catch (Exception ex) {
            log.error("Error during house keeping execution: {}", ex);
        }
    }

    private void sendStatusMapping() {
        c8yAgent.sendStatusMapping(STATUS_MAPPING_EVENT_TYPE, statusMapping);
    }

    private void sendStatusService() {
        ServiceStatus statusService;
        statusService = getServiceStatus();
        c8yAgent.sendStatusService(STATUS_SERVICE_EVENT_TYPE, statusService);
    }

    public ServiceStatus getServiceStatus() {
        ServiceStatus serviceStatus;
        if (isConnected()) {
            serviceStatus = ServiceStatus.connected();
        } else if (MQTTConfiguration.isActive(mqttConfiguration)) {
            serviceStatus = ServiceStatus.activated();
        } else if (MQTTConfiguration.isValid(mqttConfiguration)) {
            serviceStatus = ServiceStatus.configured();
        } else {
            serviceStatus = ServiceStatus.notReady();
        }
        return serviceStatus;
    }

    private void cleanDirtyMappings() throws JsonProcessingException {
        // test if for this tenant dirty mappings exist
        log.debug("Testing for dirty maps");
        for (Mapping mqttMapping : dirtyMappings) {
            log.info("Found mapping to be saved: {}, {}", mqttMapping.id, mqttMapping.snoopStatus);
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
        long key = m.id;
        String ident = m.ident;
        String t = m.subscriptionTopic;
        if ( unspecified) {
            t = "#";
            key = -1;
            ident = "#";
        }
        MappingStatus ms = statusMapping.get(ident);
        if (ms == null) {
            log.info("Adding: {}", key);
            ms = new MappingStatus(key, m.ident, t, 0, 0, 0, 0);
            statusMapping.put(ident, ms);
        }
        return ms;
    }

    public void initializeMappingStatus(MappingStatus ms){
        statusMapping.put(ms.ident, ms);
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
            mapping.ident = UUID.randomUUID().toString();
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
        int updateMapping = -1;
        ArrayList<Mapping> mappings = c8yAgent.getMappings();
        ArrayList<ValidationError> errors = MappingsRepresentation.isMappingValid(mappings, mapping);
        if (errors.size() == 0) {
            updateMapping = IntStream.range(0, mappings.size())
                    .filter(i -> id.equals(mappings.get(i).id)).findFirst().orElse(-1);
            if (updateMapping != -1) {
                log.info("Update mapping with id: {}", mappings.get(updateMapping).id);
                mapping.lastUpdate = System.currentTimeMillis();
                mapping.ident = mappings.get(updateMapping).ident;
                mappings.set(updateMapping, mapping);
                c8yAgent.saveMappings(mappings);
                if (reload) reloadMappings();
            } else {
                log.error("Something went wrong when updating mapping: {}", id);
            }
        } else {
            String errorList = errors.stream().map(e -> e.toString()).reduce("",
                    (res, error) -> res + "[ " + error + " ]");
            throw new RuntimeException("Validation errors:" + errorList);
        }

        c8yAgent.saveMappings(mappings);
        reloadMappings();
        return (long) updateMapping;
    }

    public void runOperation(ServiceOperation operation) {
        if (operation.getOperation().equals(Operation.RELOAD)) {
            reloadMappings();
        } else if (operation.getOperation().equals(Operation.CONNECT)) {
            connectToBroker();
        } else if (operation.getOperation().equals(Operation.DISCONNECT)) {
            disconnectFromBroker();
        } else if (operation.getOperation().equals(Operation.RESFRESH_MAPPING_STATUS)) {
            sendStatusMapping();
        }
    }

    public List<ProcessingContext> test(String topic, boolean send, Map<String, Object> payload) throws JsonProcessingException {
        String payloadMessage =  objectMapper.writeValueAsString(payload);
        ProcessingContext ctx = new ProcessingContext();
        ctx.setPayload(payloadMessage);
        ctx.setTopic(topic);
        return payloadProcessor.processPayload(ctx, send);
    }
}