/*
 * Copyright (c) 2022-2026 Cumulocity GmbH.
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

package dynamic.mapper.processor.inbound.processor;

import static com.dashjoin.jsonata.Jsonata.jsonata;
import static dynamic.mapper.model.Substitution.toPrettyJsonString;

import org.springframework.stereotype.Component;

import dynamic.mapper.core.ServiceRegistry;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.processor.runtime.ProcessingContext;
import dynamic.mapper.util.Utils;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class FilterInboundProcessor extends BaseProcessor {

    final ServiceRegistry serviceRegistry;

    public FilterInboundProcessor(ServiceRegistry serviceRegistry) {
        this.serviceRegistry = serviceRegistry;
    }

    public void process(ProcessingContext<Object> context) throws Exception {
        applyFilter(context);
    }

    private void applyFilter(ProcessingContext<Object> context) {
        String tenant = context.getTenant();
        Mapping mapping = context.getMapping();
        String mappingFilter = mapping.getFilterMapping();

        if (mappingFilter != null && !mappingFilter.isBlank()) {
            Object payloadObjectNode = context.getPayload();

            // Byte-payload mapping types (ANY_PAYLOAD, PROTOBUF_INTERNAL) intentionally leave the
            // payload undeserialized here - parsing is done later by the extension/protobuf processor
            // itself. JSONata cannot evaluate a raw byte[] (it only accepts JSON values/Map/List), so
            // filtering against it would always throw and fail the message closed. Skip filtering in
            // that case; extensions can still gate processing themselves via context.addWarning()/
            // setIgnoreFurtherProcessing() inside onMessage().
            if (payloadObjectNode instanceof byte[]) {
                if (mapping.getDebug()) {
                    log.info("{} - Inbound mapping {}/{} skipping filter evaluation - payload not yet deserialized (raw byte[])",
                            tenant, mapping.getName(), mapping.getIdentifier());
                }
                return;
            }

            String payload = toPrettyJsonString(payloadObjectNode);
            try {
                var expr = jsonata(mappingFilter);
                Object extractedSourceContent = expr.evaluate(payloadObjectNode);
                if (!Utils.isNodeTrue(extractedSourceContent)) {
                    if (mapping.getDebug()) {
                        log.info("{} - Inbound mapping {}/{} filtered out - message filter mismatch: filter={}, payload={}",
                                tenant, mapping.getName(), mapping.getIdentifier(), mappingFilter, payload);
                    }
                    context.getWarnings().add("Payload will be ignored due to filter: " + mappingFilter);
                    context.setIgnoreFurtherProcessing(true);
                }
            } catch (Exception e) {
                // A filter that fails to evaluate (e.g. malformed expression) must not silently let
                // messages through - fail closed and surface it, matching the outbound path's behavior
                // (MappingResolverService.evaluateMessageFilter/evaluateInventoryFilter treat any
                // evaluation error as "does not match").
                log.error("{} - Inbound mapping {}/{} filter evaluation failed - message will be ignored: filter={}, payload={}",
                        tenant, mapping.getName(), mapping.getIdentifier(), mappingFilter, payload, e);
                context.getWarnings().add("Payload will be ignored - filter evaluation failed: " + mappingFilter);
                context.setIgnoreFurtherProcessing(true);
            }
        }

    }

}