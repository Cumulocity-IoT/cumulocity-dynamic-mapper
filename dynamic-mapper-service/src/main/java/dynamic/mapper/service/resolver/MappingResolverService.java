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

package dynamic.mapper.service.resolver;

import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.model.API;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.ResolveException;
import dynamic.mapper.processor.model.C8YMessage;
import dynamic.mapper.service.cache.MappingCacheManager;
import dynamic.mapper.util.Utils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.dashjoin.jsonata.Jsonata.jsonata;

/**
 * Service for resolving which mappings apply to messages
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MappingResolverService {

    private final MappingCacheManager cacheManager;
    private final ConfigurationRegistry configurationRegistry;

    /**
     * Resolves inbound mappings by topic
     */
    public List<Mapping> resolveInbound(String tenant, String topic) throws ResolveException {
        List<Mapping> resolved = cacheManager.resolveInboundMappings(tenant, topic);
        log.debug("{} - Resolved {} inbound mappings for topic: {}", tenant, resolved.size(), topic);
        return resolved;
    }

    /**
     * Resolves outbound mappings for a C8Y message
     */
    public List<Mapping> resolveOutbound(String tenant, C8YMessage message) throws ResolveException {
        List<Mapping> result = new ArrayList<>();
        API api = message.getApi();

        try {
            Map<String, Mapping> allMappings = cacheManager.getAllOutboundMappings(tenant);

            for (Mapping mapping : allMappings.values()) {
                if (shouldProcessMapping(tenant, mapping, message, api)) {
                    result.add(mapping);
                }
            }

            log.debug("{} - Resolved {} outbound mappings for API: {}", tenant, result.size(), api);
            return result;

        } catch (IllegalArgumentException e) {
            throw new ResolveException("Failed to resolve outbound mappings: " + e.getMessage(), e);
        }
    }

    // ========== Private Helper Methods ==========

    private Boolean shouldProcessMapping(String tenant, Mapping mapping, C8YMessage message, API api) {
        // Check if mapping is active and API matches
        if (!mapping.getActive() || !mapping.getTargetAPI().equals(api)) {
            logMappingSkipped(tenant, mapping, "inactive or API mismatch", 
                String.format("active=%s, expectedAPI=%s, actualAPI=%s", 
                    mapping.getActive(), mapping.getTargetAPI(), api));
            return false;
        }

        // Check message filter
        if (!mapping.getFilterMapping().isBlank()) {
            if (!evaluateMessageFilter(tenant, mapping, message)) {
                return false;
            }
        }

        // Check inventory filter
        if (mapping.getFilterInventory() != null && !mapping.getFilterInventory().isBlank()) {
            if (!evaluateInventoryFilter(tenant, mapping, message)) {
                return false;
            }
        }

        return true;
    }

    private Boolean evaluateMessageFilter(String tenant, Mapping mapping, C8YMessage message) {
        try {
            var expression = jsonata(mapping.getFilterMapping());
            Object result = expression.evaluate(message.getParsedPayload());

            boolean matches = result != null && Utils.isNodeTrue(result);

            if (matches) {
                log.debug("{} - Message filter matched for mapping: {}", tenant, mapping.getIdentifier());
            } else {
                logMappingSkipped(tenant, mapping, "message filter failed", 
                    String.format("filter=%s, result=%s", mapping.getFilterMapping(), result));
            }

            return matches;

        } catch (Exception e) {
            log.debug("{} - Message filter evaluation error for mapping {}: {}", 
                tenant, mapping.getIdentifier(), e.getMessage());
            return false;
        }
    }

    public boolean evaluateInventoryFilter(String tenant, Mapping mapping, C8YMessage message) {
        String sourceId = message.getSourceId();
        
        if (sourceId == null) {
            logMappingSkipped(tenant, mapping, "inventory filter failed", "sourceId is null");
            return false;
        }

        try {
            Map<String, Object> inventoryData = configurationRegistry.getC8yAgent()
                .getMOFromInventoryCache(tenant, sourceId, false);

            log.debug("{} - Evaluating inventory filter for source {} with fragments: {}", 
                tenant, sourceId, inventoryData.keySet());

            var expression = jsonata(mapping.getFilterInventory());
            Object result = expression.evaluate(inventoryData);

            boolean matches = result != null && Utils.isNodeTrue(result);

            if (matches) {
                log.debug("{} - Inventory filter matched for mapping: {}", tenant, mapping.getIdentifier());
            } else {
                logMappingSkipped(tenant, mapping, "inventory filter failed",
                    String.format("filter=%s, sourceId=%s, result=%s", 
                        mapping.getFilterInventory(), sourceId, result));
            }

            return matches;

        } catch (Exception e) {
            log.debug("{} - Inventory filter evaluation error for mapping {}: {}", 
                tenant, mapping.getIdentifier(), e.getMessage());
            return false;
        }
    }

    private void logMappingSkipped(String tenant, Mapping mapping, String reason, String details) {
        if (mapping.getDebug()) {
            log.info("{} - Outbound mapping {}/{} not resolved - {}: {}", 
                tenant, mapping.getName(), mapping.getIdentifier(), reason, details);
        }
    }
}
