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

package dynamic.mapper.service.cache;

import dynamic.mapper.configuration.ConnectorId;
import dynamic.mapper.model.Direction;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.MappingTreeNode;
import dynamic.mapper.model.ResolveException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Manages caching for mappings with thread-safe operations
 */
@Slf4j
@Component
public class MappingCacheManager {

    // Structure: <Tenant, <MappingId, Mapping>>
    private final Map<String, Map<String, Mapping>> cacheMappingInbound = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Mapping>> cacheMappingOutbound = new ConcurrentHashMap<>();

    // Structure: <Tenant, <FilterMapping, List<Mapping>>>
    private final Map<String, Map<String, List<Mapping>>> resolverMappingOutbound = new ConcurrentHashMap<>();

    // Structure: <Tenant, MappingTreeNode>
    private final Map<String, MappingTreeNode> resolverMappingInbound = new ConcurrentHashMap<>();

    /**
     * Initializes cache structures for a tenant
     */
    public void createTenantCache(String tenant) {
        cacheMappingInbound.put(tenant, new ConcurrentHashMap<>());
        cacheMappingOutbound.put(tenant, new ConcurrentHashMap<>());
        resolverMappingOutbound.put(tenant, new ConcurrentHashMap<>());
        resolverMappingInbound.put(tenant, MappingTreeNode.createRootNode(tenant));
        
        log.debug("{} - Cache structures created", tenant);
    }

    /**
     * Removes all cache data for a tenant
     */
    public void removeTenantCache(String tenant) {
        cacheMappingInbound.remove(tenant);
        cacheMappingOutbound.remove(tenant);
        resolverMappingInbound.remove(tenant);
        resolverMappingOutbound.remove(tenant);
        
        log.debug("{} - Cache structures removed", tenant);
    }

    /**
     * Clears all caches for a tenant without removing the structure
     */
    public void clearTenantCache(String tenant) {
        getCacheInbound(tenant).clear();
        getCacheOutbound(tenant).clear();
        resolverMappingOutbound.get(tenant).clear();
        resolverMappingInbound.put(tenant, MappingTreeNode.createRootNode(tenant));
        
        log.debug("{} - Cache cleared", tenant);
    }

    // ========== Inbound Cache Operations ==========

    /**
     * Rebuilds the entire inbound cache from a list of mappings
     */
    public List<Mapping> rebuildInboundCache(String tenant, List<Mapping> mappings, ConnectorId connectorId) {
        log.info("{} - Rebuilding inbound cache with {} mappings (triggered by {})", 
            tenant, mappings.size(), connectorId.getName());

        Map<String, Mapping> newCache = mappings.stream()
            .collect(Collectors.toMap(Mapping::getId, Function.identity()));
        
        cacheMappingInbound.put(tenant, new ConcurrentHashMap<>(newCache));

        // Rebuild resolver tree
        MappingTreeNode newTree = buildMappingTree(tenant, mappings);
        resolverMappingInbound.put(tenant, newTree);

        return mappings;
    }

    /**
     * Adds a single mapping to the inbound cache
     */
    public void addInboundMapping(String tenant, Mapping mapping) {
        getCacheInbound(tenant).put(mapping.getId(), mapping);
        
        try {
            getResolverTreeInbound(tenant).addMapping(mapping);
            log.debug("{} - Added inbound mapping to cache: {}", tenant, mapping.getId());
        } catch (ResolveException e) {
            log.error("{} - Failed to add mapping {} to resolver tree", tenant, mapping.getId(), e);
        }
    }

    /**
     * Removes a mapping from the inbound cache
     */
    public Optional<Mapping> removeInboundMapping(String tenant, String mappingId) {
        Mapping removed = getCacheInbound(tenant).remove(mappingId);
        
        if (removed != null) {
            try {
                getResolverTreeInbound(tenant).deleteMapping(removed);
                log.debug("{} - Removed inbound mapping from cache: {}", tenant, mappingId);
            } catch (ResolveException e) {
                log.error("{} - Failed to remove mapping {} from resolver tree", tenant, mappingId, e);
            }
        }
        
        return Optional.ofNullable(removed);
    }

    /**
     * Retrieves a mapping from the inbound cache
     */
    public Optional<Mapping> getInboundMapping(String tenant, String mappingId) {
        return Optional.ofNullable(getCacheInbound(tenant).get(mappingId));
    }

    /**
     * Checks if an inbound mapping exists in cache
     */
    public boolean containsInboundMapping(String tenant, String mappingId) {
        return getCacheInbound(tenant).containsKey(mappingId);
    }

    /**
     * Gets all inbound mappings for a tenant
     */
    public Map<String, Mapping> getAllInboundMappings(String tenant) {
        return new HashMap<>(getCacheInbound(tenant));
    }

    /**
     * Resolves inbound mappings by topic
     */
    public List<Mapping> resolveInboundMappings(String tenant, String topic) throws ResolveException {
        return getResolverTreeInbound(tenant).resolveMapping(topic);
    }

    // ========== Outbound Cache Operations ==========

