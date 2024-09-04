/*
 * Copyright (c) 2022 Software AG, Darmstadt, Germany and/or Software AG USA Inc., Reston, VA, USA,
 * and/or its subsidiaries and/or its affiliates and/or their licensors.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * @authors Christof Strack, Stefan Witschel
 */

package dynamic.mapping.rest;

import java.net.URI;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collectors;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;

import dynamic.mapping.configuration.ConnectorConfiguration;
import dynamic.mapping.configuration.ConnectorConfigurationComponent;
import dynamic.mapping.configuration.ServiceConfiguration;
import dynamic.mapping.configuration.ServiceConfigurationComponent;

import dynamic.mapping.connector.core.ConnectorSpecification;
import dynamic.mapping.connector.core.client.AConnectorClient;
import dynamic.mapping.connector.core.client.ConnectorType;
import dynamic.mapping.connector.core.registry.ConnectorRegistry;
import dynamic.mapping.connector.core.registry.ConnectorRegistryException;
import dynamic.mapping.processor.model.ProcessingContext;
import org.apache.commons.lang3.mutable.MutableInt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.cumulocity.microservice.context.ContextService;
import com.cumulocity.microservice.context.credentials.UserCredentials;
import com.cumulocity.microservice.security.service.RoleService;
import com.fasterxml.jackson.core.JsonProcessingException;

import lombok.extern.slf4j.Slf4j;
import dynamic.mapping.core.BootstrapService;
import dynamic.mapping.core.ConfigurationRegistry;
import dynamic.mapping.core.ConnectorStatusEvent;
import dynamic.mapping.core.MappingComponent;
import dynamic.mapping.core.Operation;
import dynamic.mapping.core.ServiceOperation;
import dynamic.mapping.model.Direction;
import dynamic.mapping.model.Extension;
import dynamic.mapping.model.Feature;
import dynamic.mapping.model.MappingTreeNode;
import dynamic.mapping.model.SnoopStatus;
import dynamic.mapping.model.Mapping;
import dynamic.mapping.model.MappingStatus;
import dynamic.mapping.model.DeploymentMapEntryDetailed;

@Slf4j
@RestController
public class MappingRestController {

	@Autowired
	ConnectorRegistry connectorRegistry;

	@Autowired
	MappingComponent mappingComponent;

	@Autowired
	ConnectorConfigurationComponent connectorConfigurationComponent;

	@Autowired
	ServiceConfigurationComponent serviceConfigurationComponent;

	@Autowired
	BootstrapService bootstrapService;

	@Autowired
	private RoleService roleService;

	@Autowired
	private ContextService<UserCredentials> contextService;

	@Autowired
	private ConfigurationRegistry configurationRegistry;

	@Autowired
	private MappingComponent mappingStatusComponent;

	@Value("${APP.externalExtensionsEnabled}")
	private boolean externalExtensionsEnabled;

	@Value("${APP.userRolesEnabled}")
	private Boolean userRolesEnabled;

	@Value("${APP.mappingAdminRole}")
	private String mappingAdminRole;

	@Value("${APP.mappingCreateRole}")
	private String mappingCreateRole;

