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

package dynamic.mapper.processor;

import org.apache.camel.Exchange;
import org.springframework.beans.factory.annotation.Autowired;

import com.fasterxml.jackson.databind.ObjectMapper;

import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.MappingStatus;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.service.MappingService;
import lombok.extern.slf4j.Slf4j;

/**
 * Abstract base class for Snooping processors that provides common functionality
 * for capturing and storing payload templates during snooping mode.
 *
 * Snooping mode allows capturing actual device payloads to help with mapping configuration.
 * When snooping is active, payloads are serialized and stored in the mapping's snooped templates.
 */
@Slf4j
public abstract class AbstractSnoopingProcessor extends CommonProcessor {

    @Autowired
    protected MappingService mappingService;

    @Autowired
    protected ObjectMapper objectMapper;

    @Override
    public void process(Exchange exchange) throws Exception {
        ProcessingContext<?> context = exchange.getIn().getHeader("processingContext", ProcessingContext.class);
        Mapping mapping = context.getMapping();
        String tenant = context.getTenant();

        handleSnooping(tenant, mapping, context);

        // Mark context to skip further processing
        context.setIgnoreFurtherProcessing(true);
    }

    /**
     * Handle snooping by serializing the payload and adding it to the mapping's snooped templates.
     * Updates the mapping status counters and marks the mapping as dirty for persistence.
     *
     * @param tenant The tenant identifier
     * @param mapping The mapping configuration
     * @param context The processing context containing the payload
     */
    protected void handleSnooping(String tenant, Mapping mapping, ProcessingContext<?> context) {
        try {
            MappingStatus mappingStatus = mappingService.getMappingStatus(tenant, mapping);

            String serializedPayload = objectMapper.writeValueAsString(context.getPayload());
            if (serializedPayload != null) {
                mapping.addSnoopedTemplate(serializedPayload);
                mappingStatus.snoopedTemplatesTotal = mapping.getSnoopedTemplates().size();
                mappingStatus.snoopedTemplatesActive++;

                log.debug("{} - Adding snoopedTemplate to map: {},{},{}",
                        tenant, mapping.getMappingTopic(), mapping.getSnoopedTemplates().size(),
                        mapping.getSnoopStatus());
                mappingService.addDirtyMapping(tenant, mapping);
            } else {
                log.warn("{} - Message could NOT be serialized for snooping", tenant);
            }
        } catch (Exception e) {
            log.warn("{} - Error during snooping: {}", tenant, e.getMessage());
            log.debug("{} - Snooping error details:", tenant, e);
            return;
        }
    }
}
