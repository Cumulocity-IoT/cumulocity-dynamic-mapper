package dynamic.mapping.core;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;

import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;
import com.fasterxml.jackson.core.JsonProcessingException;
import dynamic.mapping.configuration.ServiceConfiguration;
import dynamic.mapping.configuration.ServiceConfigurationComponent;
import dynamic.mapping.connector.core.client.AConnectorClient;
import dynamic.mapping.connector.core.client.ConnectorType;
import dynamic.mapping.connector.core.registry.ConnectorRegistry;
import dynamic.mapping.connector.core.registry.ConnectorRegistryException;
import dynamic.mapping.connector.kafka.KafkaClient;
import dynamic.mapping.model.MappingServiceRepresentation;
import dynamic.mapping.processor.inbound.DispatcherInbound;
import dynamic.mapping.processor.outbound.DispatcherOutbound;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.cumulocity.microservice.subscription.model.MicroserviceSubscriptionAddedEvent;
import com.cumulocity.microservice.subscription.model.MicroserviceSubscriptionRemovedEvent;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;

import lombok.extern.slf4j.Slf4j;
import dynamic.mapping.configuration.ConnectorConfiguration;
import dynamic.mapping.configuration.ConnectorConfigurationComponent;
import dynamic.mapping.connector.mqtt.MQTTClient;
import dynamic.mapping.connector.mqtt.MQTTServiceClient;

import jakarta.annotation.PreDestroy;

@Service
@EnableScheduling
@Slf4j
public class BootstrapService {

	@Autowired
	ConnectorRegistry connectorRegistry;

	@Autowired
	ConfigurationRegistry configurationRegistry;

	@Autowired
	C8YAgent c8YAgent;

	@Autowired
	private MappingComponent mappingComponent;

	@Autowired
	ServiceConfigurationComponent serviceConfigurationComponent;

	@Autowired
	ConnectorConfigurationComponent connectorConfigurationComponent;

	@Qualifier("virtThreadPool")
	private ExecutorService virtThreadPool;

	@Autowired
	public void setVirtThreadPool(ExecutorService virtThreadPool) {
		this.virtThreadPool = virtThreadPool;
	}

	@Value("${APP.additionalSubscriptionIdTest}")
	private String additionalSubscriptionIdTest;

	@Value("#{new Integer('${APP.inboundExternalIdCacheSize}')}")
	private Integer inboundExternalIdCacheSize;

	@Autowired
	private MicroserviceSubscriptionsService subscriptionsService;

	private HashMap<String, Instant> cacheRetentionStartMap = new HashMap<>();

	@PreDestroy
	public void destroy() {
		log.info("Shutting down mapper...");
		subscriptionsService.runForEachTenant(() -> {
			String tenant = subscriptionsService.getTenant();
			configurationRegistry.getNotificationSubscriber().disconnect(tenant);
		});
	}

	@EventListener
	public void unsubscribeTenant(MicroserviceSubscriptionRemovedEvent event) {
		log.info("Tenant {} - Microservice unsubscribed", event.getTenant());
		String tenant = event.getTenant();
		configurationRegistry.getNotificationSubscriber().disconnect(tenant);
		configurationRegistry.getNotificationSubscriber().unsubscribeDeviceSubscriber(tenant);

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

		// delete cache
		c8YAgent.deleteInboundExternalIdCache(tenant);
	}

