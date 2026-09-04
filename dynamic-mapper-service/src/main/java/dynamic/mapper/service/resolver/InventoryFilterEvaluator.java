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

import static com.dashjoin.jsonata.Jsonata.jsonata;

import java.util.Map;

import org.springframework.stereotype.Service;

import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.util.Utils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Evaluates a mapping's {@code filterInventory} JSONata expression against the cached
 * managed object for a device. Shared by both the inbound result processors
 * ({@code CommonProcessor}) and the outbound resolver ({@code MappingResolverService}),
 * which previously each carried their own copy of this logic.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryFilterEvaluator {

    private final ConfigurationRegistry configurationRegistry;

    /**
     * @param filterExpression the JSONata expression to evaluate; a null/blank expression
     *                         means "no filter" and always matches
     * @param sourceId         the managed object id to look up in the inventory cache
     * @param testing          whether this evaluation happens in a test/dry-run context
     * @return true if there is no filter, or the filter matches the cached inventory content;
     *         false if sourceId is null, the filter does not match, or evaluation fails
     */
    public boolean evaluate(String tenant, String filterExpression, String sourceId, boolean testing) {
        if (filterExpression == null || filterExpression.isBlank()) {
            return true;
        }
        if (sourceId == null) {
            log.debug("{} - Inventory filter evaluation skipped: sourceId is null, filter={}",
                    tenant, filterExpression);
            return false;
        }
        try {
            Map<String, Object> cachedInventoryContent = configurationRegistry.getC8yAgent()
                    .getMOFromInventoryCache(tenant, sourceId, testing);
            log.debug("{} - Evaluating inventory filter for source {} with fragments: {}",
                    tenant, sourceId, cachedInventoryContent.keySet());

            var expression = jsonata(filterExpression);
            Object result = expression.evaluate(cachedInventoryContent);
            boolean matches = result != null && Utils.isNodeTrue(result);

            if (matches) {
                log.debug("{} - Inventory filter matched: filter={}, sourceId={}", tenant, filterExpression,
                        sourceId);
            } else {
                log.debug("{} - Inventory filter did not match: filter={}, sourceId={}, result={}",
                        tenant, filterExpression, sourceId, result);
            }
            return matches;
        } catch (Exception e) {
            log.debug("{} - Inventory filter evaluation error for {}: {}", tenant, filterExpression, e.getMessage());
            return false;
        }
    }
}
