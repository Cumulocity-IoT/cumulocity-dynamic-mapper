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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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

import dynamic.mapper.configuration.ConnectorConfiguration;
import dynamic.mapper.connector.core.client.AConnectorClient;
import dynamic.mapper.connector.core.registry.ConnectorRegistry;
import dynamic.mapper.connector.core.registry.ConnectorRegistryException;
import dynamic.mapper.model.DeploymentMapEntry;
import dynamic.mapper.service.ConnectorConfigurationService;
import dynamic.mapper.service.MappingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
@RequestMapping("/deployment")
@RestController
@Tag(name = "Deployment Controller", description = "API for managing mapping deployments across connectors. Controls which mappings are active on which connectors and provides visibility into the current deployment state.")
public class DeploymentController {

	private final ConnectorRegistry connectorRegistry;
	private final MappingService mappingService;
	private final ConnectorConfigurationService connectorConfigurationService;
	private final ContextService<UserCredentials> contextService;

	@Operation(
		summary = "Get effective deployments",
		description = """
			Retrieves the current effective deployment state by querying all active connectors 
			to see which mappings are actually deployed and running. This shows the real-time 
			deployment status across all connectors.
			
			**Use Case:**
			- Monitor which mappings are currently active on each connector
			- Verify deployment consistency across connectors
			- Troubleshoot deployment issues
			
			**Response Format:**
			- Key: Mapping identifier
			- Value: DeploymentMapEntry with connector details
			"""
	)
	@ApiResponses(value = {
		@ApiResponse(
			responseCode = "200",
			description = "Effective deployments retrieved successfully",
			content = @Content(
				mediaType = "application/json",
				schema = @Schema(
					type = "object",
					description = "Map of mapping identifiers to their deployment entries"
				),
				examples = @ExampleObject(
					name = "Effective Deployments",
					description = "Current deployment state across connectors",
					value = """
					{
					  "l19zjk": {
					    "identifier": "l19zjk",
					    "connectors": [
					      {
					        "identifier": "as9zjk",
					        "name": "MQTT Production Connector",
					        "enabled": true,
					        "connectorType": "MQTT"
					      }
					    ]
					  },
					  "m23abc": {
					    "identifier": "m23abc",
					    "connectors": [
					      {
					        "identifier": "n67zjk",
					        "name": "HTTP Connector",
					        "enabled": true,
					        "connectorType": "HTTP"
					      }
					    ]
					  }
					}
					"""
				)
			)
		),
		@ApiResponse(responseCode = "500", description = "Internal server error", content = @Content)
	})
	@GetMapping(value = "/effective", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
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

	@Operation(
		summary = "Update deployment configuration for mapping",
		description = """
			Updates the deployment configuration for a specific mapping by specifying which 
			connectors it should be deployed to. This defines the intended deployment state 
			rather than the actual runtime state.
			
			**Behavior:**
			- Defines which connectors should have this mapping active
			- Does not immediately deploy - requires separate activation
			- Overwrites existing deployment configuration for this mapping
			
			**Security:** Requires ROLE_DYNAMIC_MAPPER_ADMIN or ROLE_DYNAMIC_MAPPER_CREATE role.
			""",
		parameters = {
			@Parameter(
				name = "mappingIdentifier",
				description = "Generated identifier for the mapping",
				required = true,
				example = "l19zjk",
				schema = @Schema(type = "string")
			)
		},
		requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
			description = "List of connector identifiers where this mapping should be deployed",
			required = true,
			content = @Content(
				mediaType = "application/json",
				schema = @Schema(
					type = "array",
					description = "List of connector identifiers"
				),
				examples = @ExampleObject(
					name = "Connector Deployment",
					description = "Deploy mapping to specific connectors",
					value = """
					[
					  "mqtt-connector-01",
					  "http-connector",
					  "tcp-connector-01"
					]
					"""
				)
			)
		)
	)
	@ApiResponses(value = {
		@ApiResponse(responseCode = "201", description = "Deployment configuration updated successfully", content = @Content),
		@ApiResponse(responseCode = "400", description = "One or more connector identifiers are unknown", content = @Content),
		@ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content),
		@ApiResponse(responseCode = "404", description = "Mapping not found", content = @Content),
		@ApiResponse(responseCode = "500", description = "Internal server error", content = @Content)
	})
	@PreAuthorize("hasAnyRole('ROLE_DYNAMIC_MAPPER_ADMIN', 'ROLE_DYNAMIC_MAPPER_CREATE')")
	@PutMapping(value = "/defined/{mappingIdentifier}", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<HttpStatus> updateDeploymentMapEntry(
			@PathVariable String mappingIdentifier,
			@Valid @RequestBody List<String> deployment) {
		String tenant = contextService.getContext().getTenant();
		log.info("{} - Update deployment for mapping, mappingIdentifier: {}, deployment: {}", tenant, mappingIdentifier, deployment);

		// Reject unknown connector identifiers up front (400) rather than silently storing
		// dangling references that only get cleaned up at the next startup.
		validateConnectorsExist(tenant, deployment);

		try {
			// Capture the previous assignment so we only reconcile connectors that actually
			// gained or lost this mapping.
			Set<String> affected = new HashSet<>(mappingService.getDeploymentMapEntry(tenant, mappingIdentifier));
			affected.addAll(deployment);

			mappingService.updateDeploymentMapEntry(tenant, mappingIdentifier, deployment);

			// Apply the new deployment immediately: reconcile the affected connectors so the
			// mapping is (un)subscribed live. Without this the change is only persisted and would
			// not take effect until a connector reconnect or an explicit RELOAD_MAPPINGS.
			reconcileConnectors(tenant, affected);
			return ResponseEntity.status(HttpStatus.CREATED).build();
		} catch (Exception ex) {
			log.error("{} - Error updating deployment for mapping: {}", tenant, mappingIdentifier, ex);
			throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getLocalizedMessage());
		}
	}

	/**
	 * Validates that every connector identifier in the deployment exists for the tenant.
	 *
	 * @throws ResponseStatusException with status 400 if any identifier is unknown
	 */
	private void validateConnectorsExist(String tenant, List<String> deployment) {
		if (deployment == null || deployment.isEmpty()) {
			return;
		}
		Set<String> validIdentifiers = connectorConfigurationService.getConnectorConfigurations(tenant).stream()
				.map(ConnectorConfiguration::getIdentifier)
				.collect(Collectors.toSet());
		List<String> unknown = deployment.stream()
				.filter(id -> !validIdentifiers.contains(id))
				.distinct()
				.collect(Collectors.toList());
		if (!unknown.isEmpty()) {
			log.warn("{} - Rejecting deployment with unknown connector identifier(s): {}", tenant, unknown);
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"Unknown connector identifier(s): " + unknown);
		}
	}

	/**
	 * Reconcile the subscriptions of the given connectors against the current deployment map.
	 * Only connectors whose identifier is in {@code affectedConnectorIdentifiers} are touched;
	 * each is reconciled independently so a failure on one does not prevent the others.
	 */
	private void reconcileConnectors(String tenant, Set<String> affectedConnectorIdentifiers)
			throws ConnectorRegistryException {
		Map<String, AConnectorClient> connectorMap = connectorRegistry.getClientsForTenant(tenant);
		if (connectorMap == null) {
			return;
		}
		for (AConnectorClient client : connectorMap.values()) {
			if (!affectedConnectorIdentifiers.contains(client.getConnectorIdentifier())) {
				continue;
			}
			try {
				client.reconcileSubscriptions();
			} catch (Exception e) {
				log.error("{} - Error reconciling subscriptions for connector {} after deployment change",
						tenant, client.getConnectorIdentifier(), e);
			}
		}
	}

	@Operation(
		summary = "Get deployment configuration for mapping",
		description = """
			Retrieves the deployment configuration for a specific mapping, showing which 
			connectors this mapping is configured to be deployed to.
			
			**Note:** This shows the configured deployment, not necessarily the active runtime state.
			Use the effective deployment endpoint to see actual runtime deployment.
			""",
		parameters = {
			@Parameter(
				name = "mappingIdentifier",
				description = "Generated identifier for the mapping",
				required = true,
				example = "l19zjk",
				schema = @Schema(type = "string")
			)
		}
	)
	@ApiResponses(value = {
		@ApiResponse(
			responseCode = "200",
			description = "Deployment configuration retrieved successfully",
			content = @Content(
				mediaType = "application/json",
				schema = @Schema(
					type = "array",
					description = "List of connector identifiers"
				),
				examples = @ExampleObject(
					name = "Deployment Configuration",
					description = "List of connectors for this mapping",
					value = """
					[
					  "mqtt-connector-01",
					  "http-connector"
					]
					"""
				)
			)
		),
		@ApiResponse(responseCode = "404", description = "Mapping not found", content = @Content),
		@ApiResponse(responseCode = "500", description = "Internal server error", content = @Content)
	})
	@GetMapping(value = "/defined/{mappingIdentifier}", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<List<String>> getDeploymentMapEntry(@PathVariable String mappingIdentifier) {
		String tenant = contextService.getContext().getTenant();
		log.info("{} - Get deployment for mappingIdentifier: {}", tenant, mappingIdentifier);
		try {
			List<String> map = mappingService.getDeploymentMapEntry(tenant, mappingIdentifier);
			return new ResponseEntity<List<String>>(map, HttpStatus.OK);
		} catch (Exception ex) {
			log.error("{} - Error getting deployment for mapping: {}", tenant, mappingIdentifier, ex);
			throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getLocalizedMessage());
		}
	}

	@Operation(
		summary = "Get complete deployment configuration",
		description = """
			Retrieves the complete deployment configuration map showing all mappings and 
			which connectors they are configured to be deployed to.
			
			**Response Format:**
			- Key: Mapping identifier
			- Value: List of connector identifiers
			
			**Use Cases:**
			- Get overview of all deployment configurations
			- Export/backup deployment settings
			- Audit deployment assignments
			- Bulk deployment management
			"""
	)
	@ApiResponses(value = {
		@ApiResponse(
			responseCode = "200",
			description = "Complete deployment configuration retrieved successfully",
			content = @Content(
				mediaType = "application/json",
				schema = @Schema(
					type = "object",
					description = "Map of mapping identifiers to lists of connector identifiers"
				),
				examples = @ExampleObject(
					name = "Complete Deployment Map",
					description = "All deployment configurations",
					value = """
					{
					  "l19zjk": ["mqtt-connector-01", "http-connector"],
					  "m23abc": ["mqtt-connector-01"],
					  "n45def": ["http-connector", "tcp-connector-01"],
					  "p67ghi": ["mqtt-connector-01", "tcp-connector-01"]
					}
					"""
				)
			)
		),
		@ApiResponse(responseCode = "500", description = "Internal server error", content = @Content)
	})
	@GetMapping(value = "/defined", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<Map<String, List<String>>> getDeploymentMap() {
		String tenant = contextService.getContext().getTenant();
		log.info("{} - Get complete deployment", tenant);
		try {
			Map<String, List<String>> map = mappingService.getDeploymentMap(tenant);
			return new ResponseEntity<Map<String, List<String>>>(map, HttpStatus.OK);
		} catch (Exception ex) {
			log.error("{} - Error getting complete deployment!", tenant, ex);
			throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getLocalizedMessage());
		}
	}
}