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

package dynamic.mapper.service;

import dynamic.mapper.model.Direction;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.service.cache.MappingCacheManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Handles snooping operations for mappings
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MappingSnoopService {

    private final MappingCacheManager cacheManager;

    /**
     * Applies snooped templates from cache to mapping
     */
    public void applySnoopedTemplates(String tenant, Mapping mapping) {
        if (Direction.INBOUND.equals(mapping.getDirection())) {
            cacheManager.getInboundMapping(tenant, mapping.getId())
                .ifPresent(cached -> mapping.setSnoopedTemplates(cached.getSnoopedTemplates()));
        } else {
            cacheManager.getOutboundMapping(tenant, mapping.getId())
                .ifPresent(cached -> mapping.setSnoopedTemplates(cached.getSnoopedTemplates()));
        }
        
        log.debug("{} - Applied {} snooped templates to mapping {}", 
            tenant, mapping.getSnoopedTemplates().size(), mapping.getId());
    }
}
