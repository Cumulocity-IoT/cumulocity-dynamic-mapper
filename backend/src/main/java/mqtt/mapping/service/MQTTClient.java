package mqtt.mapping.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
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

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

import org.apache.commons.lang3.StringUtils;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
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

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import mqtt.mapping.configuration.ConfigurationConnection;
import mqtt.mapping.configuration.ServiceConfiguration;
import mqtt.mapping.core.C8yAgent;
import mqtt.mapping.model.InnerNode;
import mqtt.mapping.model.Mapping;
import mqtt.mapping.model.MappingNode;
import mqtt.mapping.model.MappingStatus;
import mqtt.mapping.model.MappingsRepresentation;
import mqtt.mapping.model.ResolveException;
import mqtt.mapping.model.TreeNode;
import mqtt.mapping.model.ValidationError;
import mqtt.mapping.processor.SynchronousDispatcher;
import mqtt.mapping.processor.AsynchronousDispatcher;
import mqtt.mapping.processor.ProcessingContext;

@Slf4j
@Configuration
@EnableScheduling
@Service
public class MQTTClient {

    private static final String ADDITION_TEST_DUMMY = "_D1";
    private static final int WAIT_PERIOD_MS = 10000;
    public static final Long KEY_MONITORING_UNSPECIFIED = -1L;
    private static final String STATUS_MQTT_EVENT_TYPE = "mqtt_status_event";
    private static final String STATUS_MAPPING_EVENT_TYPE = "mqtt_mapping_event";
    private static final String STATUS_SERVICE_EVENT_TYPE = "mqtt_service_event";

    private ConfigurationConnection connectionConfiguration;
    private Certificate cert;

    @Getter
    private ServiceConfiguration serviceConfiguration;

    private MqttClient mqttClient;

    @Autowired
    private C8yAgent c8yAgent;

    // @Autowired
    // private SynchronousDispatcher dispatcher;

    @Autowired
    private AsynchronousDispatcher dispatcher;

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

    private Instant start = Instant.now();

    @Data
    @AllArgsConstructor
    public static class Certificate {
        private String fingerprint;
        private String certInPemFormat;
    }

    public void submitInitialize() {
        // test if init task is still running, then we don't need to start another task
        log.info("Called initialize(): {}", initializeTask == null || initializeTask.isDone());
        if ((initializeTask == null || initializeTask.isDone())) {
            initializeTask = cachedThreadPool.submit(() -> initialize());
        }
    }

