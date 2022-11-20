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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

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
import mqtt.mapping.configuration.ConnectionConfigurationComponent;
import mqtt.mapping.configuration.ServiceConfiguration;
import mqtt.mapping.configuration.ServiceConfigurationComponent;
import mqtt.mapping.core.C8YAgent;
import mqtt.mapping.core.MappingComponent;
import mqtt.mapping.core.ServiceStatus;
import mqtt.mapping.model.InnerNode;
import mqtt.mapping.model.Mapping;
import mqtt.mapping.model.MappingNode;
import mqtt.mapping.model.ResolveException;
import mqtt.mapping.model.TreeNode;
//import mqtt.mapping.processor.SynchronousDispatcher;
import mqtt.mapping.processor.AsynchronousDispatcher;
import mqtt.mapping.processor.model.ProcessingContext;

@Slf4j
@Configuration
@EnableScheduling
@Service
public class MQTTClient {

    private static final String ADDITION_TEST_DUMMY = "_D1";
    private static final int WAIT_PERIOD_MS = 10000;
    public static final Long KEY_MONITORING_UNSPECIFIED = -1L;
    private static final String STATUS_MQTT_EVENT_TYPE = "mqtt_status_event";

    private ConfigurationConnection connectionConfiguration;
    private Certificate cert;

    @Getter
    private ServiceConfiguration serviceConfiguration;

    @Autowired
    private ConnectionConfigurationComponent connectionConfigurationComponent;

    @Autowired
    private ServiceConfigurationComponent serviceConfigurationComponent;

    @Autowired
    private MappingComponent mappingStatusComponent;

    private MqttClient mqttClient;

    @Autowired
    private C8YAgent c8yAgent;

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
        mappingStatusComponent.getMappingStatus(null, true);
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

                        // before we create a new mqttClient, test if there already exists on and try to close it
                        if (mqttClient != null) {
                            mqttClient.close(true);
                        }

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
        connectionConfiguration = connectionConfigurationComponent.enableConnection(false);
        disconnect();
        c8yAgent.sendStatusService(getServiceStatus());
    }

    public void connectToBroker() {
        connectionConfiguration = connectionConfigurationComponent.enableConnection(true);
        submitConnect();
        c8yAgent.sendStatusService(getServiceStatus());
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
            mappingStatusComponent.removeStatusMapping(ident);
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
            c8yAgent.sendStatusMapping();
            c8yAgent.sendStatusService(getServiceStatus());
        } catch (Exception ex) {
            log.error("Error during house keeping execution: {}", ex);
        }
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
        for (Mapping mapping : mappingStatusComponent.getMappingDirty()) {
            log.info("Found mapping to be saved: {}, {}", mapping.id, mapping.snoopStatus);
            // no reload required
            c8yAgent.updateMapping(mapping, mapping.id);
        }
        // reset dirtySet
        mappingStatusComponent.resetMappingDirty();
    }

    public TreeNode getMappingTree() {
        return mappingTree;
    }

    public void runOperation(ServiceOperation operation) {
        if (operation.getOperation().equals(Operation.RELOAD_MAPPINGS)) {
            reloadMappings();
        } else if (operation.getOperation().equals(Operation.CONNECT)) {
            connectToBroker();
        } else if (operation.getOperation().equals(Operation.DISCONNECT)) {
            disconnectFromBroker();
        } else if (operation.getOperation().equals(Operation.RESFRESH_STATUS_MAPPING)) {
            c8yAgent.sendStatusMapping();
        } else if (operation.getOperation().equals(Operation.RESET_STATUS_MAPPING)) {
            mappingStatusComponent.resetMappingStatus();
        } else if (operation.getOperation().equals(Operation.RELOAD_EXTENSIONS)) {
            c8yAgent.reloadExtensions();
        }
    }

    public List<ProcessingContext<?>> test(String topic, boolean send, Map<String, Object> payload)
            throws Exception {
        String payloadMessage = objectMapper.writeValueAsString(payload);
        MqttMessage mqttMessage = new MqttMessage();
        mqttMessage.setPayload(payloadMessage.getBytes());
        return dispatcher.processMessage(topic, mqttMessage, send).get();
    }


    public void saveServiceConfiguration(ServiceConfiguration configuration) throws JsonProcessingException {
        serviceConfiguration = configuration;
        serviceConfigurationComponent.saveServiceConfiguration(configuration);
    }

    public List<Mapping> resolveMappings(String topic) throws ResolveException {
        List<TreeNode> resolvedMappings = getMappingTree()
                .resolveTopicPath(Mapping.splitTopicIncludingSeparatorAsList(topic));
        return resolvedMappings.stream().filter(tn -> tn instanceof MappingNode)
                .map(mn -> ((MappingNode) mn).getMapping()).collect(Collectors.toList());
    }

    public void reconnect() {
        disconnect();
        // invalidate broker client
        connectionConfiguration = null;
        submitInitialize();
        submitConnect();
    }
}