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

package dynamic.mapping.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import jakarta.validation.Valid;
import dynamic.mapping.configuration.ConnectorConfiguration;
import dynamic.mapping.configuration.ConnectorConfigurationComponent;
import dynamic.mapping.configuration.ServiceConfiguration;
import dynamic.mapping.configuration.ServiceConfigurationComponent;

import dynamic.mapping.connector.core.client.AConnectorClient;
import dynamic.mapping.connector.core.registry.ConnectorRegistry;
import dynamic.mapping.core.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.cumulocity.microservice.context.ContextService;
import com.cumulocity.microservice.context.credentials.UserCredentials;
import lombok.extern.slf4j.Slf4j;
import dynamic.mapping.model.Direction;
import dynamic.mapping.model.SnoopStatus;
import dynamic.mapping.model.Mapping;

@Slf4j
@RestController
public class OperationController {

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
	C8YAgent c8YAgent;

	@Autowired
	private ContextService<UserCredentials> contextService;

	@Autowired
	private ConfigurationRegistry configurationRegistry;

	@Value("${APP.externalExtensionsEnabled}")
	private boolean externalExtensionsEnabled;

	@Value("${APP.userRolesEnabled}")
	private Boolean userRolesEnabled;

	@Value("${APP.mappingAdminRole}")
	private String mappingAdminRole;

	@Value("${APP.mappingCreateRole}")
	private String mappingCreateRole;

	@RequestMapping(value = "/operation", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<?> runOperation(@Valid @RequestBody ServiceOperation operation) {
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
				String connectorIdentifier = operation.getParameter().get("connectorIdentifier");
				ConnectorConfiguration configuration = connectorConfigurationComponent
						.getConnectorConfiguration(connectorIdentifier, tenant);
				configuration.setEnabled(true);
				connectorConfigurationComponent.saveConnectorConfiguration(configuration);

				// Initialize Connector only when enabled.
				ServiceConfiguration serviceConfiguration = serviceConfigurationComponent
						.getServiceConfiguration(tenant);
				bootstrapService.initializeConnectorByConfiguration(configuration, serviceConfiguration, tenant);
				configurationRegistry.getNotificationSubscriber().notificationSubscriberReconnect(tenant);

				AConnectorClient client = connectorRegistry.getClientForTenant(tenant,
						connectorIdentifier);

				client.submitConnect();
			} else if (operation.getOperation().equals(Operation.DISCONNECT)) {
				String connectorIdentifier = operation.getParameter().get("connectorIdentifier");
				ConnectorConfiguration configuration = connectorConfigurationComponent
						.getConnectorConfiguration(connectorIdentifier, tenant);
				configuration.setEnabled(false);
				connectorConfigurationComponent.saveConnectorConfiguration(configuration);

				AConnectorClient client = connectorRegistry.getClientForTenant(tenant,
						connectorIdentifier);
				// client.submitDisconnect();
				bootstrapService.disableConnector(tenant, client.getConnectorIdent());
				// We might need to Reconnect other Notification Clients for other connectors
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
				// subscribe/unsubscribe respective mappingTopic of mapping only for
				// outbound mapping
				Map<String, String> failed = new HashMap<>();
				for (AConnectorClient client : connectorMap.values()) {
					if (updatedMapping.direction == Direction.INBOUND) {
						if (!client.updateActiveSubscriptionInbound(updatedMapping, false, true)) {
							ConnectorConfiguration conf = client.getConnectorConfiguration();
							failed.put(conf.getIdentifier(), conf.getName());
						}
						;
					} else {
						client.updateActiveSubscriptionOutbound(updatedMapping);
					}
				}

				if (failed.size() > 0) {
					// configurationRegistry.getC8yAgent().createEvent("Activation of mapping: " +
					// updatedMapping.name,
					// C8YAgent.STATUS_MAPPING_ACTIVATION_ERROR_EVENT_TYPE,
					// DateTime.now(),
					// configurationRegistry.getMappingServiceRepresentations().get(tenant),
					// tenant,
					// failed);
					return new ResponseEntity<Map<String, String>>(failed, HttpStatus.BAD_REQUEST);
				}

			} else if (operation.getOperation().equals(Operation.APPLY_MAPPING_FILTER)) {
				// activate/deactivate mapping
				String mappingId = operation.getParameter().get("id");
				String filterMapping = operation.getParameter().get("filterMapping");
				mappingComponent.setFilterMapping(tenant, mappingId, filterMapping);
			} 
			else if (operation.getOperation().equals(Operation.DEBUG_MAPPING)) {
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
			} else if (operation.getOperation().equals(Operation.CLEAR_CACHE)) {
				String cacheId = operation.getParameter().get("cacheId");
				if ("INBOUND_ID_CACHE".equals(cacheId)) {
					Integer cacheSize = serviceConfigurationComponent
							.getServiceConfiguration(tenant).inboundExternalIdCacheSize;
					c8YAgent.clearInboundExternalIdCache(tenant, false, cacheSize);
					log.info("Tenant {} - Cache cleared: {}", tenant, cacheId);
				} else {
					String errorMsgTemplate = "Tenant %s - Unknown cache: %s";
					String errorMsg = String.format(errorMsgTemplate, tenant, cacheId);
					log.error(errorMsg);
					throw new ResponseStatusException(HttpStatus.BAD_REQUEST, errorMsg);
				}
			}
			return ResponseEntity.status(HttpStatus.CREATED).build();
		} catch (Exception ex) {
			log.error("Tenant {} - Error running operation {}", tenant, ex);
			throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getLocalizedMessage());
		}
	}

}