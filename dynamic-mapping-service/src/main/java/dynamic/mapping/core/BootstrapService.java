package dynamic.mapping.core;

import java.util.HashMap;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;

import dynamic.mapping.configuration.ServiceConfiguration;
import dynamic.mapping.configuration.ServiceConfigurationComponent;
import dynamic.mapping.connector.core.client.AConnectorClient;
import dynamic.mapping.connector.core.registry.ConnectorRegistry;
import dynamic.mapping.connector.core.registry.ConnectorRegistryException;
import dynamic.mapping.model.MappingServiceRepresentation;
import dynamic.mapping.processor.inbound.AsynchronousDispatcherInbound;
import dynamic.mapping.processor.outbound.AsynchronousDispatcherOutbound;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Service;

import com.cumulocity.microservice.subscription.model.MicroserviceSubscriptionAddedEvent;
import com.cumulocity.microservice.subscription.model.MicroserviceSubscriptionRemovedEvent;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;

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
    ConfigurationRegistry configurationRegistry;

    @Autowired
    private MappingComponent mappingComponent;

    @Autowired
    ServiceConfigurationComponent serviceConfigurationComponent;

    @Autowired
    ConnectorConfigurationComponent connectorConfigurationComponent;

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
        configurationRegistry.getNotificationSubscriber().disconnect(tenant, false);
        configurationRegistry.getNotificationSubscriber().deleteAllSubscriptions(tenant);

        try {
            connectorRegistry.unregisterAllClientsForTenant(tenant);
        } catch (ConnectorRegistryException e) {
            log.error("Tenant {} - Error on cleaning up connector clients", event.getTenant());
        }

        // delete configurations
        configurationRegistry.getServiceConfigurations().remove(tenant);
        configurationRegistry.getMappingServiceRepresentations().remove(tenant);
        mappingComponent.cleanMappingStatus(tenant);
        configurationRegistry.getPayloadProcessorsInbound().remove(tenant);
        configurationRegistry.getPayloadProcessorsOutbound().remove(tenant);
    }

    @EventListener
    public void initialize(MicroserviceSubscriptionAddedEvent event) {
        // Executed for each tenant subscribed
        String tenant = event.getCredentials().getTenant();
        log.info("Tenant {} - Microservice subscribed", tenant);
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"));
        ManagedObjectRepresentation mappingServiceMOR = configurationRegistry.getC8yAgent()
                .initializeMappingServiceObject(tenant);

        ServiceConfiguration serviceConfiguration = serviceConfigurationComponent.getServiceConfiguration(tenant);
        configurationRegistry.getServiceConfigurations().put(tenant, serviceConfiguration);
        configurationRegistry.getC8yAgent().createExtensibleProsessor(tenant);
        configurationRegistry.getC8yAgent().loadProcessorExtensions(tenant);

        MappingServiceRepresentation mappingServiceRepresentation = configurationRegistry.getObjectMapper()
                .convertValue(mappingServiceMOR,
                        MappingServiceRepresentation.class);
        configurationRegistry.getMappingServiceRepresentations().put(tenant, mappingServiceRepresentation);
        mappingComponent.initializeMappingStatus(tenant, false);
        mappingComponent.initializeMappingCaches(tenant);

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
            log.error("Tenant {} - Error on initializing connectors: ", tenant, e);
            // mqttClient.submitConnect();
        }

        log.info("Tenant {} - OutputMapping Config Enabled: {}", tenant, outputMappingEnabled);
        if (outputMappingEnabled) {
            configurationRegistry.getNotificationSubscriber().initTenantClient();
            configurationRegistry.getNotificationSubscriber().initDeviceClient();
        }
    }

    public AConnectorClient initializeConnectorByConfiguration(ConnectorConfiguration connectorConfiguration,
            ServiceConfiguration serviceConfiguration, String tenant) throws ConnectorRegistryException {
        AConnectorClient connectorClient = null;

        if (MQTTClient.getConnectorType().equals(connectorConfiguration.getConnectorType())) {
            log.info("Tenant {} - Initializing MQTT Connector with ident {}", tenant,
                    connectorConfiguration.getIdent());
            MQTTClient mqttClient = new MQTTClient(configurationRegistry,
                    mappingComponent,
                    connectorConfigurationComponent, serviceConfigurationComponent, connectorConfiguration,
                    cachedThreadPool,
                    null,
                    additionalSubscriptionIdTest, tenant);

            connectorRegistry.registerClient(tenant, mqttClient);
            connectorClient = mqttClient;
        }

        // initialize AsynchronousDispatcherInbound
        AsynchronousDispatcherInbound dispatcherInbound = new AsynchronousDispatcherInbound(configurationRegistry,
                mappingComponent, cachedThreadPool, connectorClient);
        configurationRegistry.initializePayloadProcessorsInbound(tenant);
        connectorClient.setDispatcher(dispatcherInbound);
        connectorClient.reconnect();
        connectorClient.submitHouskeeping();

        if (outputMappingEnabled) {
            // initialize AsynchronousDispatcherOutbound
            configurationRegistry.initializePayloadProcessorsOutbound(connectorClient);
            AsynchronousDispatcherOutbound dispatcherOutbound = new AsynchronousDispatcherOutbound(
                    configurationRegistry, mappingComponent, cachedThreadPool, connectorClient);
            configurationRegistry.getNotificationSubscriber().addSubscriber(tenant, connectorClient.getConnectorIdent(),
                    dispatcherOutbound);
            // Subscriber must be new initialized for the new added connector
            configurationRegistry.getNotificationSubscriber().notificationSubscriberReconnect(tenant);

        }
        return connectorClient;
    }

    public void shutdownConnector(String tenant, String ident) throws ConnectorRegistryException {
        connectorRegistry.unregisterClient(tenant, ident);
        if (outputMappingEnabled) {
            configurationRegistry.getNotificationSubscriber().removeConnector(tenant, ident);
        }
    }
}