    /**
     * Rebuilds the entire outbound cache from a list of mappings
     */
    public List<Mapping> rebuildOutboundCache(String tenant, List<Mapping> mappings, ConnectorId connectorId) {
        log.info("{} - Rebuilding outbound cache with {} mappings (triggered by {})", 
            tenant, mappings.size(), connectorId.getName());

        Map<String, Mapping> newCache = mappings.stream()
            .collect(Collectors.toMap(Mapping::getId, Function.identity()));
        
        cacheMappingOutbound.put(tenant, new ConcurrentHashMap<>(newCache));

        // Rebuild resolver map
        Map<String, List<Mapping>> newResolver = mappings.stream()
            .filter(m -> m.getFilterMapping() != null)
            .collect(Collectors.groupingBy(Mapping::getFilterMapping));
        
        resolverMappingOutbound.put(tenant, new ConcurrentHashMap<>(newResolver));

        return mappings;
    }

    /**
     * Adds a single mapping to the outbound cache
     */
    public void addOutboundMapping(String tenant, Mapping mapping) {
        getCacheOutbound(tenant).put(mapping.getId(), mapping);
        
        if (mapping.getFilterMapping() != null) {
            resolverMappingOutbound.get(tenant)
                .computeIfAbsent(mapping.getFilterMapping(), k -> new ArrayList<>())
                .add(mapping);
        }
        
        log.debug("{} - Added outbound mapping to cache: {}", tenant, mapping.getId());
    }

    /**
     * Removes a mapping from the outbound cache
     */
    public Optional<Mapping> removeOutboundMapping(String tenant, String mappingId) {
        Mapping removed = getCacheOutbound(tenant).remove(mappingId);
        
        if (removed != null && removed.getFilterMapping() != null) {
            List<Mapping> mappingsForFilter = resolverMappingOutbound.get(tenant)
                .get(removed.getFilterMapping());
            
            if (mappingsForFilter != null) {
                mappingsForFilter.removeIf(m -> m.getId().equals(mappingId));
                log.debug("{} - Removed outbound mapping from cache: {}", tenant, mappingId);
            }
        }
        
        return Optional.ofNullable(removed);
    }

    /**
     * Retrieves a mapping from the outbound cache
     */
    public Optional<Mapping> getOutboundMapping(String tenant, String mappingId) {
        return Optional.ofNullable(getCacheOutbound(tenant).get(mappingId));
    }

    /**
     * Checks if an outbound mapping exists in cache
     */
    public boolean containsOutboundMapping(String tenant, String mappingId) {
        return getCacheOutbound(tenant).containsKey(mappingId);
    }

    /**
     * Gets all outbound mappings for a tenant
     */
    public Map<String, Mapping> getAllOutboundMappings(String tenant) {
        return new HashMap<>(getCacheOutbound(tenant));
    }

    /**
     * Gets all outbound mappings matching a filter
     */
    public List<Mapping> getOutboundMappingsByFilter(String tenant, String filter) {
        List<Mapping> mappings = resolverMappingOutbound.get(tenant).get(filter);
        return mappings != null ? new ArrayList<>(mappings) : Collections.emptyList();
    }

    // ========== Generic Operations ==========

    /**
     * Removes a mapping from the appropriate cache based on direction
     */
    public Optional<Mapping> removeMapping(String tenant, Mapping mapping) {
        if (Direction.OUTBOUND.equals(mapping.getDirection())) {
            return removeOutboundMapping(tenant, mapping.getId());
        } else {
            return removeInboundMapping(tenant, mapping.getId());
        }
    }

    /**
     * Adds a mapping to the appropriate cache based on direction
     */
    public void addMapping(String tenant, Mapping mapping) {
        if (Direction.OUTBOUND.equals(mapping.getDirection())) {
            addOutboundMapping(tenant, mapping);
        } else {
            addInboundMapping(tenant, mapping);
        }
    }

    /**
     * Gets a mapping from either cache
     */
    public Optional<Mapping> getMapping(String tenant, String mappingId) {
        Optional<Mapping> inbound = getInboundMapping(tenant, mappingId);
        return inbound.isPresent() ? inbound : getOutboundMapping(tenant, mappingId);
    }

    // ========== Helper Methods ==========

    private Map<String, Mapping> getCacheInbound(String tenant) {
        return cacheMappingInbound.computeIfAbsent(tenant, k -> new ConcurrentHashMap<>());
    }

    private Map<String, Mapping> getCacheOutbound(String tenant) {
        return cacheMappingOutbound.computeIfAbsent(tenant, k -> new ConcurrentHashMap<>());
    }

    private MappingTreeNode getResolverTreeInbound(String tenant) {
        return resolverMappingInbound.computeIfAbsent(tenant, MappingTreeNode::createRootNode);
    }

    private MappingTreeNode buildMappingTree(String tenant, List<Mapping> mappings) {
        MappingTreeNode tree = MappingTreeNode.createRootNode(tenant);
        
        for (Mapping mapping : mappings) {
            try {
                tree.addMapping(mapping);
            } catch (ResolveException e) {
                log.error("{} - Could not add mapping {} to tree, skipping", tenant, mapping.getId(), e);
            }
        }
        
        return tree;
    }

    public MappingTreeNode getResolverMappingInbound(String tenant) {
        return getResolverTreeInbound(tenant);
    }
}
