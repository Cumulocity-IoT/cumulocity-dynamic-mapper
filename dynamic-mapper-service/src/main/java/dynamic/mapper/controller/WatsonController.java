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

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.cumulocity.microservice.context.ContextService;
import com.cumulocity.microservice.context.credentials.UserCredentials;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

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
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequestMapping("/watson/mapping")

@RestController
public class WatsonController {

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

    private ObjectMapper objectMapper;

    @Autowired
    public void setObjectMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PreAuthorize("hasAnyRole('ROLE_DYNAMIC_MAPPER_ADMIN', 'ROLE_DYNAMIC_MAPPER_CREATE')")
    @PostMapping(consumes = MediaType.TEXT_PLAIN_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Mapping> createMapping(@RequestBody String textBody) {
        try {
            String tenant = contextService.getContext().getTenant();
            String processedText = textBody
                    .replace("\"[", "[") // 1. replace "[ with [
                    .replace("]\"", "]") // 2. replace ]" with ]
                    .replace("\\\\\"", "___\"") // 3. replace \\\" with ___"
                    .replace("\\\"", "\"") // 4. replace \" with "
                    .replace("___\"", "\""); // 5. replace ___" with \"
            Mapping mapping = objectMapper.readValue(processedText, Mapping.class);
            log.info("{} - Adding mapping: {}", tenant, mapping.getMappingTopic());
            log.debug("{} - Adding mapping: {}", tenant, mapping);
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

}