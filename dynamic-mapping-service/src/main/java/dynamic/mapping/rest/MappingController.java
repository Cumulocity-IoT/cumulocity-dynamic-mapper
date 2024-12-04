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

import java.util.List;
import java.util.Map;
import jakarta.validation.Valid;
import dynamic.mapping.configuration.ConnectorConfigurationComponent;
import dynamic.mapping.configuration.ServiceConfigurationComponent;

import dynamic.mapping.connector.core.client.AConnectorClient;
import dynamic.mapping.connector.core.registry.ConnectorRegistry;
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
import com.fasterxml.jackson.core.JsonProcessingException;

import lombok.extern.slf4j.Slf4j;
import dynamic.mapping.model.Direction;
import dynamic.mapping.model.Mapping;

@Slf4j
@RestController
public class MappingController {

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
			log.error("Tenant {} - Exception when deleting mapping: ", tenant, ex);
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
}