    private boolean initialize() {
        var firstRun = true;
        // initialize entry to record errors that can't be assigned to any map, i.e.
        // UNSPECIFIED
        getMappingStatus(null, true);
        while (!canConnect()) {
            if (!firstRun) {
                try {
                    log.info("Retrieving MQTT configuration in {}s ...",
                            WAIT_PERIOD_MS / 1000);
                    Thread.sleep(WAIT_PERIOD_MS);
                } catch (InterruptedException e) {
                    log.error("Error initializing MQTT client: ", e);
                }
            }
            connectionConfiguration = c8yAgent.loadConnectionConfiguration();
            if (connectionConfiguration.useSelfSignedCertificate) {
                cert = c8yAgent.loadCertificateByName(connectionConfiguration.nameCertificate);
            }
            serviceConfiguration = c8yAgent.loadServiceConfiguration();
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

    private boolean connect() throws Exception {
        log.info("Establishing the MQTT connection now - phase I: (isConnected:shouldConnect) ({}:{})", isConnected(),
                shouldConnect());
        if (isConnected()) {
            disconnect();
        }
        // stay in the loop until successful
        boolean successful = false;
        while (!successful) {
            var firstRun = true;
            while (!isConnected() && shouldConnect()) {
                log.info("Establishing the MQTT connection now - phase II: {}, {}",
                        ConfigurationConnection.isValid(connectionConfiguration), canConnect());
                if (!firstRun) {
                    try {
                        Thread.sleep(WAIT_PERIOD_MS);
                    } catch (InterruptedException e) {
                        log.error("Error on reconnect: ", e);
                    }
                }
                try {
                    if (canConnect()) {
                        String prefix = connectionConfiguration.useTLS ? "ssl://" : "tcp://";
                        String broker = prefix + connectionConfiguration.mqttHost + ":"
                                + connectionConfiguration.mqttPort;
                        // mqttClient = new MqttClient(broker, MqttClient.generateClientId(), new
                        // MemoryPersistence());
                        mqttClient = new MqttClient(broker, connectionConfiguration.getClientId() + ADDITION_TEST_DUMMY,
                                new MemoryPersistence());
                        mqttClient.setCallback(dispatcher);
                        MqttConnectOptions connOpts = new MqttConnectOptions();
                        connOpts.setCleanSession(true);
                        connOpts.setAutomaticReconnect(false);
                        if ( !StringUtils.isEmpty(connectionConfiguration.user) && !StringUtils.isEmpty(connectionConfiguration.password)){
                            connOpts.setUserName(connectionConfiguration.getUser());
                            connOpts.setPassword(connectionConfiguration.getPassword().toCharArray());
                        }
                        if (connectionConfiguration.useSelfSignedCertificate) {
                            log.debug("Using certificate: {}", cert.certInPemFormat);

                            try {
                                KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
                                trustStore.load(null, null);
                                trustStore.setCertificateEntry("Custom CA",
                                        (X509Certificate) CertificateFactory.getInstance("X509")
                                                .generateCertificate(new ByteArrayInputStream(
                                                        cert.certInPemFormat.getBytes(Charset.defaultCharset()))));

                                TrustManagerFactory tmf = TrustManagerFactory
                                        .getInstance(TrustManagerFactory.getDefaultAlgorithm());
                                tmf.init(trustStore);
                                TrustManager[] trustManagers = tmf.getTrustManagers();

                                SSLContext sslContext = SSLContext.getInstance("TLSv1.2");
                                sslContext.init(null, trustManagers, null);
                                SSLSocketFactory sslSocketFactory = sslContext.getSocketFactory();

                                // where options is the MqttConnectOptions object
                                connOpts.setSocketFactory(sslSocketFactory);
                            } catch (NoSuchAlgorithmException | CertificateException | IOException | KeyStoreException
                                    | KeyManagementException e) {
                                log.error("Exception when configuraing socketFactory for TLS!", e);
                                throw new Exception(e);
                            }
                        }
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

            // try {
            // Thread.sleep(WAIT_PERIOD_MS / 10);
            // } catch (InterruptedException e) {
            // log.error("Error on reconnect: ", e);
            // }

            try {
                subscribe("$SYS/#", 0);
                activeSubscriptionTopic = new HashSet<String>();
                activeMapping = new ArrayList<Mapping>();
                reloadMappings();
            } catch (MqttException e) {
                log.error("Error on reconnect, retrying ... ", e);
            }
            successful = true;

        }
        return true;
    }

    private boolean canConnect() {
        return ConfigurationConnection.isEnabled(connectionConfiguration)
                && (!connectionConfiguration.useSelfSignedCertificate
                        || (connectionConfiguration.useSelfSignedCertificate && 
                                cert != null));
    }

    private boolean shouldConnect() {
        return !ConfigurationConnection.isValid(connectionConfiguration)
                || ConfigurationConnection.isEnabled(connectionConfiguration);
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
                        log.error("Exception when unsubscribing from topic: {}, {}", topic, e);
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
        connectionConfiguration = c8yAgent.enableConnection(false);
        disconnect();
        sendStatusService();
    }

    public void connectToBroker() {
        connectionConfiguration = c8yAgent.enableConnection(true);
        submitConnect();
        sendStatusService();
    }

    public ConfigurationConnection loadConnectionConfiguration() {
        return c8yAgent.loadConnectionConfiguration();
    }

    public void saveConnectionConfiguration(ConfigurationConnection configuration) {
        c8yAgent.saveConnectionConfiguration(configuration);
        disconnect();
        // invalidate broker client
        connectionConfiguration = null;
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
                log.error("Exception when unsubscribing from topic: {}, {}", topic, e1);
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
                log.error("Exception when subscribing to topic: {}, {}", topic, e1);
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

        log.debug("Subscribing on topic: {}", topic);
        c8yAgent.createEvent("Subscribing on topic " + topic, STATUS_MQTT_EVENT_TYPE, DateTime.now(), null);
        if (qos != null)
            mqttClient.subscribe(topic, qos);
        else
            mqttClient.subscribe(topic);
        log.debug("Successfully subscribed on topic: {}", topic);

    }

    private void unsubscribe(String topic) throws MqttException {
        log.info("Unsubscribing from topic: {}", topic);
        c8yAgent.createEvent("Unsubscribing on topic " + topic, STATUS_MQTT_EVENT_TYPE, DateTime.now(), null);
        mqttClient.unsubscribe(topic);
    }

    @Scheduled(fixedRate = 30000)
    public void runHouskeeping() {
        try {
            Instant now = Instant.now();
            // only log this for the first 180 seconds to reduce log amount
            if (Duration.between(start, now).getSeconds() < 1800) {
                String statusConnectTask = (connectTask == null ? "stopped"
                        : connectTask.isDone() ? "stopped" : "running");
                String statusInitializeTask = (initializeTask == null ? "stopped"
                        : initializeTask.isDone() ? "stopped" : "running");
                log.info("Status: connectTask: {}, initializeTask: {}, isConnected: {}", statusConnectTask,
                        statusInitializeTask, isConnected());
            }
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
        } else if (canConnect()) {
            serviceStatus = ServiceStatus.activated();
        } else if (ConfigurationConnection.isValid(connectionConfiguration)) {
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
        log.debug("Setting dirty: {}", mapping);
        dirtyMappings.add(mapping);
    }

    public MappingStatus getMappingStatus(Mapping m, boolean unspecified) {
        String topic = "#";
        long key = -1;
        String ident = "#";
        if (!unspecified) {
            topic = m.subscriptionTopic;
            key = m.id;
            ident = m.ident;
        }
        MappingStatus ms = statusMapping.get(ident);
        if (ms == null) {
            log.info("Adding: {}", key);
            ms = new MappingStatus(key, ident, topic, 0, 0, 0, 0);
            statusMapping.put(ident, ms);
        }
        return ms;
    }

    public void initializeMappingStatus(MappingStatus ms) {
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
                if (reload)
                    reloadMappings();
            } else {
                log.error("Something went wrong when updating mapping: {}", id);
            }
        } else {
            String errorList = errors.stream().map(e -> e.toString()).reduce("",
                    (res, error) -> res + "[ " + error + " ]");
            throw new RuntimeException("Validation errors:" + errorList);
        }
        return (long) updateMapping;
    }

    public void runOperation(ServiceOperation operation) {
        if (operation.getOperation().equals(Operation.RELOAD)) {
            reloadMappings();
        } else if (operation.getOperation().equals(Operation.CONNECT)) {
            connectToBroker();
        } else if (operation.getOperation().equals(Operation.DISCONNECT)) {
            disconnectFromBroker();
        } else if (operation.getOperation().equals(Operation.RESFRESH_STATUS_MAPPING)) {
            sendStatusMapping();
        } else if (operation.getOperation().equals(Operation.RESET_STATUS_MAPPING)) {
            resetMappingStatus();
        }
    }

    public List<ProcessingContext<?>> test(String topic, boolean send, Map<String, Object> payload)
            throws Exception {
        String payloadMessage = objectMapper.writeValueAsString(payload);
        MqttMessage mqttMessage = new MqttMessage();
        mqttMessage.setPayload(payloadMessage.getBytes());
        return dispatcher.processMessage(topic, mqttMessage, send).get();
    }

    public List<MappingStatus> getMappingStatus() {
        return new ArrayList<MappingStatus>(statusMapping.values());
    }

    public List<MappingStatus> resetMappingStatus() {
        ArrayList<MappingStatus> msl = new ArrayList<MappingStatus>(statusMapping.values());
        msl.forEach(ms -> ms.reset());
        return msl;
    }

    public void saveServiceConfiguration(ServiceConfiguration configuration) {
        serviceConfiguration = configuration;
        c8yAgent.saveServiceConfiguration(configuration);
    }

    public ServiceConfiguration loadServiceConfiguration() {
        return c8yAgent.loadServiceConfiguration();
    }

    public List<Mapping> resolveMappings(String topic) throws ResolveException {
        List<TreeNode> resolvedMappings = getMappingTree()
                .resolveTopicPath(Mapping.splitTopicIncludingSeparatorAsList(topic));
        return resolvedMappings.stream().filter(tn -> tn instanceof MappingNode)
                .map(mn -> ((MappingNode) mn).getMapping()).collect(Collectors.toList());
    }
}