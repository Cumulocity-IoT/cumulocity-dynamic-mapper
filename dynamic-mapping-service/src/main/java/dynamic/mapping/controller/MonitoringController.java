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

import java.util.AbstractMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collectors;

import jakarta.validation.constraints.NotNull;

import dynamic.mapping.configuration.ConnectorConfiguration;
import dynamic.mapping.configuration.ConnectorConfigurationComponent;
import dynamic.mapping.configuration.ServiceConfigurationComponent;

import dynamic.mapping.connector.core.client.AConnectorClient;
import dynamic.mapping.connector.core.registry.ConnectorRegistry;
import dynamic.mapping.connector.core.registry.ConnectorRegistryException;
import dynamic.mapping.core.*;
import org.apache.commons.lang3.mutable.MutableInt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.cumulocity.microservice.context.ContextService;
import com.cumulocity.microservice.context.credentials.UserCredentials;
import lombok.extern.slf4j.Slf4j;
import dynamic.mapping.model.MappingTreeNode;
import dynamic.mapping.model.MappingStatus;

@Slf4j
@RequestMapping("/monitoring")
@RestController
public class MonitoringController {

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

	@Value("${APP.externalExtensionsEnabled}")
	private boolean externalExtensionsEnabled;

	@Value("${APP.userRolesEnabled}")
	private Boolean userRolesEnabled;

	@Value("${APP.mappingAdminRole}")
	private String mappingAdminRole;

	@Value("${APP.mappingCreateRole}")
	private String mappingCreateRole;

	@GetMapping(value = "/status/connector/{connectorIdentifier}", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<ConnectorStatusEvent> getConnectorStatus(@PathVariable @NotNull String connectorIdentifier) {
		try {
			String tenant = contextService.getContext().getTenant();
			AConnectorClient client = connectorRegistry.getClientForTenant(tenant,
					connectorIdentifier);
			ConnectorStatusEvent st = client.getConnectorStatus();
			log.info("Tenant {} - Get status for connector {}: {}", tenant, connectorIdentifier, st);
			return new ResponseEntity<>(st, HttpStatus.OK);
		} catch (ConnectorRegistryException e) {
			throw new RuntimeException(e);
		}
	}

	@GetMapping(value = "/status/connectors", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<Map<String, ConnectorStatusEvent>> getConnectorsStatus() {
		Map<String, ConnectorStatusEvent> connectorsStatus = new HashMap<>();
		String tenant = contextService.getContext().getTenant();
		try {
			// initialize list with all known connectors
			List<ConnectorConfiguration> configurationList = connectorConfigurationComponent.getConnectorConfigurations(
					tenant);
			for (ConnectorConfiguration conf : configurationList) {
				connectorsStatus.put(conf.getIdentifier(), ConnectorStatusEvent.unknown(conf.name, conf.identifier));
			}

			// overwrite status with last remembered status of once enabled connectors
			connectorsStatus.putAll(connectorRegistry.getConnectorStatusMap().get(tenant));
			// overwrite with / add status of currently enabled connectors
			if (connectorRegistry.getClientsForTenant(tenant) != null) {
				for (AConnectorClient client : connectorRegistry.getClientsForTenant(tenant).values()) {
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

	@GetMapping(value = "/status/mapping/statistic", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<List<MappingStatus>> getMappingStatus() {
		String tenant = contextService.getContext().getTenant();
		List<MappingStatus> ms = mappingComponent.getMappingStatus(tenant);
		log.info("Tenant {} - Get mapping status: {}", tenant, ms);
		return new ResponseEntity<List<MappingStatus>>(ms, HttpStatus.OK);
	}

    // @RequestMapping(value = "/status/mapping/error", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
	// public ResponseEntity<List<MappingStatus>> getMappingLoadingError() {
	// 	String tenant = contextService.getContext().getTenant();
	// 	List<MappingStatus> ms = mappingComponent.getMappingLoadingError(tenant);
	// 	log.info("Tenant {} - Get mapping loadingError: {}", tenant, ms);
	// 	return new ResponseEntity<List<MappingStatus>>(ms, HttpStatus.OK);
	// }

	@GetMapping(value = "/tree", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<MappingTreeNode> getInboundMappingTree() {
		String tenant = contextService.getContext().getTenant();
		MappingTreeNode result = mappingComponent.getResolverMappingInbound().get(tenant);
		log.info("Tenant {} - Get mapping tree", tenant);
		return ResponseEntity.status(HttpStatus.OK).body(result);
	}

	@GetMapping(value = "/subscription/{connectorIdentifier}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<Map<String, Integer>> getActiveSubscriptions(@PathVariable @NotNull String connectorIdentifier) {
		String tenant = contextService.getContext().getTenant();
		AConnectorClient client = null;
		try {
			client = connectorRegistry.getClientForTenant(tenant, connectorIdentifier);
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

}