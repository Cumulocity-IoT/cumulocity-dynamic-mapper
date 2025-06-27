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

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.cumulocity.microservice.context.ContextService;
import com.cumulocity.microservice.context.credentials.UserCredentials;
import com.fasterxml.jackson.core.JsonProcessingException;

import dynamic.mapper.configuration.ConnectorConfigurationComponent;
import dynamic.mapper.configuration.ConnectorId;
import dynamic.mapper.configuration.ServiceConfigurationComponent;
import dynamic.mapper.connector.core.client.AConnectorClient;
import dynamic.mapper.connector.core.registry.ConnectorRegistry;
import dynamic.mapper.core.BootstrapService;
import dynamic.mapper.core.C8YAgent;
import dynamic.mapper.core.MappingComponent;
import dynamic.mapper.model.Direction;
import dynamic.mapper.model.Mapping;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequestMapping("/mapping")

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

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<Mapping>> getMappings(@RequestParam(required = false) Direction direction) {
        String tenant = contextService.getContext().getTenant();
        log.debug("{} - Get mappings", tenant);
        List<Mapping> result = mappingComponent.getMappings(tenant, direction);
        return ResponseEntity.status(HttpStatus.OK).body(result);
    }

    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Mapping> getMapping(@PathVariable String id) {
        String tenant = contextService.getContext().getTenant();
        log.debug("{} - Get mapping: {}", tenant, id);
        Mapping result = mappingComponent.getMapping(tenant, id);
        if (result == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(result);
        }
        return ResponseEntity.status(HttpStatus.OK).body(result);
    }

    @PreAuthorize("hasAnyRole('ROLE_DYNAMIC_MAPPER_ADMIN', 'ROLE_DYNAMIC_MAPPER_CREATE')")
    @DeleteMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> deleteMapping(@PathVariable String id) {
        String tenant = contextService.getContext().getTenant();
        log.debug("{} - Delete mapping: {}", tenant, id);
        try {
            final Mapping deletedMapping = mappingComponent.deleteMapping(tenant, id);
            if (deletedMapping == null)
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Mapping with id " + id + " could not be found");

            mappingComponent.removeFromMappingFromCaches(tenant, deletedMapping);

            if (!Direction.OUTBOUND.equals(deletedMapping.direction)) {
                // FIXME Currently we create mappings in ALL connectors assuming they could
                // occur in all of them.
                Map<String, AConnectorClient> clients = connectorRegistry.getClientsForTenant(tenant);
                clients.keySet().stream().forEach(connector -> {
                    clients.get(connector).deleteActiveSubscription(deletedMapping);
                });
            }
        } catch (Exception ex) {
            log.error("{} - Exception deleting mapping: {}", tenant, id, ex);
            throw new ResponseStatusException(HttpStatus.NOT_ACCEPTABLE, ex.getLocalizedMessage());
        }
        log.info("{} - Mapping {} deleted", tenant, id);

        return ResponseEntity.status(HttpStatus.OK).body(id);
    }

    // TODO We might need to add the connector ID here to correlate mappings to
    // exactly one connector
    @PreAuthorize("hasAnyRole('ROLE_DYNAMIC_MAPPER_ADMIN', 'ROLE_DYNAMIC_MAPPER_CREATE')")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Mapping> createMapping(@Valid @RequestBody Mapping mapping) {
        try {
            String tenant = contextService.getContext().getTenant();
            log.info("{} - Create mapping: {}", tenant, mapping.getMappingTopic());
            log.debug("{} - Create mapping: {}", tenant, mapping);
            // new mapping should be disabled by default
            mapping.active = false;
            final Mapping createdMapping = mappingComponent.createMapping(tenant, mapping);
            if (Direction.OUTBOUND.equals(createdMapping.direction)) {
                mappingComponent.rebuildMappingOutboundCache(tenant, ConnectorId.INTERNAL);
            } else {
                // FIXME Currently we create mappings in ALL connectors assuming they could
                // occur in all of them.
                Map<String, AConnectorClient> clients = connectorRegistry.getClientsForTenant(tenant);
                clients.keySet().stream().forEach(connector -> {
                    clients.get(connector).updateSubscriptionForInbound(createdMapping, true, false);
                });
                mappingComponent.removeMappingInboundFromResolver(tenant, createdMapping);
                mappingComponent.addMappingInboundToResolver(tenant, createdMapping);
                mappingComponent.addMappingInboundToCache(tenant, createdMapping.id, mapping);
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
    @PreAuthorize("hasAnyRole('ROLE_DYNAMIC_MAPPER_ADMIN', 'ROLE_DYNAMIC_MAPPER_CREATE')")
    @PutMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Mapping> updateMapping(@PathVariable String id, @Valid @RequestBody Mapping mapping) {
        String tenant = contextService.getContext().getTenant();
        try {
            log.info("{} - Update mapping: {}, {}", mapping, id);
            final Mapping updatedMapping = mappingComponent.updateMapping(tenant, mapping, false, false);
            if (Direction.OUTBOUND.equals(mapping.direction)) {
                mappingComponent.rebuildMappingOutboundCache(tenant, ConnectorId.INTERNAL);
            } else {
                Map<String, AConnectorClient> clients = connectorRegistry.getClientsForTenant(tenant);
                clients.keySet().stream().forEach(connector -> {
                    clients.get(connector).updateSubscriptionForInbound(updatedMapping, false, false);
                });
                mappingComponent.removeMappingInboundFromResolver(tenant, mapping);
                mappingComponent.addMappingInboundToResolver(tenant, mapping);
                mappingComponent.addMappingInboundToCache(tenant, mapping.id, mapping);
            }
            return ResponseEntity.status(HttpStatus.OK).body(mapping);
        } catch (Exception ex) {
            if (ex instanceof IllegalArgumentException) {
                log.error("{} - Updating active mappings is not allowed", tenant, ex);
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