	@EventListener
	public void initialize(MicroserviceSubscriptionAddedEvent event) {
		// Executed for each tenant subscribed
		String tenant = event.getCredentials().getTenant();
		log.info("Tenant {} - Microservice subscribed", tenant);
		configurationRegistry.getMicroserviceCredentials().put(tenant, event.getCredentials());

		ServiceConfiguration serviceConfiguration = serviceConfigurationComponent.getServiceConfiguration(tenant);
		var cacheSize = inboundExternalIdCacheSize;
		boolean saveServiceConfiguration = false;
		if (serviceConfiguration.inboundExternalIdCacheSize != null
				&& serviceConfiguration.inboundExternalIdCacheSize.intValue() != 0) {
			cacheSize = serviceConfiguration.inboundExternalIdCacheSize.intValue();
		} else if (serviceConfiguration.inboundExternalIdCacheSize != null
				&& serviceConfiguration.inboundExternalIdCacheSize.intValue() == 0) {
			serviceConfiguration.inboundExternalIdCacheSize = inboundExternalIdCacheSize;
			saveServiceConfiguration = true;
		}
		if (serviceConfiguration.inboundExternalIdCacheRetention == null) {
			serviceConfiguration.inboundExternalIdCacheSize = 1;
			saveServiceConfiguration = true;
		}
		if (saveServiceConfiguration) {
			try {
				serviceConfigurationComponent.saveServiceConfiguration(serviceConfiguration);
			} catch (JsonProcessingException e) {
				log.error("Tenant {} - Error saving service configuration: {}", tenant, e.getMessage());
			}
		}
		cacheRetentionStartMap.put(tenant, Instant.now());

		c8YAgent.initializeInboundExternalIdCache(tenant, cacheSize);
		TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"));
		ManagedObjectRepresentation mappingServiceMOR = configurationRegistry.getC8yAgent()
				.initializeMappingServiceObject(tenant);

		configurationRegistry.getServiceConfigurations().put(tenant, serviceConfiguration);
		configurationRegistry.getC8yAgent().createExtensibleProcessor(tenant);
		configurationRegistry.getC8yAgent().loadProcessorExtensions(tenant);

		MappingServiceRepresentation mappingServiceRepresentation = configurationRegistry.getObjectMapper()
				.convertValue(mappingServiceMOR,
						MappingServiceRepresentation.class);
		configurationRegistry.getMappingServiceRepresentations().put(tenant, mappingServiceRepresentation);
		mappingComponent.initializeMappingStatus(tenant, false);
		mappingComponent.initializeDeploymentMap(tenant, false);
		mappingComponent.initializeMappingCaches(tenant);
		mappingComponent.rebuildMappingOutboundCache(tenant);
		mappingComponent.rebuildMappingInboundCache(tenant);

		try {
			// TODO Add other clients static property definition here
			if (connectorRegistry.getConnectorStatusMap().get(tenant) == null) {
				connectorRegistry.getConnectorStatusMap().put(tenant, new HashMap<String, ConnectorStatusEvent>());
			}
			connectorRegistry.registerConnector(ConnectorType.MQTT, new MQTTClient().getConnectorSpecification());
			connectorRegistry.registerConnector(ConnectorType.CUMULOCITY_MQTT_SERVICE,
					new MQTTServiceClient().getConnectorSpecification());
			connectorRegistry.registerConnector(ConnectorType.KAFKA, new KafkaClient().getConnectorSpecification());
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

		log.info("Tenant {} - OutputMapping Config Enabled: {}", tenant,
				serviceConfiguration.isOutboundMappingEnabled());
		if (serviceConfiguration.isOutboundMappingEnabled()) {
			if (!configurationRegistry.getNotificationSubscriber().isNotificationServiceAvailable(tenant)) {
				try {
					serviceConfiguration.setOutboundMappingEnabled(false);
					serviceConfigurationComponent.saveServiceConfiguration(serviceConfiguration);
				} catch (JsonProcessingException e) {
					log.error("Tenant {} - Error saving service configuration: {}", tenant, e.getMessage());
				}
			} else {
				configurationRegistry.getNotificationSubscriber().initDeviceClient();
			}
		}
	}

	public AConnectorClient initializeConnectorByConfiguration(ConnectorConfiguration connectorConfiguration,
			ServiceConfiguration serviceConfiguration, String tenant) throws ConnectorRegistryException {
		AConnectorClient connectorClient = null;
		if (connectorConfiguration.isEnabled()) {
			try {
				connectorClient = configurationRegistry.createConnectorClient(connectorConfiguration,
						additionalSubscriptionIdTest, tenant);
			} catch (IOException e) {
				log.error("Tenant {} - Error on creating connector {} {}", connectorConfiguration.getConnectorType(),
						e);
				throw new ConnectorRegistryException(e.getMessage());
			}
			connectorRegistry.registerClient(tenant, connectorClient);
			// initialize AsynchronousDispatcherInbound
			DispatcherInbound dispatcherInbound = new DispatcherInbound(configurationRegistry,
					connectorClient);
			configurationRegistry.initializePayloadProcessorsInbound(tenant);
			connectorClient.setDispatcher(dispatcherInbound);
			connectorClient.reconnect();
			connectorClient.submitHousekeeping();
			initializeOutboundMapping(tenant, serviceConfiguration, connectorClient);
		}
		return connectorClient;
	}

	public void initializeOutboundMapping(String tenant, ServiceConfiguration serviceConfiguration,
			AConnectorClient connectorClient) {
		if (serviceConfiguration.isOutboundMappingEnabled()) {
			// initialize AsynchronousDispatcherOutbound
			configurationRegistry.initializePayloadProcessorsOutbound(connectorClient);
			DispatcherOutbound dispatcherOutbound = new DispatcherOutbound(
					configurationRegistry, connectorClient);
			// Only initialize Connectors which are enabled
			if (connectorClient.getConnectorConfiguration().isEnabled())
				configurationRegistry.getNotificationSubscriber().addConnector(tenant,
						connectorClient.getConnectorIdent(),
						dispatcherOutbound);
			// Subscriber must be new initialized for the new added connector
			// configurationRegistry.getNotificationSubscriber().notificationSubscriberReconnect(tenant);

		}
	}

	// shutdownAndRemoveConnector will unsubscribe the subscriber which drops all
	// queues
	public void shutdownAndRemoveConnector(String tenant, String connectorIdentifier) throws ConnectorRegistryException {
		// connectorRegistry.unregisterClient(tenant, connectorIdentifier);
		ServiceConfiguration serviceConfiguration = serviceConfigurationComponent.getServiceConfiguration(tenant);
		if (serviceConfiguration.isOutboundMappingEnabled()) {
			configurationRegistry.getNotificationSubscriber().unsubscribeDeviceSubscriberByConnector(tenant,
					connectorIdentifier);
			configurationRegistry.getNotificationSubscriber().removeConnector(tenant, connectorIdentifier);
		}
	}

	// DisableConnector will just clean-up maps and disconnects Notification 2.0 -
	// queues will be kept
	public void disableConnector(String tenant, String connectorIdentifier) throws ConnectorRegistryException {
		connectorRegistry.unregisterClient(tenant, connectorIdentifier);
		ServiceConfiguration serviceConfiguration = serviceConfigurationComponent.getServiceConfiguration(tenant);
		if (serviceConfiguration.isOutboundMappingEnabled()) {
			configurationRegistry.getNotificationSubscriber().removeConnector(tenant, connectorIdentifier);
		}
	}

	@Scheduled(cron = "0 * * * * *")
	public void cleanUpCaches() {
		subscriptionsService.runForEachTenant(() -> {
			String tenant = subscriptionsService.getTenant();
			if (cacheRetentionStartMap.get(tenant) != null) {
				Instant cacheRetentionStart = cacheRetentionStartMap.get(tenant);
				ServiceConfiguration serviceConfiguration = serviceConfigurationComponent
						.getServiceConfiguration(tenant);
				int inboundCacheRetention = serviceConfiguration.getInboundExternalIdCacheRetention();
				int cacheSize = Integer.valueOf(c8YAgent.getInboundExternalIdCache(tenant).getCacheSize());
				if (inboundCacheRetention > 0 && Duration.between(cacheRetentionStart, Instant.now())
						.getSeconds() >= Duration.ofDays(inboundCacheRetention).getSeconds()) {
					c8YAgent.clearInboundExternalIdCache(tenant, false, cacheSize);
					cacheRetentionStartMap.put(tenant, Instant.now());
					log.info("Tenant {} - Identity Cache cleared by scheduler. Old Size: {}, New size: {}", tenant,
							cacheSize, c8YAgent.getInboundExternalIdCache(tenant).getCacheSize());
				}
			}
		});
	}
}
