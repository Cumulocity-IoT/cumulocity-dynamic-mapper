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

import com.dashjoin.jsonata.Jsonata;

import dynamic.mapper.core.ServiceRegistry;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.processor.inbound.MessageDeduplicationService;
import dynamic.mapper.processor.runtime.ProcessingContext;
import dynamic.mapper.util.Utils;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class FilterInboundProcessor extends BaseProcessor {

    final ServiceRegistry serviceRegistry;

    final MessageDeduplicationService deduplicationService;

    public FilterInboundProcessor(ServiceRegistry serviceRegistry,
            MessageDeduplicationService deduplicationService) {
        this.serviceRegistry = serviceRegistry;
        this.deduplicationService = deduplicationService;
    }

    public void process(ProcessingContext<Object> context) throws Exception {
        applyFilter(context);
    }

    /**
     * Registers {@code $isDuplicate(key, windowSeconds)} for the mapping filter (issue #692).
     *
     * <p>It returns true if the same key was seen for this mapping less than {@code windowSeconds} ago, so a
     * mapping drops repeats with {@code $not($isDuplicate(deviceId & "|" & time, 60))}. The check fails open:
     * a missing key or an invalid window returns false and logs a warning. Test runs never record a key.</p>
     */
    private void registerIsDuplicate(Jsonata expression, ProcessingContext<Object> context) {
        Mapping mapping = context.getMapping();
        String tenant = context.getTenant();
        expression.registerFunction("isDuplicate", (Jsonata.Fn2<Object, Object, Object>) (key, windowArg) -> {
            if (!context.affectsMappingStatus()) {
                return false;
            }
            if (key == null) {
                log.warn("{} - Inbound mapping {}/{}: $isDuplicate key has no value, message is processed without a check",
                        tenant, mapping.getName(), mapping.getIdentifier());
                return false;
            }
            int window = windowArg instanceof Number n
                    ? MessageDeduplicationService.clampWindowSeconds(n.intValue())
                    : 0;
            if (window == 0) {
                log.warn("{} - Inbound mapping {}/{}: $isDuplicate window must be a number of seconds from {} to {}, message is processed without a check",
                        tenant, mapping.getName(), mapping.getIdentifier(),
                        MessageDeduplicationService.MIN_WINDOW_SECONDS, MessageDeduplicationService.MAX_WINDOW_SECONDS);
                return false;
            }
            String keyText = key instanceof CharSequence || key instanceof Number || key instanceof Boolean
                    ? String.valueOf(key)
                    : toPrettyJsonString(key);
            String fingerprint = MessageDeduplicationService.fingerprint(keyText);
            boolean duplicate = deduplicationService.isDuplicate(tenant, mapping.getIdentifier(), fingerprint, window);
            if (duplicate && mapping.getDebug()) {
                log.info("{} - Inbound mapping {}/{} dropped a repeated message: key={}, window={}s",
                        tenant, mapping.getName(), mapping.getIdentifier(), keyText, window);
            }
            if (duplicate) {
                context.setDroppedAsDuplicate(true);
            }
            return duplicate;
        });
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
                registerIsDuplicate(expr, context);
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