	@RequestMapping(value = "/feature", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<Feature> getFeatures() {
		String tenant = contextService.getContext().getTenant();
		ServiceConfiguration serviceConfiguration = serviceConfigurationComponent.getServiceConfiguration(tenant);
		log.debug("Tenant {} - Get Feature status", tenant);
		Feature feature = new Feature();
		feature.setOutputMappingEnabled(serviceConfiguration.isOutboundMappingEnabled());
		feature.setExternalExtensionsEnabled(externalExtensionsEnabled);
		feature.setUserHasMappingCreateRole(userHasMappingCreateRole());
		feature.setUserHasMappingAdminRole(userHasMappingAdminRole());
		return new ResponseEntity<Feature>(feature, HttpStatus.OK);
	}

	@RequestMapping(value = "/configuration/connector/specifications", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<List<ConnectorSpecification>> getConnectorSpecifications() {
		String tenant = contextService.getContext().getTenant();
		List<ConnectorSpecification> connectorConfigurations = new ArrayList<>();
		log.info("Tenant {} - Getting connection properties...", tenant);
		Map<ConnectorType, ConnectorSpecification> spec = connectorRegistry
				.getConnectorSpecifications();
		// Iterate over all connectors
		for (ConnectorType connectorType : spec.keySet()) {
			connectorConfigurations.add(spec.get(connectorType));
		}
		return ResponseEntity.ok(connectorConfigurations);
	}

	@RequestMapping(value = "/configuration/connector/instance", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<HttpStatus> createConnectorConfiguration(
			@Valid @RequestBody ConnectorConfiguration configuration) {
		String tenant = contextService.getContext().getTenant();
		if (!userHasMappingAdminRole()) {
			log.error("Tenant {} - Insufficient Permission, user does not have required permission to access this API",
					tenant);
			throw new ResponseStatusException(HttpStatus.FORBIDDEN,
					"Insufficient Permission, user does not have required permission to access this API");
		}
		// Remove sensitive data before printing to log
		ConnectorSpecification connectorSpecification = connectorRegistry
		.getConnectorSpecification(configuration.connectorType);
		ConnectorConfiguration clonedConfig = configuration.getCleanedConfig(connectorSpecification);
		log.info("Tenant {} - Post Connector configuration: {}", tenant, clonedConfig.toString());
		try {
			connectorConfigurationComponent.saveConnectorConfiguration(configuration);
			return ResponseEntity.status(HttpStatus.CREATED).build();
		} catch (Exception ex) {
			log.error("Tenant {} - Error getting mqtt broker configuration: ", tenant, ex);
			throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getLocalizedMessage());
		}
	}

	@RequestMapping(value = "/configuration/connector/instances", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<List<ConnectorConfiguration>> getConnectionConfigurations() {
		String tenant = contextService.getContext().getTenant();
		log.debug("Tenant {} - Get connection details", tenant);

		try {
			List<ConnectorConfiguration> configurations = connectorConfigurationComponent
					.getConnectorConfigurations(tenant);
			List<ConnectorConfiguration> modifiedConfigs = new ArrayList<>();

			// Remove sensitive data before sending to UI
			for (ConnectorConfiguration config : configurations) {
				ConnectorSpecification connectorSpecification = connectorRegistry
				.getConnectorSpecification(config.connectorType);
				ConnectorConfiguration cleanedConfig = config.getCleanedConfig(connectorSpecification);
				modifiedConfigs.add(cleanedConfig);
			}
			return ResponseEntity.ok(modifiedConfigs);
		} catch (Exception ex) {
			log.error("Tenant {} - Error on loading configuration {}", tenant, ex);
			throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getLocalizedMessage());
		}
	}

	@RequestMapping(value = "/configuration/connector/instance/{ident}", method = RequestMethod.DELETE, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<String> deleteConnectionConfiguration(@PathVariable String ident) {
		String tenant = contextService.getContext().getTenant();
		log.info("Tenant {} - Delete connection instance {}", tenant, ident);
		if (!userHasMappingAdminRole()) {
			log.error("Tenant {} - Insufficient Permission, user does not have required permission to access this API",
					tenant);
			throw new ResponseStatusException(HttpStatus.FORBIDDEN,
					"Insufficient Permission, user does not have required permission to access this API");
		}
		try {
			ConnectorConfiguration configuration = connectorConfigurationComponent.getConnectorConfiguration(ident,
					tenant);
			AConnectorClient client = connectorRegistry.getClientForTenant(tenant,
					configuration.getIdent());
			if (client == null)
				return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Client with ident " + ident + " not found");
			client.disconnect();
			bootstrapService.shutdownAndRemoveConnector(tenant, client.getConnectorIdent());
			connectorConfigurationComponent.deleteConnectorConfiguration(ident);
			mappingComponent.removeConnectorFromDeploymentMap(tenant, ident);
		} catch (Exception ex) {
			log.error("Tenant {} - Error getting mqtt broker configuration {}", tenant, ex);
			throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getLocalizedMessage());
		}
		return ResponseEntity.status(HttpStatus.OK).body(ident);
	}

	@RequestMapping(value = "/configuration/connector/instance/{ident}", method = RequestMethod.PUT, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<ConnectorConfiguration> updateConnectionConfiguration(@PathVariable String ident,
			@Valid @RequestBody ConnectorConfiguration configuration) {
		String tenant = contextService.getContext().getTenant();
		log.info("Tenant {} - Update connection instance {}", tenant, ident);
		// make sure we are using the correct ident
		configuration.ident = ident;

		if (!userHasMappingAdminRole()) {
			log.error("Tenant {} - Insufficient Permission, user does not have required permission to access this API",
					tenant);
			throw new ResponseStatusException(HttpStatus.FORBIDDEN,
					"Insufficient Permission, user does not have required permission to access this API");
		}
		// Remove sensitive data before printing to log
		ConnectorSpecification connectorSpecification = connectorRegistry
		.getConnectorSpecification(configuration.connectorType);
		ConnectorConfiguration clonedConfig = configuration.getCleanedConfig(connectorSpecification);
		log.info("Tenant {} - Post Connector configuration: {}", tenant, clonedConfig.toString());
		try {
			// check if password filed was touched, e.g. != "****", then use password from
			// new payload, otherwise copy password from previously saved configuration
			ConnectorConfiguration originalConfiguration = connectorConfigurationComponent
					.getConnectorConfiguration(configuration.ident, tenant);

			for (String property : configuration.getProperties().keySet()) {
				if (connectorSpecification.isPropertySensitive(property)
						&& configuration.getProperties().get(property).equals("****")) {
					// retrieve the existing value
					log.info(
							"Tenant {} - Copy property {} from existing configuration, since it was not touched and is sensitive.",
							property);
					configuration.getProperties().put(property,
							originalConfiguration.getProperties().get(property));
				}
			}
			connectorConfigurationComponent.saveConnectorConfiguration(configuration);
			AConnectorClient client = connectorRegistry.getClientForTenant(tenant,
					configuration.getIdent());
			client.reconnect();
		} catch (Exception ex) {
			log.error("Tenant {} - Error getting mqtt broker configuration {}", tenant, ex);
			throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getLocalizedMessage());
		}
		return ResponseEntity.status(HttpStatus.CREATED).body(configuration);
	}

	@RequestMapping(value = "/configuration/service", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<ServiceConfiguration> getServiceConfiguration() {
		String tenant = contextService.getContext().getTenant();
		log.info("Tenant {} - Get connection details", tenant);

		try {
			final ServiceConfiguration configuration = serviceConfigurationComponent.getServiceConfiguration(tenant);
			if (configuration == null) {
				throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Service connection not available");
			}
			// don't modify original copy
			return new ResponseEntity<ServiceConfiguration>(configuration, HttpStatus.OK);
		} catch (Exception ex) {
			log.error("Tenant {} - Error on loading configuration {}", tenant, ex);
			throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getLocalizedMessage());
		}
	}

	@RequestMapping(value = "/configuration/service", method = RequestMethod.PUT, consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<HttpStatus> configureConnectionToBroker(
			@Valid @RequestBody ServiceConfiguration configuration) {
		String tenant = contextService.getContext().getTenant();
		// don't modify original copy
		log.info("Tenant {} - Post service configuration: {}", tenant, configuration.toString());

		if (!userHasMappingAdminRole()) {
			log.error("Tenant {} - Insufficient Permission, user does not have required permission to access this API",
					tenant);
			throw new ResponseStatusException(HttpStatus.FORBIDDEN,
					"Insufficient Permission, user does not have required permission to access this API");
		}

		try {
			serviceConfigurationComponent.saveServiceConfiguration(configuration);
			if (!configuration.isOutboundMappingEnabled()
					&& configurationRegistry.getNotificationSubscriber().getDeviceConnectionStatus(tenant) == 200) {
				configurationRegistry.getNotificationSubscriber().disconnect(tenant);
			} else if (configurationRegistry.getNotificationSubscriber().getDeviceConnectionStatus(tenant) == null
					|| configurationRegistry.getNotificationSubscriber().getDeviceConnectionStatus(tenant) == null
							&& configurationRegistry.getNotificationSubscriber()
									.getDeviceConnectionStatus(tenant) != 200) {
				List<ConnectorConfiguration> connectorConfigurationList = connectorConfigurationComponent
						.getConnectorConfigurations(tenant);
				for (ConnectorConfiguration connectorConfiguration : connectorConfigurationList) {
					bootstrapService.initializeConnectorByConfiguration(connectorConfiguration, configuration, tenant);
				}
				configurationRegistry.getNotificationSubscriber().initDeviceClient();
			}

			configurationRegistry.getServiceConfigurations().put(tenant, configuration);
			return ResponseEntity.status(HttpStatus.CREATED).build();
		} catch (Exception ex) {
			log.error("Tenant {} - Error getting mqtt broker configuration {}", tenant, ex);
			throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getLocalizedMessage());
		}
	}

	@RequestMapping(value = "/deployment/effective", method = RequestMethod.GET, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<Map<String, DeploymentMapEntryDetailed>> getMappingsDeployed() {
		String tenant = contextService.getContext().getTenant();
		Map<String, DeploymentMapEntryDetailed> mappingsDeployed = new HashMap<>();
		try {
			Map<String, AConnectorClient> connectorMap = connectorRegistry
					.getClientsForTenant(tenant);
			if (connectorMap != null) {
				// iterate over all clients
				for (AConnectorClient client : connectorMap.values()) {
					client.collectSubscribedMappingsAll(mappingsDeployed);
				}
			}

			log.debug("Tenant {} - Get active subscriptions!", tenant);
			return ResponseEntity.status(HttpStatus.OK).body(mappingsDeployed);
		} catch (ConnectorRegistryException e) {
			throw new RuntimeException(e);
		}

	}

	@RequestMapping(value = "/deployment/defined/{mappingIdent}", method = RequestMethod.PUT, consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<HttpStatus> updateDeploymentMapEntry(@PathVariable String mappingIdent,
			@Valid @RequestBody List<String> deployment) {
		String tenant = contextService.getContext().getTenant();
		log.info("Tenant {} - Update deployment for mapping: {} : {}", tenant, mappingIdent, deployment);
		try {
			mappingComponent.updateDeploymentMapEntry(tenant, mappingIdent, deployment);
			return ResponseEntity.status(HttpStatus.CREATED).build();
		} catch (Exception ex) {
			log.error("Tenant {} - Error updating deployment for mapping: {}", tenant, mappingIdent, ex);
			throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getLocalizedMessage());
		}
	}

	@RequestMapping(value = "/deployment/defined/{mappingIdent}", method = RequestMethod.GET, consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<List<String>> getDeploymentMapEntry(@PathVariable String mappingIdent) {
		String tenant = contextService.getContext().getTenant();
		log.info("Tenant {} - Get deployment for mapping: {}", tenant, mappingIdent);
		try {
			List<String> map = mappingComponent.getDeploymentMapEntry(tenant, mappingIdent);
			return new ResponseEntity<List<String>>(map, HttpStatus.OK);
		} catch (Exception ex) {
			log.error("Tenant {} - Error getting deployment for mapping: {}", tenant, mappingIdent, ex);
			throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getLocalizedMessage());
		}
	}

	@RequestMapping(value = "/deployment/defined", method = RequestMethod.GET, consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<Map<String, List<String>>> getDeploymentMap() {
		String tenant = contextService.getContext().getTenant();
		log.info("Tenant {} - Get complete deployment", tenant);
		try {
			Map<String, List<String>> map = mappingComponent.getDeploymentMap(tenant);
			return new ResponseEntity<Map<String, List<String>>>(map, HttpStatus.OK);
		} catch (Exception ex) {
			log.error("Tenant {} - Error getting complete deployment!", tenant, ex);
			throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getLocalizedMessage());
		}
	}

	@RequestMapping(value = "/operation", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<HttpStatus> runOperation(@Valid @RequestBody ServiceOperation operation) {
		String tenant = contextService.getContext().getTenant();
		log.info("Tenant {} - Post operation: {}", tenant, operation.toString());
		try {
			if (operation.getOperation().equals(Operation.RELOAD_MAPPINGS)) {
				mappingComponent.rebuildMappingOutboundCache(tenant);
				// in order to keep MappingInboundCache and ActiveSubscriptionMappingInbound in
				// sync, the ActiveSubscriptionMappingInbound is build on the
				// previously used updatedMappings

				List<Mapping> updatedMappingsInbound = mappingComponent.rebuildMappingInboundCache(tenant);
				Map<String, AConnectorClient> connectorMap = connectorRegistry
						.getClientsForTenant(tenant);
				for (AConnectorClient client : connectorMap.values()) {
					client.updateActiveSubscriptionsInbound(updatedMappingsInbound, false);
				}
				List<Mapping> updatedMappingsOutbound = mappingComponent.rebuildMappingOutboundCache(tenant);

				for (AConnectorClient client : connectorMap.values()) {
					client.updateActiveSubscriptionsOutbound(updatedMappingsOutbound);
				}
			} else if (operation.getOperation().equals(Operation.CONNECT)) {
				String connectorIdent = operation.getParameter().get("connectorIdent");
				ConnectorConfiguration configuration = connectorConfigurationComponent
						.getConnectorConfiguration(connectorIdent, tenant);
				configuration.setEnabled(true);
				connectorConfigurationComponent.saveConnectorConfiguration(configuration);

				//Initialize Connector only when enabled.
				ServiceConfiguration serviceConfiguration = serviceConfigurationComponent.getServiceConfiguration(tenant);
				bootstrapService.initializeConnectorByConfiguration(configuration, serviceConfiguration, tenant);
				configurationRegistry.getNotificationSubscriber().notificationSubscriberReconnect(tenant);

				AConnectorClient client = connectorRegistry.getClientForTenant(tenant,
						connectorIdent);

				client.submitConnect();
			} else if (operation.getOperation().equals(Operation.DISCONNECT)) {
				String connectorIdent = operation.getParameter().get("connectorIdent");
				ConnectorConfiguration configuration = connectorConfigurationComponent
						.getConnectorConfiguration(connectorIdent, tenant);
				configuration.setEnabled(false);
				connectorConfigurationComponent.saveConnectorConfiguration(configuration);

				AConnectorClient client = connectorRegistry.getClientForTenant(tenant,
						connectorIdent);
				//client.submitDisconnect();
				bootstrapService.disableConnector(tenant, client.getConnectorIdent());
				//We might need to Reconnect other Notification Clients for other connectors
				configurationRegistry.getNotificationSubscriber().notificationSubscriberReconnect(tenant);
			} else if (operation.getOperation().equals(Operation.REFRESH_STATUS_MAPPING)) {
				mappingComponent.sendMappingStatus(tenant);
			} else if (operation.getOperation().equals(Operation.RESET_STATUS_MAPPING)) {
				mappingComponent.initializeMappingStatus(tenant, true);
			} else if (operation.getOperation().equals(Operation.RESET_DEPLOYMENT_MAP)) {
				mappingComponent.initializeMappingStatus(tenant, true);
			} else if (operation.getOperation().equals(Operation.RELOAD_EXTENSIONS)) {
				configurationRegistry.getC8yAgent().reloadExtensions(tenant);
			} else if (operation.getOperation().equals(Operation.ACTIVATE_MAPPING)) {
				// activate/deactivate mapping
				String mappingId = operation.getParameter().get("id");
				Boolean activeBoolean = Boolean.parseBoolean(operation.getParameter().get("active"));
				Mapping updatedMapping = mappingComponent.setActivationMapping(tenant, mappingId, activeBoolean);
				Map<String, AConnectorClient> connectorMap = connectorRegistry
						.getClientsForTenant(tenant);
				// subscribe/unsubscribe respective subscriptionTopic of mapping only for
				// outbound mapping
				for (AConnectorClient client : connectorMap.values()) {
					if (updatedMapping.direction == Direction.INBOUND) {
						client.updateActiveSubscriptionInbound(updatedMapping, false, true);
					} else {
						client.updateActiveSubscriptionOutbound(updatedMapping);
					}
				}
			} else if (operation.getOperation().equals(Operation.DEBUG_MAPPING)) {
				String id = operation.getParameter().get("id");
				Boolean debugBoolean = Boolean.parseBoolean(operation.getParameter().get("debug"));
				mappingComponent.setDebugMapping(tenant, id, debugBoolean);
			} else if (operation.getOperation().equals(Operation.SNOOP_MAPPING)) {
				String id = operation.getParameter().get("id");
				SnoopStatus newSnoop = SnoopStatus.valueOf(operation.getParameter().get("snoopStatus"));
				mappingComponent.setSnoopStatusMapping(tenant, id, newSnoop);
			} else if (operation.getOperation().equals(Operation.SNOOP_RESET)) {
				String id = operation.getParameter().get("id");
				mappingComponent.resetSnoop(tenant, id);
			} else if (operation.getOperation().equals(Operation.REFRESH_NOTIFICATIONS_SUBSCRIPTIONS)) {
				configurationRegistry.getNotificationSubscriber().notificationSubscriberReconnect(tenant);
			}
			return ResponseEntity.status(HttpStatus.CREATED).build();
		} catch (Exception ex) {
			log.error("Tenant {} - Error getting mqtt broker configuration {}", tenant, ex);
			throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getLocalizedMessage());
		}
	}

	@RequestMapping(value = "/monitoring/status/connector/{connectorIdent}", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<ConnectorStatusEvent> getConnectorStatus(@PathVariable @NotNull String connectorIdent) {
		try {
			String tenant = contextService.getContext().getTenant();
			AConnectorClient client = connectorRegistry.getClientForTenant(tenant,
					connectorIdent);
			ConnectorStatusEvent st = client.getConnectorStatus();
			log.info("Tenant {} - Get status for connector {}: {}", tenant, connectorIdent, st);
			return new ResponseEntity<>(st, HttpStatus.OK);
		} catch (ConnectorRegistryException e) {
			throw new RuntimeException(e);
		}
	}

	@RequestMapping(value = "/monitoring/status/connectors", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<Map<String, ConnectorStatusEvent>> getConnectorsStatus() {
		Map<String, ConnectorStatusEvent> connectorsStatus = new HashMap<>();
		String tenant = contextService.getContext().getTenant();
		try {
			Map<String, AConnectorClient> connectorMap = connectorRegistry
					.getClientsForTenant(tenant);
			if (connectorMap != null) {
				for (AConnectorClient client : connectorMap.values()) {
					ConnectorStatusEvent st = client.getConnectorStatus();
					connectorsStatus.put(client.getConnectorIdent(), st);
				}
			}
			log.info("Tenant {} - Get status of connectors: {}", tenant, connectorsStatus);
			return new ResponseEntity<>(connectorsStatus, HttpStatus.OK);
		} catch (ConnectorRegistryException e) {
			throw new RuntimeException(e);
		}
	}

	@RequestMapping(value = "/monitoring/status/mapping", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<List<MappingStatus>> getMappingStatus() {
		String tenant = contextService.getContext().getTenant();
		List<MappingStatus> ms = mappingStatusComponent.getMappingStatus(tenant);
		log.info("Tenant {} - Get mapping status: {}", tenant, ms);
		return new ResponseEntity<List<MappingStatus>>(ms, HttpStatus.OK);
	}

	@RequestMapping(value = "/monitoring/tree", method = RequestMethod.GET, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<MappingTreeNode> getInboundMappingTree() {
		String tenant = contextService.getContext().getTenant();
		MappingTreeNode result = mappingComponent.getResolverMappingInbound().get(tenant);
		log.info("Tenant {} - Get mapping tree", tenant);
		return ResponseEntity.status(HttpStatus.OK).body(result);
	}

	@RequestMapping(value = "/monitoring/subscription/{connectorIdent}", method = RequestMethod.GET, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<Map<String, Integer>> getActiveSubscriptions(@PathVariable @NotNull String connectorIdent) {
		String tenant = contextService.getContext().getTenant();
		AConnectorClient client = null;
		try {
			client = connectorRegistry.getClientForTenant(tenant, connectorIdent);
			Map<String, MutableInt> as = client.getActiveSubscriptions();
			Map<String, Integer> result = as.entrySet().stream()
					.map(entry -> new AbstractMap.SimpleEntry<String, Integer>(entry.getKey(),
							entry.getValue().getValue()))
					.collect(Collectors.toMap(Entry::getKey, Entry::getValue));

			log.debug("Tenant {} - Getting active subscriptions!", tenant);
			return ResponseEntity.status(HttpStatus.OK).body(result);
		} catch (ConnectorRegistryException e) {
			throw new RuntimeException(e);
		}

	}

	@RequestMapping(value = "/mapping", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<List<Mapping>> getMappings() {
		String tenant = contextService.getContext().getTenant();
		log.info("Tenant {} - Get mappings", tenant);
		List<Mapping> result = mappingComponent.getMappings(tenant);
		return ResponseEntity.status(HttpStatus.OK).body(result);
	}

	@RequestMapping(value = "/mapping/{id}", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<Mapping> getMapping(@PathVariable String id) {
		String tenant = contextService.getContext().getTenant();
		log.info("Tenant {} - Get mapping: {}", tenant, id);
		Mapping result = mappingComponent.getMapping(tenant, id);
		if (result == null) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND).body(result);
		}
		return ResponseEntity.status(HttpStatus.OK).body(result);
	}

	@RequestMapping(value = "/mapping/{id}", method = RequestMethod.DELETE, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<String> deleteMapping(@PathVariable String id) {
		String tenant = contextService.getContext().getTenant();
		log.info("Tenant {} - Delete mapping: {}", tenant, id);
		try {
			final Mapping deletedMapping = mappingComponent.deleteMapping(tenant, id);
			if (deletedMapping == null)
				throw new ResponseStatusException(HttpStatus.NOT_FOUND,
						"Mapping with id " + id + " could not be found.");

			mappingComponent.deleteFromMappingCache(tenant, deletedMapping);

			if (!Direction.OUTBOUND.equals(deletedMapping.direction)) {
				// FIXME Currently we create mappings in ALL connectors assuming they could
				// occur in all of them.
				Map<String, AConnectorClient> clients = connectorRegistry.getClientsForTenant(tenant);
				clients.keySet().stream().forEach(connector -> {
					clients.get(connector).deleteActiveSubscription(deletedMapping);
				});
			}
		} catch (Exception ex) {
			log.error("Tenant {} - Exception when deleting mapping {}", tenant, ex);
			throw new ResponseStatusException(HttpStatus.NOT_ACCEPTABLE, ex.getLocalizedMessage());
		}
		log.info("Tenant {} - Mapping {} successfully deleted!", tenant, id);

		return ResponseEntity.status(HttpStatus.OK).body(id);
	}

	// TODO We might need to add the connector ID here to correlate mappings to
	// exactly one connector
	@RequestMapping(value = "/mapping", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<Mapping> createMapping(@Valid @RequestBody Mapping mapping) {
		try {
			String tenant = contextService.getContext().getTenant();
			log.info("Tenant {} - Adding mapping: {}", tenant, mapping.getMappingTopic());
			log.debug("Tenant {} - Adding mapping: {}", tenant, mapping);
			// new mapping should be disabled by default
			mapping.active = false;
			final Mapping createdMapping = mappingComponent.createMapping(tenant, mapping);
			if (Direction.OUTBOUND.equals(createdMapping.direction)) {
				mappingComponent.rebuildMappingOutboundCache(tenant);
			} else {
				// FIXME Currently we create mappings in ALL connectors assuming they could
				// occur in all of them.
				Map<String, AConnectorClient> clients = connectorRegistry.getClientsForTenant(tenant);
				clients.keySet().stream().forEach(connector -> {
					clients.get(connector).updateActiveSubscriptionInbound(createdMapping, true, false);
				});
				mappingComponent.deleteFromCacheMappingInbound(tenant, createdMapping);
				mappingComponent.addToCacheMappingInbound(tenant, createdMapping);
				mappingComponent.getCacheMappingInbound().get(tenant).put(createdMapping.id, mapping);
			}
			return ResponseEntity.status(HttpStatus.OK).body(createdMapping);
		} catch (Exception ex) {
			if (ex instanceof RuntimeException)
				throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getLocalizedMessage());
			else if (ex instanceof JsonProcessingException)
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getLocalizedMessage());
			else
				throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getLocalizedMessage());
		}
	}

	@RequestMapping(value = "/mapping/{id}", method = RequestMethod.PUT, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<Mapping> updateMapping(@PathVariable String id, @Valid @RequestBody Mapping mapping) {
		String tenant = contextService.getContext().getTenant();
		try {
			log.info("Tenant {} - Update mapping: {}, {}", mapping, id);
			final Mapping updatedMapping = mappingComponent.updateMapping(tenant, mapping, false, false);
			if (Direction.OUTBOUND.equals(mapping.direction)) {
				mappingComponent.rebuildMappingOutboundCache(tenant);
			} else {
				Map<String, AConnectorClient> clients = connectorRegistry.getClientsForTenant(tenant);
				clients.keySet().stream().forEach(connector -> {
					clients.get(connector).updateActiveSubscriptionInbound(updatedMapping, false, false);
				});
				mappingComponent.deleteFromCacheMappingInbound(tenant, mapping);
				mappingComponent.addToCacheMappingInbound(tenant, mapping);
				mappingComponent.getCacheMappingInbound().get(tenant).put(mapping.id, mapping);
			}
			return ResponseEntity.status(HttpStatus.OK).body(mapping);
		} catch (Exception ex) {
			if (ex instanceof IllegalArgumentException) {
				log.error("Tenant {} - Updating active mappings is not allowed {}", tenant, ex);
				throw new ResponseStatusException(HttpStatus.NOT_ACCEPTABLE, ex.getLocalizedMessage());
			} else if (ex instanceof RuntimeException)
				throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getLocalizedMessage());
			else if (ex instanceof JsonProcessingException)
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getLocalizedMessage());
			else
				throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getLocalizedMessage());
		}
	}

	@RequestMapping(value = "/test/{method}", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<List<ProcessingContext<?>>> forwardPayload(@PathVariable String method,
			@RequestParam URI topic, @RequestParam String connectorIdent,
			@Valid @RequestBody Map<String, Object> payload) {
		String path = topic.getPath();
		List<ProcessingContext<?>> result = null;
		String tenant = contextService.getContext().getTenant();
		log.info("Tenant {} - Test payload: {}, {}, {}", tenant, path, method,
				payload);
		try {
			boolean send = ("send").equals(method);
			try {
				AConnectorClient connectorClient = connectorRegistry
						.getClientForTenant(tenant, connectorIdent);
				result = connectorClient.test(path, send, payload);
			} catch (ConnectorRegistryException e) {
				throw new RuntimeException(e);
			}
			return new ResponseEntity<List<ProcessingContext<?>>>(result, HttpStatus.OK);
		} catch (Exception ex) {
			log.error("Tenant {} - Error transforming payload: {}", tenant, ex);
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getLocalizedMessage());
		}
	}

	@RequestMapping(value = "/extension", method = RequestMethod.GET, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<Map<String, Extension>> getProcessorExtensions() {
		String tenant = contextService.getContext().getTenant();
		Map<String, Extension> result = configurationRegistry.getC8yAgent().getProcessorExtensions(tenant);
		return ResponseEntity.status(HttpStatus.OK).body(result);
	}

	@RequestMapping(value = "/extension/{extensionName}", method = RequestMethod.GET, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<Extension> getProcessorExtension(@PathVariable String extensionName) {
		String tenant = contextService.getContext().getTenant();
		Extension result = configurationRegistry.getC8yAgent().getProcessorExtension(tenant, extensionName);
		if (result == null)
			throw new ResponseStatusException(HttpStatus.NOT_FOUND,
					"Extension with id " + extensionName + " could not be found.");
		return ResponseEntity.status(HttpStatus.OK).body(result);
	}

	@RequestMapping(value = "/extension/{extensionName}", method = RequestMethod.DELETE, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<Extension> deleteProcessorExtension(@PathVariable String extensionName) {
		String tenant = contextService.getContext().getTenant();
		if (!userHasMappingAdminRole()) {
			log.error("Tenant {} - Insufficient Permission, user does not have required permission to access this API",
					tenant);
			throw new ResponseStatusException(HttpStatus.FORBIDDEN,
					"Insufficient Permission, user does not have required permission to access this API");
		}
		Extension result = configurationRegistry.getC8yAgent().deleteProcessorExtension(tenant, extensionName);
		if (result == null)
			throw new ResponseStatusException(HttpStatus.NOT_FOUND,
					"Extension with id " + extensionName + " could not be found.");
		return ResponseEntity.status(HttpStatus.OK).body(result);
	}

	private boolean userHasMappingAdminRole() {
		return !userRolesEnabled || (userRolesEnabled && roleService.getUserRoles().contains(mappingAdminRole));
	}

	private boolean userHasMappingCreateRole() {
		return !userRolesEnabled || userHasMappingAdminRole()
				|| (userRolesEnabled && roleService.getUserRoles().contains(mappingCreateRole));
	}

}