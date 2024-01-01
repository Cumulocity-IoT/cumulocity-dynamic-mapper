package dynamic.mapping.core;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;

import dynamic.mapping.configuration.ServiceConfiguration;
import dynamic.mapping.configuration.ServiceConfigurationComponent;
import dynamic.mapping.connector.core.client.AConnectorClient;
import dynamic.mapping.connector.core.registry.ConnectorRegistry;
import dynamic.mapping.connector.core.registry.ConnectorRegistryException;
import dynamic.mapping.model.MappingServiceRepresentation;
import dynamic.mapping.notification.C8YAPISubscriber;
import dynamic.mapping.processor.PayloadProcessor;
import dynamic.mapping.processor.inbound.AsynchronousDispatcherInbound;
import dynamic.mapping.processor.outbound.AsynchronousDispatcherOutbound;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Service;

import com.cumulocity.microservice.subscription.model.MicroserviceSubscriptionAddedEvent;
import com.cumulocity.microservice.subscription.model.MicroserviceSubscriptionRemovedEvent;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import dynamic.mapping.configuration.ConnectorConfiguration;
import dynamic.mapping.configuration.ConnectorConfigurationComponent;
import dynamic.mapping.connector.mqtt.MQTTClient;

@Service
@EnableScheduling
@Slf4j
public class BootstrapService {

    @Value("${APP.outputMappingEnabled}")
    private boolean outputMappingEnabled;

    @Autowired
    ConnectorRegistry connectorRegistry;

    @Autowired
    C8YAgent c8YAgent;

    @Autowired
    private MappingComponent mappingComponent;

    @Autowired
    ServiceConfigurationComponent serviceConfigurationComponent;

    private Map<String, MappingServiceRepresentation> mappingServiceRepresentations;

    @Autowired
    public void setMappingServiceRepresentations(
            Map<String, MappingServiceRepresentation> mappingServiceRepresentations) {
        this.mappingServiceRepresentations = mappingServiceRepresentations;
    }

    @Getter
    public Map<String, ServiceConfiguration> serviceConfigurations;

    @Autowired
    public void setServiceConfigurations(Map<String, ServiceConfiguration> serviceConfigurations) {
        this.serviceConfigurations = serviceConfigurations;
    }

    @Autowired
    ConnectorConfigurationComponent connectorConfigurationComponent;

    @Getter
    private C8YAPISubscriber notificationSubscriber;

    @Autowired
    public void setNotificationSubscriber(@Lazy C8YAPISubscriber notificationSubscriber) {
        this.notificationSubscriber = notificationSubscriber;
    }

    private ObjectMapper objectMapper;

    @Autowired
    public void setObjectMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Qualifier("cachedThreadPool")
    private ExecutorService cachedThreadPool;

    @Autowired
    public void setCachedThreadPool(ExecutorService cachedThreadPool) {
        this.cachedThreadPool = cachedThreadPool;
    }

    @Value("${APP.additionalSubscriptionIdTest}")
    private String additionalSubscriptionIdTest;

    @EventListener
    public void destroy(MicroserviceSubscriptionRemovedEvent event) {
        log.info("Tenant {} - Microservice unsubscribed", event.getTenant());
        String tenant = event.getTenant();
        c8YAgent.getNotificationSubscriber().disconnect(tenant, false);
        c8YAgent.getNotificationSubscriber().deleteAllSubscriptions(tenant);

        try {
            connectorRegistry.unregisterAllClientsForTenant(tenant);
        } catch (ConnectorRegistryException e) {
            log.error("Error on cleaning up connector clients");
        }
    }

    @EventListener
    public void initialize(MicroserviceSubscriptionAddedEvent event) {
        // Executed for each tenant subscribed
        String tenant = event.getCredentials().getTenant();
        log.info("Tenant {} - Microservice subscribed", tenant);
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"));
        ManagedObjectRepresentation mappingServiceMOR = c8YAgent.createMappingServiceObject(tenant);

        ServiceConfiguration serviceConfiguration = serviceConfigurationComponent.loadServiceConfiguration();
        serviceConfigurations.put(tenant, serviceConfiguration);
        c8YAgent.createExtensibleProsessorForTenant(tenant);
        c8YAgent.loadProcessorExtensions(tenant);
        MappingServiceRepresentation mappingServiceRepresentation = objectMapper.convertValue(mappingServiceMOR,
                MappingServiceRepresentation.class);
        mappingServiceRepresentations.put(tenant, mappingServiceRepresentation);
        mappingComponent.initializeMappingStatus(tenant, false);
        // TODO Add other clients static property definition here
        connectorRegistry.registerConnector(MQTTClient.getConnectorType(), MQTTClient.getSpec());

        try {
            if (serviceConfiguration != null) {
                List<ConnectorConfiguration> connectorConfigurationList = connectorConfigurationComponent
                        .getConnectorConfigurations(tenant);
                // For each connector configuration create a new instance of the connector
                for (ConnectorConfiguration connectorConfiguration : connectorConfigurationList) {
                    initializeConnectorByConfiguration(connectorConfiguration, serviceConfiguration,
                            tenant);
                }
            }

        } catch (Exception e) {
            log.error("Error on initializing connectors: ", e);
            // mqttClient.submitConnect();
        }

        log.info("Tenant {} - OutputMapping Config Enabled: {}", tenant, outputMappingEnabled);
        if (outputMappingEnabled) {
            notificationSubscriber.initTenantClient();
            notificationSubscriber.initDeviceClient();
        }
    }

    public AConnectorClient initializeConnectorByConfiguration(ConnectorConfiguration connectorConfiguration,
            ServiceConfiguration serviceConfiguration, String tenant) throws ConnectorRegistryException {
        AConnectorClient client = null;

        if (MQTTClient.getConnectorType().equals(connectorConfiguration.getConnectorType())) {
            log.info("Tenant {} - Initializing MQTT Connector with ident {}", tenant,
                    connectorConfiguration.getIdent());
            MQTTClient mqttClient = new MQTTClient(tenant, objectMapper, c8YAgent, mappingComponent,
                    connectorConfigurationComponent, connectorConfiguration, serviceConfiguration, cachedThreadPool,
                    mappingServiceRepresentations.get(tenant), null,
                    additionalSubscriptionIdTest);

            connectorRegistry.registerClient(tenant, mqttClient);
            client = mqttClient;
        }

        // initialize AsynchronousDispatcherInbound
        PayloadProcessor payloadProcessor = new PayloadProcessor(objectMapper, c8YAgent, tenant, client);
        AsynchronousDispatcherInbound dispatcherInbound = new AsynchronousDispatcherInbound(objectMapper, c8YAgent,
                mappingComponent, cachedThreadPool, client, payloadProcessor);
        client.setDispatcher(dispatcherInbound);
        client.reconnect();
        client.submitHouskeeping();

        if (outputMappingEnabled) {
            // initialize AsynchronousDispatcherOutbound
            AsynchronousDispatcherOutbound dispatcherOutbound = new AsynchronousDispatcherOutbound(objectMapper,
                    c8YAgent, mappingComponent, cachedThreadPool, client, payloadProcessor);
            notificationSubscriber.addConnector(tenant, client.getConnectorIdent(), dispatcherOutbound);
            // Subscriber must be new initialmqized for the new added connector
            notificationSubscriber.notificationSubscriberReconnect(tenant);
        }
        return client;
    }

    public void shutdownConnector(String tenant, String ident) throws ConnectorRegistryException {
        connectorRegistry.unregisterClient(tenant, ident);
        if (outputMappingEnabled) {
            notificationSubscriber.removeConnector(tenant, ident);
        }
    }
}
