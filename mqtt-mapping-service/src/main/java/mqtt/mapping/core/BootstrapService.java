package mqtt.mapping.core;

import com.cumulocity.microservice.context.credentials.MicroserviceCredentials;
import com.cumulocity.microservice.subscription.model.MicroserviceSubscriptionAddedEvent;
import com.cumulocity.microservice.subscription.model.MicroserviceSubscriptionRemovedEvent;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import mqtt.mapping.configuration.ConnectorConfiguration;
import mqtt.mapping.configuration.ConnectorConfigurationComponent;
import mqtt.mapping.configuration.ServiceConfiguration;
import mqtt.mapping.configuration.ServiceConfigurationComponent;
import mqtt.mapping.connector.core.registry.ConnectorRegistry;
import mqtt.mapping.connector.core.registry.ConnectorRegistryException;
import mqtt.mapping.connector.mqtt.MQTTClient;
import mqtt.mapping.model.MappingServiceRepresentation;
import mqtt.mapping.processor.PayloadProcessor;
import mqtt.mapping.processor.inbound.BasePayloadProcessor;
import mqtt.mapping.processor.model.MappingType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;

@Service
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
        log.info("Microservice unsubscribed for tenant {}", event.getTenant());
        String tenant = event.getTenant();
        c8YAgent.getNotificationSubscriber().disconnect(tenant, false);
        try {
            connectorRegistry.unregisterAllClientsForTenant(tenant);
        } catch (ConnectorRegistryException e) {
            log.error("Error on cleaning up connector clients");
        }
    }

    @EventListener
    public void initialize(MicroserviceSubscriptionAddedEvent event) {
        //Executed for each tenant subscribed
        String tenant = event.getCredentials().getTenant();
        MicroserviceCredentials credentials = event.getCredentials();
        log.info("Event received for Tenant {}", tenant);
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"));
        ManagedObjectRepresentation mappingServiceMOR = c8YAgent.createMappingObject(tenant);
        PayloadProcessor processor = new PayloadProcessor(objectMapper, c8YAgent, tenant, null);
        c8YAgent.checkExtensions(processor);
        ServiceConfiguration serviceConfiguration = serviceConfigurationComponent.loadServiceConfiguration();
        c8YAgent.setServiceConfiguration(serviceConfiguration);
        //loadProcessorExtensions();
        MappingServiceRepresentation mappingServiceRepresentation = objectMapper.convertValue(mappingServiceMOR, MappingServiceRepresentation.class);
        mappingComponent.initializeMappingComponent(tenant, mappingServiceRepresentation);


        try {
            if (serviceConfiguration != null) {
                //TODO Add other clients - maybe dynamically per tenant
                MQTTClient mqttClient = new MQTTClient(credentials, tenant, mappingComponent, connectorConfigurationComponent, c8YAgent, cachedThreadPool, objectMapper, additionalSubscriptionIdTest);
                //mqttClient.setTenantId(tenant);
                //mqttClient.setCredentials(credentials);
                connectorRegistry.registerClient(tenant, mqttClient);
                mqttClient.submitInitialize();
                mqttClient.submitConnect();
                mqttClient.runHouskeeping();
                //Subscriptions are initiated for each tenant.
                c8YAgent.getNotificationSubscriber().init();
            }

        } catch (Exception e) {
            log.error("Error on MQTT Connection: ", e);
            //mqttClient.submitConnect();
        }
    }
}
