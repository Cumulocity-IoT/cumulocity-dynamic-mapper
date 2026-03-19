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
package dynamic.mapper.processor.outbound.processor;

import org.apache.camel.Exchange;
import org.joda.time.DateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.cumulocity.model.JSONBase;
import com.cumulocity.model.idtype.GId;
import com.cumulocity.model.operation.OperationStatus;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.cumulocity.rest.representation.operation.OperationRepresentation;
import dynamic.mapper.connector.core.client.AConnectorClient;
import dynamic.mapper.connector.core.client.ConnectorType;
import dynamic.mapper.connector.core.registry.ConnectorRegistry;
import dynamic.mapper.core.C8YAgent;
import dynamic.mapper.model.API;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.MappingStatus;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.service.MappingService;
import dynamic.mapper.util.Utils;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Component
public class SendOutboundProcessor extends BaseProcessor {

    @Autowired
    private C8YAgent c8yAgent;

    @Autowired
    private ConnectorRegistry connectorRegistry;

    @Autowired
    private MappingService mappingService;

    @Override
    @SuppressWarnings("unchecked")
    public void process(Exchange exchange) throws Exception {
        ProcessingContext<Object> context = exchange.getIn().getHeader("processingContext", ProcessingContext.class);

        String tenant = context.getTenant();
        Mapping mapping = context.getMapping();
        Boolean testing = context.getTesting();

        try {
            // Auto-acknowledge operation before sending
            autoAckOperation(context, tenant, mapping, OperationStatus.EXECUTING);

            // Process all C8Y requests that were created by SubstitutionProcessor
            String connectorIdentifier = exchange.getIn().getHeader("connectorIdentifier", String.class);
            processAndPrepareRequests(context, connectorIdentifier);


            // Create alarms for any processing issues
            createProcessingAlarms(context);

            //FIXME Is context.getErrors() sufficient or do we need to check also context.currentRequests.getErrors()?
            if(context.hasError())
                autoAckOperation(context, tenant, mapping, OperationStatus.FAILED);
            else
                autoAckOperation(context, tenant, mapping, OperationStatus.SUCCESSFUL);
        } catch (Exception e) {
            String errorMessage = String.format(
                    "Tenant %s - Error in SendOutboundProcessor: %s for mapping: %s",
                    tenant, mapping.getName(), e.getMessage());
            log.error(errorMessage, e);
            context.addError(new ProcessingException(errorMessage, e));

            if (!testing) {
                MappingStatus mappingStatus = mappingService.getMappingStatus(tenant, mapping);
                mappingStatus.errors++;
                mappingService.increaseAndHandleFailureCount(tenant, mapping, mappingStatus);
            }
        }

    }

    /**
     * Set operation status to EXECUTING before sending the outbound message.
     */
    private void autoAckOperation(ProcessingContext<Object> context, String tenant, Mapping mapping, OperationStatus operationStatus) {
        if (!API.OPERATION.equals(context.getApi()) || !Boolean.TRUE.equals(mapping.getAutoAckOperation())
                || Boolean.TRUE.equals(context.getTesting())) {
            return;
        }
        try {
            OperationRepresentation op = JSONBase.getJSONParser().parse(
                    OperationRepresentation.class, (String) context.getRawPayload());
            // Join error messages for the status update
            if(context.hasError()) {
                String errorMessage = context.getErrors().stream()
                        .map(Exception::getMessage)
                        .filter(Objects::nonNull)
                        .collect(Collectors.joining("; "));
                c8yAgent.updateOperationStatus(tenant, op, operationStatus, errorMessage);
            } else {
                c8yAgent.updateOperationStatus(tenant, op, operationStatus, null);
            }

        } catch (Exception e) {
            log.warn("{} - Failed to update operation status to EXECUTING: {}", tenant, e.getMessage());
        }
    }

