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

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import mqttagent.callback.GenericCallback;
import mqttagent.configuration.MQTTConfiguration;
import mqttagent.core.C8yAgent;
import mqttagent.model.InnerNode;
import mqttagent.model.Mapping;
import mqttagent.model.MappingStatus;
import mqttagent.model.MappingsRepresentation;
import mqttagent.model.QOS;
import mqttagent.model.ResolveException;
import mqttagent.model.TreeNode;
import mqttagent.model.ValidationError;

import org.apache.commons.lang3.tuple.MutablePair;

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
    private ExecutorService newCachedThreadPool = Executors.newCachedThreadPool();

    private Future reconnectTask;
    private Future initTask;

    private boolean initilized = false;
    private Set<String> activeSubscriptionsLog = new HashSet<String>();

    private TreeNode mappingTree = InnerNode.initTree();;
    private Set<Mapping> dirtyMappings = new HashSet<Mapping>();

    @Getter
    private Map<Long, MappingStatus> monitoring = new HashMap<Long, MappingStatus>();

    private void runInit() {
        while (!isInitilized()) {
            log.info("Try to retrieve MQTT connection configuration now ...");
            mqttConfiguration = c8yAgent.loadConfiguration();
            log.info("Configuration found: {}", mqttConfiguration);
            if (mqttConfiguration != null && mqttConfiguration.active) {
                try {
                    String prefix = mqttConfiguration.useTLS ? "ssl://" : "tcp://";
                    String broker = prefix + mqttConfiguration.mqttHost + ":" + mqttConfiguration.mqttPort;
                    mqttClient = new MqttClient(broker, mqttConfiguration.getClientId() + "_dummy" , new MemoryPersistence());
                    setInitilized(true);
                    log.info("Connecting to MQTT Broker {}", broker);
                } catch (HttpServerErrorException e) {
                    log.error("Failed to authenticate to MQTT broker", e);

                } catch (MqttException e) {
                    log.error("Failed to connect to MQTT broker", e);
                }
            }

            try {
                log.info("Try to retrieve MQTT connection configuration in 30s, active: {}...", mqttConfiguration.active);
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
            init();
            try {
                connect();
                // Uncomment this if you want to subscribe on start on "#"
                // subscribe("#", 0);
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
                    STATUS_MQTT_EVENT_TYPE,
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

    public void reloadMappings() {
        List<Mapping> updatedMaps = c8yAgent.getMappings();
        Map<String, MutablePair<Integer, QOS>> updatedSubscriptionsLog = new HashMap<String, MutablePair<Integer, QOS>>();
        Set<Long> acticeMaps = new HashSet<Long>(monitoring.keySet());

        // add existing subscriptionTopics to updatedSubscriptionLog, to verify if they are still needed
        activeSubscriptionsLog.forEach((topic) -> {
            MutablePair<Integer, QOS> p = updatedSubscriptionsLog.getOrDefault(topic, new MutablePair<Integer, QOS>(0, QOS.AT_LEAST_ONCE));
            updatedSubscriptionsLog.put(topic, p);
        });
        updatedMaps.forEach(m -> {
            if (m.active) {
                // if multiple subscriptions for a topic exist the qos is det by the first mapping (this can result in conflicts)
                MutablePair<Integer, QOS> p = updatedSubscriptionsLog.getOrDefault(m.subscriptionTopic, new MutablePair<Integer, QOS>(0, m.qos));
                p.left++;
                updatedSubscriptionsLog.put(m.subscriptionTopic, p);
            }
            if (!monitoring.containsKey(m.id)) {
                log.info("Adding: {}", m.id);
                monitoring.put(m.id, new MappingStatus(m.id, m.subscriptionTopic, 0, 0, m.snoopedTemplates.size(), 0));
            }
            acticeMaps.remove(m.id);
        });


        // always keep monitoring entry for monitoring that can't be related to any mapping and so they are unspecified 
        if (!monitoring.containsKey(KEY_MONITORING_UNSPECIFIED)) {
            log.info("Adding: {}", KEY_MONITORING_UNSPECIFIED);
            monitoring.put(KEY_MONITORING_UNSPECIFIED, new MappingStatus(KEY_MONITORING_UNSPECIFIED, "#", 0, 0, 0, 0));
        }
        acticeMaps.remove(KEY_MONITORING_UNSPECIFIED);
        // remove monitorings for deleted maps
        acticeMaps.forEach(id -> {
            log.info("Removing monitoring not used: {}", id);
            monitoring.remove(id);
        });

        // unsubscribe not used topics
        updatedSubscriptionsLog.forEach((topic, entry) -> {
            log.info("Processing unsubscribe for topic: {}, count: {}", topic, entry.left);
            // topic was deleted -> unsubscribe
            if (entry.left == 0) {
                try {
                    log.debug("Unsubscribe from topic: {} ...", topic);
                    unsubscribe(topic);
                    activeSubscriptionsLog.remove(topic);
                } catch (MqttException e) {
                    log.error("Could not unsubscribe topic: {}", topic);
                }
            } else if (!activeSubscriptionsLog.contains(topic)) {
                // subscription topic is new, we need to subscribe to it
                try {
                    log.debug("Subscribing to topic: {} ...", topic);
                    subscribe(topic, (int) entry.right.ordinal());
                    activeSubscriptionsLog.add(topic);
                } catch (MqttException | IllegalArgumentException e) {
                    log.error("Could not subscribe topic: {}", topic);
                }
            }
        });
        // update mappings tree
        mappingTree = rebuildMappingTree(updatedMaps);
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
            String statusReconnectTask = (reconnectTask == null ? "stopped"
                    : reconnectTask.isDone() ? "stopped" : "running");
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

    public TreeNode getActiveMappings() {
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
