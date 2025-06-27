/*
 * Copyright (c) 2022-2025 Cumulocity GmbH.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *  @authors Christof Strack, Stefan Witschel
 *
 */

package dynamic.mapper.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import jakarta.validation.Valid;
import dynamic.mapper.configuration.ConnectorConfigurationComponent;
import dynamic.mapper.configuration.ServiceConfigurationComponent;

import dynamic.mapper.connector.core.client.AConnectorClient;
import dynamic.mapper.connector.core.registry.ConnectorRegistry;
import dynamic.mapper.connector.core.registry.ConnectorRegistryException;
import dynamic.mapper.core.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.cumulocity.microservice.context.ContextService;
import com.cumulocity.microservice.context.credentials.UserCredentials;
import lombok.extern.slf4j.Slf4j;
import dynamic.mapper.model.DeploymentMapEntry;

@Slf4j
@RequestMapping("/deployment")
@RestController
public class DeploymentController {

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

	@GetMapping(value = "/effective",  consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<Map<String, DeploymentMapEntry>> getMappingsDeployed() {
		String tenant = contextService.getContext().getTenant();
		Map<String, DeploymentMapEntry> mappingsDeployed = new HashMap<>();
		try {
			Map<String, AConnectorClient> connectorMap = connectorRegistry
					.getClientsForTenant(tenant);
			if (connectorMap != null) {
				// iterate over all clients
				for (AConnectorClient client : connectorMap.values()) {
					client.collectSubscribedMappingsAll(mappingsDeployed);
				}
			}

			log.debug("{} - Get active subscriptions!", tenant);
			return ResponseEntity.status(HttpStatus.OK).body(mappingsDeployed);
		} catch (ConnectorRegistryException e) {
			throw new RuntimeException(e);
		}

	}

	@PreAuthorize("hasAnyRole('ROLE_DYNAMIC_MAPPER_ADMIN', 'ROLE_DYNAMIC_MAPPER_CREATE')")
	@PutMapping(value = "/defined/{mappingIdentifier}", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<HttpStatus> updateDeploymentMapEntry(@PathVariable String mappingIdentifier,
			@Valid @RequestBody List<String> deployment) {
		String tenant = contextService.getContext().getTenant();
		log.info("{} - Update deployment for mapping, mappingIdentifier: {}, deployment: {}", tenant, mappingIdentifier, deployment);
		try {
			mappingComponent.updateDeploymentMapEntry(tenant, mappingIdentifier, deployment);
			return ResponseEntity.status(HttpStatus.CREATED).build();
		} catch (Exception ex) {
			log.error("{} - Error updating deployment for mapping: {}", tenant, mappingIdentifier, ex);
			throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getLocalizedMessage());
		}
	}

	@GetMapping(value = "/defined/{mappingIdentifier}", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<List<String>> getDeploymentMapEntry(@PathVariable String mappingIdentifier) {
		String tenant = contextService.getContext().getTenant();
		log.info("{} - Get deployment for mappingIdentifier: {}", tenant, mappingIdentifier);
		try {
			List<String> map = mappingComponent.getDeploymentMapEntry(tenant, mappingIdentifier);
			return new ResponseEntity<List<String>>(map, HttpStatus.OK);
		} catch (Exception ex) {
			log.error("{} - Error getting deployment for mapping: {}", tenant, mappingIdentifier, ex);
			throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getLocalizedMessage());
		}
	}

	@GetMapping(value = "/defined", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<Map<String, List<String>>> getDeploymentMap() {
		String tenant = contextService.getContext().getTenant();
		log.info("{} - Get complete deployment", tenant);
		try {
			Map<String, List<String>> map = mappingComponent.getDeploymentMap(tenant);
			return new ResponseEntity<Map<String, List<String>>>(map, HttpStatus.OK);
		} catch (Exception ex) {
			log.error("{} - Error getting complete deployment!", tenant, ex);
			throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getLocalizedMessage());
		}
	}
}