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

import com.cumulocity.model.idtype.GId;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import dynamic.mapper.connector.core.client.AConnectorClient;
import dynamic.mapper.connector.core.registry.ConnectorRegistry;
import dynamic.mapper.core.C8YAgent;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.MappingStatus;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.model.DynamicMapperRequest;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.service.MappingService;
import dynamic.mapper.util.Utils;
import lombok.extern.slf4j.Slf4j;

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
        Boolean testing = context.isTesting();
        Mapping mapping = context.getMapping();

        try {
            // Process all C8Y requests that were created by SubstitutionProcessor
            String connectorIdentifier = exchange.getIn().getHeader("connectorIdentifier", String.class);
            processAndPrepareRequests(context, connectorIdentifier);

            // Create alarms for any processing issues
            createProcessingAlarms(context);

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
     * Process and send all C8Y requests created during substitution
     */
    private void processAndPrepareRequests(ProcessingContext<Object> context, String connectorIdentifier) {
        String tenant = context.getTenant();
        Mapping mapping = context.getMapping();

        // Process each C8Y request

        try {
            AConnectorClient connectorClient = connectorRegistry.getClientForTenant(tenant, connectorIdentifier);
            DynamicMapperRequest request = context.getCurrentRequest();
            if (request == null) {
                // Create a placeholder request to avoid further processing
                request = createAndAddDynamicMapperRequest(context, context.getMapping().getTargetTemplate(), null, null, null,
                        context.getMapping());
            }
            if (connectorClient.isConnected() && context.isSendPayload()) {
                connectorClient.publishMEAO(context);
            } else {
                log.warn("{} - Not sending message: connected {}, sendPayload {}", tenant,
                        connectorClient.isConnected(), context.isSendPayload());
            }

            // Log if debug is enabled
            if (mapping.getDebug() || context.getServiceConfiguration().isLogPayload()) {
                log.info("{} - Transformed message sent: API: {}, message: {}",
                        tenant, request.getApi(), request.getRequest());
            }

        } catch (Exception e) {
            context.getCurrentRequest().setError(e);
            log.error("{} - Error during publishing outbound message: ", tenant, e);
            log.error("{} - Failed to process request: {}", tenant, e.getMessage(), e);
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