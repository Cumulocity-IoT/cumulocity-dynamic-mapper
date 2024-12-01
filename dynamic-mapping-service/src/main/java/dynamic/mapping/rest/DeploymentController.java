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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.validation.Valid;
import dynamic.mapping.configuration.ConnectorConfigurationComponent;
import dynamic.mapping.configuration.ServiceConfigurationComponent;

import dynamic.mapping.connector.core.client.AConnectorClient;
import dynamic.mapping.connector.core.registry.ConnectorRegistry;
import dynamic.mapping.connector.core.registry.ConnectorRegistryException;
import dynamic.mapping.core.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.cumulocity.microservice.context.ContextService;
import com.cumulocity.microservice.context.credentials.UserCredentials;
import lombok.extern.slf4j.Slf4j;
import dynamic.mapping.model.DeploymentMapEntryDetailed;

@Slf4j
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

	@Value("${APP.externalExtensionsEnabled}")
	private boolean externalExtensionsEnabled;

	@Value("${APP.userRolesEnabled}")
	private Boolean userRolesEnabled;

	@Value("${APP.mappingAdminRole}")
	private String mappingAdminRole;

	@Value("${APP.mappingCreateRole}")
	private String mappingCreateRole;

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
}