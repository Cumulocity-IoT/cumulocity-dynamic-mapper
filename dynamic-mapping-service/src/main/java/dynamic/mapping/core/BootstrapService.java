package dynamic.mapping.core;

import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;

import dynamic.mapping.configuration.ServiceConfiguration;
import dynamic.mapping.configuration.ServiceConfigurationComponent;
import dynamic.mapping.connector.core.client.AConnectorClient;
import dynamic.mapping.connector.core.registry.ConnectorRegistry;
import dynamic.mapping.connector.core.registry.ConnectorRegistryException;
import dynamic.mapping.model.MappingServiceRepresentation;
import dynamic.mapping.processor.PayloadProcessor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Service;

import com.cumulocity.microservice.context.credentials.Credentials;
import com.cumulocity.microservice.context.credentials.MicroserviceCredentials;
import com.cumulocity.microservice.subscription.model.MicroserviceSubscriptionAddedEvent;
import com.cumulocity.microservice.subscription.model.MicroserviceSubscriptionRemovedEvent;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;
import dynamic.mapping.configuration.ConnectorConfiguration;
import dynamic.mapping.configuration.ConnectorConfigurationComponent;
import dynamic.mapping.connector.mqtt.MQTTClient;

@Service
@EnableScheduling
@Slf4j
public class BootstrapService {

    @Autowired
    ConnectorRegistry connectorRegistry;

    @Autowired
    C8YAgent c8YAgent;

    @Autowired
    private MappingComponent mappingComponent;

    @Autowired
    ServiceConfigurationComponent serviceConfigurationComponent;

    @Autowired
    ConnectorConfigurationComponent connectorConfigurationComponent;

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
        serviceConfigurationComponent.deleteServiceConfigurations(tenant);
        connectorConfigurationComponent.deleteConnectorConfigurations(tenant);

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
        MicroserviceCredentials credentials = event.getCredentials();
        log.info("Tenant {} - Microservice subscribed", tenant);
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"));
        ManagedObjectRepresentation mappingServiceMOR = c8YAgent.createMappingServiceObject(tenant);
        PayloadProcessor processor = new PayloadProcessor(objectMapper, c8YAgent, tenant, null);
        c8YAgent.checkExtensions(tenant, processor);
        ServiceConfiguration serviceConfiguration = serviceConfigurationComponent.loadServiceConfiguration();
        c8YAgent.setServiceConfiguration(serviceConfiguration);
        c8YAgent.loadProcessorExtensions(tenant);
        MappingServiceRepresentation mappingServiceRepresentation = objectMapper.convertValue(mappingServiceMOR,
                MappingServiceRepresentation.class);
        mappingComponent.initializeMappingComponent(tenant, mappingServiceRepresentation);
        // TODO Add other clients static property definition here
        connectorRegistry.registerConnector(MQTTClient.getConnectorId(), MQTTClient.getSpec());

        try {
            if (serviceConfiguration != null) {
                List<ConnectorConfiguration> connectorConfigurationList = connectorConfigurationComponent
                        .getConnectorConfigurations(tenant);
                // For each connector configuration create a new instance of the connector
                for (ConnectorConfiguration connectorConfiguration : connectorConfigurationList) {
                    initializeConnectorByConfiguration(connectorConfiguration, credentials, tenant);
                }
            }

        } catch (Exception e) {
            log.error("Error on initializing connectors: ", e);
            // mqttClient.submitConnect();
        }
    }

    public AConnectorClient initializeConnectorByConfiguration(ConnectorConfiguration connectorConfiguration,
                                                               Credentials credentials, String tenant) throws ConnectorRegistryException {
        AConnectorClient client = null;

        if (MQTTClient.getConnectorId().equals(connectorConfiguration.getConnectorId())) {
            log.info("Tenant {} - Initializing MQTT Connector with ident {}", tenant, connectorConfiguration.getIdent());
            MQTTClient mqttClient = new MQTTClient(credentials, tenant, mappingComponent,
                    connectorConfigurationComponent, connectorConfiguration, c8YAgent, cachedThreadPool, objectMapper,
                    additionalSubscriptionIdTest);
            connectorRegistry.registerClient(tenant, mqttClient);
            mqttClient.submitInitialize();
            mqttClient.submitConnect();
            mqttClient.submitHouskeeping();
            client = mqttClient;
        }
        // Subscriber must be new initialized for the new added connector
        c8YAgent.notificationSubscriberReconnect(tenant);
        return client;
    }

    public void shutdownConnector(String tenant, String ident) throws ConnectorRegistryException {
        connectorRegistry.unregisterClient(tenant, ident);
        c8YAgent.getNotificationSubscriber().removeConnector(tenant, ident);
    }


}