    /**
     * Process and send all C8Y requests created during substitution
     */
    private void processAndPrepareRequests(ProcessingContext<Object> context, String connectorIdentifier) {
        String tenant = context.getTenant();
        Mapping mapping = context.getMapping();

        // Get all requests from the context
        var requests = context.getRequests();

        if (requests == null || requests.isEmpty()) {
            log.debug("{} - No requests to process, skipping send", tenant);
            return;
        }

        if (!context.getSendPayload()) {
            log.warn("{} - Not sending messages: sendPayload is false", tenant);
            return;
        }

        try {
            // Outbound testing with send=true: publish via all real (non-TEST) connectors
            // that have the mapping deployed, mirroring how inbound send=true uses real C8Y.
            // Identity enrichment still uses mocks (testing=true) since the source payload
            // is always synthetic for outbound tests.
            if (Boolean.TRUE.equals(context.getTesting())) {
                publishToRealConnectors(context, tenant);
                return;
            }

            AConnectorClient connectorClient = connectorRegistry.getClientForTenant(tenant, connectorIdentifier);

            if (!connectorClient.isConnected()) {
                log.warn("{} - Not sending messages: connector not connected", tenant);
                return;
            }

            // Publish all requests in a single call
            // The connector implementation will handle looping over requests
            connectorClient.publishMEAO(context);

            // Log if debug is enabled
            if (mapping.getDebug() || context.getServiceConfiguration().getLogPayload()) {
                log.info("{} - Published {} outbound message(s)", tenant, requests.size());
            }

        } catch (Exception e) {
            log.error("{} - Failed to process outbound requests: {}", tenant, e.getMessage(), e);
            if (context.getCurrentRequest() != null) {
                context.getCurrentRequest().setError(e);
            }
        }
    }

    /**
     * Publish to all real (non-TEST) connectors that have the mapping deployed and are connected.
     * Used for outbound "Send Test Message" so the payload reaches the actual broker.
     */
    private void publishToRealConnectors(ProcessingContext<Object> context, String tenant) {
        Mapping mapping = context.getMapping();
        Map<String, AConnectorClient> allClients;
        try {
            allClients = connectorRegistry.getClientsForTenant(tenant);
        } catch (Exception e) {
            log.error("{} - Failed to look up connectors for outbound test send: {}", tenant, e.getMessage(), e);
            return;
        }

        int publishCount = 0;
        for (AConnectorClient client : allClients.values()) {
            if (client.getConnectorType() == ConnectorType.TEST) {
                continue;
            }
            if (!client.isConnected()) {
                log.warn("{} - Skipping connector '{}': not connected", tenant, client.getConnectorName());
                continue;
            }
            if (!client.isMappingOutboundDeployed(mapping.getIdentifier())) {
                log.debug("{} - Mapping '{}' not deployed on connector '{}', skipping",
                        tenant, mapping.getName(), client.getConnectorName());
                continue;
            }
            try {
                client.publishMEAO(context);
                publishCount++;
                log.info("{} - Published outbound test message via connector: {}", tenant, client.getConnectorName());
            } catch (Exception e) {
                log.error("{} - Failed to publish via connector '{}': {}", tenant, client.getConnectorName(), e.getMessage(), e);
            }
        }

        if (publishCount == 0) {
            log.warn("{} - No connected real connector found to publish outbound test message for mapping: {}",
                    tenant, mapping.getName());
        }
    }

    /**
     * Create alarms for any processing issues
     */
    private void createProcessingAlarms(ProcessingContext<Object> context) {
        String tenant = context.getTenant();

        if (context.getSourceId() != null && !context.getAlarms().isEmpty()) {
            ManagedObjectRepresentation sourceMor = new ManagedObjectRepresentation();
            sourceMor.setId(new GId(context.getSourceId()));

            context.getAlarms().forEach(alarm -> {
                try {
                    c8yAgent.createAlarm("WARNING", alarm, Utils.MAPPER_PROCESSING_ALARM,
                            new DateTime(), sourceMor, tenant);
                } catch (Exception e) {
                    log.warn("{} - Failed to create processing alarm: {}", tenant, e.getMessage());
                }
            });
        }
    }

}