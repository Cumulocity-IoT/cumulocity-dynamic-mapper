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

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import jakarta.validation.constraints.NotNull;

import dynamic.mapper.configuration.ConnectorConfiguration;
import dynamic.mapper.connector.core.client.AConnectorClient;
import dynamic.mapper.connector.core.registry.ConnectorRegistry;
import dynamic.mapper.connector.core.registry.ConnectorRegistryException;
import dynamic.mapper.core.*;
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
import dynamic.mapper.model.MappingTreeNode;
import dynamic.mapper.service.ConnectorConfigurationService;
import dynamic.mapper.service.MappingService;
import dynamic.mapper.service.ServiceConfigurationService;
import dynamic.mapper.model.ConnectorStatus;
import dynamic.mapper.model.ConnectorStatusEvent;
import dynamic.mapper.model.MappingStatus;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@Slf4j
@RequestMapping("/monitoring")
@RestController
@Tag(name = "Monitoring Controller", description = "API for monitoring connector status, mapping statistics, and system health")
public class MonitoringController {

    @Autowired
    ConnectorRegistry connectorRegistry;

    @Autowired
    MappingService mappingService;

    @Autowired
    ConnectorConfigurationService connectorConfigurationService;

    @Autowired
    ServiceConfigurationService serviceConfigurationService;

    @Autowired
    BootstrapService bootstrapService;

    @Autowired
    C8YAgent c8YAgent;

    @Autowired
    private ContextService<UserCredentials> contextService;

    @Value("${APP.externalExtensionsEnabled}")
    private Boolean externalExtensionsEnabled;

    @Operation(summary = "Get connector status", description = "Retrieves the current status of a specific connector including connection state, last update time, and any error messages.", parameters = {
            @Parameter(name = "connectorIdentifier", description = "The unique identifier of the connector", required = true, example = "l19zjk", schema = @Schema(type = "string"))
    })
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Connector status retrieved successfully", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConnectorStatusEvent.class))),
            @ApiResponse(responseCode = "404", description = "Connector not found", content = @Content),
            @ApiResponse(responseCode = "500", description = "Internal server error", content = @Content)
    })
    @GetMapping(value = "/status/connector/{connectorIdentifier}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ConnectorStatusEvent> getConnectorStatus(@PathVariable @NotNull String connectorIdentifier) {
        try {
            String tenant = contextService.getContext().getTenant();
            AConnectorClient client = connectorRegistry.getClientForTenant(tenant,
                    connectorIdentifier);
            ConnectorStatusEvent st = client.getConnectionStateManager().getConnectorStatus().get();
            log.info("{} - Get status for connector: {}: {}", tenant, connectorIdentifier, st);
            return new ResponseEntity<>(st, HttpStatus.OK);
        } catch (ConnectorRegistryException e) {
            throw new RuntimeException(e);
        }
    }

    @Operation(summary = "Get all connectors status", description = "Retrieves the status of all connectors for the current tenant. Returns a map with connector identifiers as keys and their status information as values. Includes both enabled and disabled connectors.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Connectors status retrieved successfully", content = @Content(mediaType = "application/json", schema = @Schema(type = "object", description = "Map of connector identifiers to their status"))),
            @ApiResponse(responseCode = "500", description = "Internal server error", content = @Content)
    })
    @GetMapping(value = "/status/connectors", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, ConnectorStatusEvent>> getConnectorsStatus() {
        Map<String, ConnectorStatusEvent> connectorsStatus = new ConcurrentHashMap<>();
        String tenant = contextService.getContext().getTenant();

        // log.info("{} - Starting to collect connector statuses", tenant);

        try {
            // Stage 1: Initialize list with all known connectors as UNKNOWN
            List<ConnectorConfiguration> configurationList = connectorConfigurationService
                    .getConnectorConfigurations(tenant);
            // log.info("{} - Stage 1: Found {} connector configurations", tenant,
            // configurationList.size());

            for (ConnectorConfiguration conf : configurationList) {
                ConnectorStatusEvent unknownStatus = ConnectorStatusEvent.unknown(conf.getName(), conf.getIdentifier());
                connectorsStatus.put(conf.getIdentifier(), unknownStatus);
                // log.info("{} - Stage 1: Initialized connector [{}] ({}) with status:
                // UNKNOWN",
                // tenant, conf.getName(), conf.getIdentifier());
            }

            // log.info("{} - Stage 1 complete: {} connectors initialized as UNKNOWN",
            // tenant, connectorsStatus.size());
            // logCurrentStatuses(tenant, "After Stage 1", connectorsStatus);

            // Stage 2: Overwrite with last remembered status from registry
            Map<String, ConnectorStatusEvent> registryStatusMap = connectorRegistry.getConnectorStatusMap(tenant);
            // log.info("{} - Stage 2: Found {} statuses in registry status map",
            // tenant, registryStatusMap != null ? registryStatusMap.size() : 0);

            if (registryStatusMap != null && !registryStatusMap.isEmpty()) {
                for (Map.Entry<String, ConnectorStatusEvent> entry : registryStatusMap.entrySet()) {
                    // ConnectorStatusEvent oldStatus = connectorsStatus.get(entry.getKey());
                    ConnectorStatusEvent newStatus = entry.getValue();
                    connectorsStatus.put(entry.getKey(), newStatus);

                    // log.info("{} - Stage 2: Updated connector [{}] ({}) from {} to {}",
                    // tenant,
                    // newStatus.getConnectorName(),
                    // entry.getKey(),
                    // oldStatus != null ? oldStatus.getStatus() : "null",
                    // newStatus.getStatus());
                }
                // log.info("{} - Stage 2 complete: Updated {} connectors from registry",
                // tenant, registryStatusMap.size());
            } else {
                // log.info("{} - Stage 2: No statuses found in registry, skipping", tenant);
            }

            // logCurrentStatuses(tenant, "After Stage 2", connectorsStatus);

            // Stage 3: Overwrite with status of currently active connectors
            Map<String, AConnectorClient> activeClients = connectorRegistry.getClientsForTenant(tenant);
            // log.info("{} - Stage 3: Found {} active connector clients",
            //         tenant, activeClients != null ? activeClients.size() : 0);

            if (activeClients != null && !activeClients.isEmpty()) {
                for (Map.Entry<String, AConnectorClient> entry : activeClients.entrySet()) {
                    AConnectorClient client = entry.getValue();
                    // ConnectorStatusEvent oldStatus =
                    // connectorsStatus.get(client.getConnectorIdentifier());
                    ConnectorStatusEvent newStatus = client.getConnectionStateManager().getConnectorStatus().get();
                    connectorsStatus.put(client.getConnectorIdentifier(), newStatus);

                    // log.info("{} - Stage 3: Updated active connector [{}] ({}) from {} to {}",
                    // tenant,
                    // client.getConnectorName(),
                    // client.getConnectorIdentifier(),
                    // oldStatus != null ? oldStatus.getStatus() : "null",
                    // newStatus.getStatus());
                }
                // log.info("{} - Stage 3 complete: Updated {} active connectors",
                // tenant, activeClients.size());
            } else {
                // log.info("{} - Stage 3: No active clients found, skipping", tenant);
            }

            // logCurrentStatuses(tenant, "Final Status", connectorsStatus);

            // log.info("{} - Connector status collection complete: returning {} statuses",
            //        tenant, connectorsStatus.size());

            return new ResponseEntity<>(connectorsStatus, HttpStatus.OK);

        } catch (ConnectorRegistryException e) {
            log.error("{} - Error retrieving connector statuses: {}", tenant, e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    /**
     * Helper method to log all current connector statuses
     */
    private void logCurrentStatuses(String tenant, String stage, Map<String, ConnectorStatusEvent> statuses) {
        log.info("{} - {} - Summary of {} connectors:", tenant, stage, statuses.size());

        // Group by status for clearer logging
        Map<ConnectorStatus, List<String>> statusGroups = new HashMap<>();
        for (ConnectorStatusEvent event : statuses.values()) {
            statusGroups.computeIfAbsent(event.getStatus(), k -> new ArrayList<>())
                    .add(event.getConnectorName());
        }

        // Log each status group
        for (Map.Entry<ConnectorStatus, List<String>> entry : statusGroups.entrySet()) {
            log.info("{} - {} - Status {}: {} connector(s) - {}",
                    tenant,
                    stage,
                    entry.getKey(),
                    entry.getValue().size(),
                    String.join(", ", entry.getValue()));
        }

        // Also log individual details
        for (Map.Entry<String, ConnectorStatusEvent> entry : statuses.entrySet()) {
            ConnectorStatusEvent event = entry.getValue();
            String message = event.getMessage() != null && !event.getMessage().isEmpty()
                    ? " [" + event.getMessage() + "]"
                    : "";
            log.info("{} - {} - Connector: {} ({}), Status: {}{}",
                    tenant,
                    stage,
                    event.getConnectorName(),
                    entry.getKey(),
                    event.getStatus(),
                    message);
        }
    }

    @Operation(summary = "Get mapping statistics", description = "Retrieves statistics for all mappings including message counts, error counts, snooping status, and loading errors. Useful for monitoring mapping performance and health.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Mapping statistics retrieved successfully", content = @Content(mediaType = "application/json", schema = @Schema(type = "array", description = "List of mapping statistics", implementation = MappingStatus.class))),
            @ApiResponse(responseCode = "500", description = "Internal server error", content = @Content)
    })
    @GetMapping(value = "/status/mapping/statistic", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<MappingStatus>> getMappingStatus() {
        String tenant = contextService.getContext().getTenant();
        List<MappingStatus> ms = mappingService.getMappingStatus(tenant);
        log.info("{} - Get mapping status: {}", tenant, ms);
        return new ResponseEntity<List<MappingStatus>>(ms, HttpStatus.OK);
    }

    // @RequestMapping(value = "/status/mapping/error", method = RequestMethod.GET,
    // produces = MediaType.APPLICATION_JSON_VALUE)
    // public ResponseEntity<List<MappingStatus>> getMappingLoadingError() {
    // String tenant = contextService.getContext().getTenant();
    // List<MappingStatus> ms = mappingService.getMappingLoadingError(tenant);
    // log.info("{} - Get mapping loadingError: {}", tenant, ms);
    // return new ResponseEntity<List<MappingStatus>>(ms, HttpStatus.OK);
    // }

    @Operation(summary = "Get inbound mapping tree", description = "Retrieves the hierarchical tree structure of inbound mappings organized by topic patterns. This shows how incoming messages are routed to different mappings based on topic matching.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Mapping tree retrieved successfully", content = @Content(mediaType = "application/json", schema = @Schema(implementation = MappingTreeNode.class))),
            @ApiResponse(responseCode = "500", description = "Internal server error", content = @Content)
    })
    @GetMapping(value = "/tree", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<MappingTreeNode> getInboundMappingTree() {
        String tenant = contextService.getContext().getTenant();
        MappingTreeNode result = mappingService.getResolverMappingInbound(tenant);
        log.info("{} - Get mapping tree", tenant);
        return ResponseEntity.status(HttpStatus.OK).body(result);
    }

    @Operation(summary = "Get active subscriptions for connector", description = "Retrieves the active topic subscriptions for a specific connector with the count of mappings per topic. This helps monitor which topics are being subscribed to and how many mappings are listening to each topic.", parameters = {
            @Parameter(name = "connectorIdentifier", description = "The unique identifier of the connector", required = true, example = "l19zjk", schema = @Schema(type = "string"))
    })
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Active subscriptions retrieved successfully", content = @Content(mediaType = "application/json", schema = @Schema(type = "object", description = "Map of topic patterns to subscription counts"))),
            @ApiResponse(responseCode = "404", description = "Connector not found", content = @Content),
            @ApiResponse(responseCode = "500", description = "Internal server error", content = @Content)
    })
    @GetMapping(value = "/subscription/{connectorIdentifier}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Integer>> getActiveSubscriptions(
            @PathVariable @NotNull String connectorIdentifier) {
        String tenant = contextService.getContext().getTenant();
        AConnectorClient client = null;
        try {
            client = connectorRegistry.getClientForTenant(tenant, connectorIdentifier);
            Map<String, MutableInt> as = client.getCountSubscriptionsPerTopicInbound();
            Map<String, Integer> result = as.entrySet().stream()
                    .map(entry -> new AbstractMap.SimpleEntry<String, Integer>(entry.getKey(),
                            entry.getValue().getValue()))
                    .collect(Collectors.toMap(Entry::getKey, Entry::getValue));

            log.debug("{} - Getting active subscriptions!", tenant);
            return ResponseEntity.status(HttpStatus.OK).body(result);
        } catch (ConnectorRegistryException e) {
            throw new RuntimeException(e);
        }

    }

}