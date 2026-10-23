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

package dynamic.mapper.mapping.cache;

import dynamic.mapper.configuration.ConnectorId;
import dynamic.mapper.model.Direction;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.MappingTreeNode;
import dynamic.mapper.model.validation.ResolveException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages caching for mappings with thread-safe operations.
 *
 * <p>Per tenant, the inbound side (mappings by id, by stable identifier, and the topic resolver
 * tree) lives in one {@link InboundState} that a rebuild swaps in a single volatile write, so a
 * reader never sees the map and the tree out of sync. Incremental changes are serialized per
 * tenant. Getters never create tenant state; only {@link #createTenantCache} and the rebuilds do,
 * so a late call after {@link #removeTenantCache} cannot resurrect (and leak) a removed tenant.
 */
@Slf4j
@Component
public class MappingCacheManager {

    private static final class InboundState {
        final Map<String, Mapping> byId = new ConcurrentHashMap<>();
        final Map<String, Mapping> byIdentifier = new ConcurrentHashMap<>();
        final MappingTreeNode tree;

        InboundState(MappingTreeNode tree) {
            this.tree = tree;
        }
    }

    private static final class OutboundState {
        final Map<String, Mapping> byId = new ConcurrentHashMap<>();
        final Map<String, Mapping> byIdentifier = new ConcurrentHashMap<>();
    }

    private static final class TenantCache {
        // Serializes incremental changes and rebuilds of this tenant
        final Object lock = new Object();
        volatile InboundState inbound;
        volatile OutboundState outbound = new OutboundState();

        TenantCache(String tenant) {
            this.inbound = new InboundState(MappingTreeNode.createRootNode(tenant));
        }
    }

    // Structure: <Tenant, TenantCache>
    private final Map<String, TenantCache> tenants = new ConcurrentHashMap<>();

    /**
     * Initializes cache structures for a tenant (idempotent)
     */
    public void createTenantCache(String tenant) {
        tenants.computeIfAbsent(tenant, TenantCache::new);
        log.debug("{} - Cache structures created", tenant);
    }

    /**
     * Removes all cache data for a tenant
     */
    public void removeTenantCache(String tenant) {
        tenants.remove(tenant);
        log.debug("{} - Cache structures removed", tenant);
    }

    /**
     * Clears all caches for a tenant without removing the structure
     */
    public void clearTenantCache(String tenant) {
        TenantCache tc = tenants.get(tenant);
        if (tc == null) {
            return;
        }
        synchronized (tc.lock) {
            tc.inbound = new InboundState(MappingTreeNode.createRootNode(tenant));
            tc.outbound = new OutboundState();
        }
        log.debug("{} - Cache cleared", tenant);
    }

    // ========== Inbound Cache Operations ==========

    /**
     * Rebuilds the entire inbound cache from a list of mappings. The new state is built off to
     * the side and swapped in atomically.
     */
    public List<Mapping> rebuildInboundCache(String tenant, List<Mapping> mappings, ConnectorId connectorId) {
        log.debug("{} - Rebuilding inbound cache with {} mappings (triggered by {})",
                tenant, mappings.size(), connectorId.getName());

        InboundState state = new InboundState(MappingTreeNode.createRootNode(tenant));
        for (Mapping mapping : mappings) {
            // Last one wins on duplicate ids instead of failing the whole rebuild
            index(state.byId, state.byIdentifier, mapping);
            try {
                state.tree.addMapping(mapping);
            } catch (ResolveException e) {
                log.error("{} - Could not add mapping {} to tree, skipping", tenant, mapping.getId(), e);
            }
        }

        TenantCache tc = tenants.computeIfAbsent(tenant, TenantCache::new);
        synchronized (tc.lock) {
            tc.inbound = state;
        }
        return mappings;
    }

    /**
     * Adds or replaces a single mapping in the inbound cache. A previous version of the same
     * mapping (even with a different topic) is swapped out under one tree lock.
     */
    public void addInboundMapping(String tenant, Mapping mapping) {
        TenantCache tc = tenants.get(tenant);
        if (tc == null) {
            log.warn("{} - No mapping cache for tenant, ignoring inbound mapping {}", tenant, mapping.getId());
            return;
        }
        synchronized (tc.lock) {
            InboundState state = tc.inbound;
            Mapping previous = state.byId.get(mapping.getId());
            try {
                state.tree.replaceMapping(previous, mapping);
            } catch (ResolveException e) {
                log.error("{} - Failed to add mapping {} to resolver tree", tenant, mapping.getId(), e);
            }
            unindex(state.byId, state.byIdentifier, previous);
            index(state.byId, state.byIdentifier, mapping);
        }
        log.debug("{} - Added inbound mapping to cache: {}", tenant, mapping.getId());
    }

    /**
     * Removes a mapping from the inbound cache
     */
    public Optional<Mapping> removeInboundMapping(String tenant, String mappingId) {
        TenantCache tc = tenants.get(tenant);
        if (tc == null) {
            return Optional.empty();
        }
        Mapping removed;
        synchronized (tc.lock) {
            InboundState state = tc.inbound;
            removed = state.byId.get(mappingId);
            if (removed != null) {
                try {
                    state.tree.deleteMapping(removed);
                } catch (ResolveException e) {
                    log.error("{} - Failed to remove mapping {} from resolver tree", tenant, mappingId, e);
                }
                unindex(state.byId, state.byIdentifier, removed);
                log.debug("{} - Removed inbound mapping from cache: {}", tenant, mappingId);
            }
        }
        return Optional.ofNullable(removed);
    }

    /**
     * Retrieves a mapping from the inbound cache
     */
    public Optional<Mapping> getInboundMapping(String tenant, String mappingId) {
        return inbound(tenant).map(s -> s.byId.get(mappingId));
    }

    /**
     * Checks if an inbound mapping exists in cache
     */
    public boolean containsInboundMapping(String tenant, String mappingId) {
        return inbound(tenant).map(s -> s.byId.containsKey(mappingId)).orElse(false);
    }

    /**
     * Checks if an inbound mapping with the given identifier exists in cache.
     * Uses the stable identifier (UUID) rather than the mutable MO id.
     */
    public boolean containsInboundMappingByIdentifier(String tenant, String identifier) {
        return getInboundMappingByIdentifier(tenant, identifier).isPresent();
    }

    /**
     * Retrieves an inbound mapping by its stable identifier (UUID).
     */
    public Optional<Mapping> getInboundMappingByIdentifier(String tenant, String identifier) {
        if (identifier == null) return Optional.empty();
        return inbound(tenant).map(s -> s.byIdentifier.get(identifier));
    }

    /**
     * Gets all inbound mappings for a tenant
     */
    public Map<String, Mapping> getAllInboundMappings(String tenant) {
        return inbound(tenant).map(s -> (Map<String, Mapping>) new HashMap<>(s.byId)).orElseGet(HashMap::new);
    }

    /**
     * Resolves inbound mappings by topic
     */
    public List<Mapping> resolveInboundMappings(String tenant, String topic) throws ResolveException {
        Optional<InboundState> state = inbound(tenant);
        return state.isPresent() ? state.get().tree.resolveMapping(topic) : List.of();
    }

    // ========== Outbound Cache Operations ==========

    /**
     * Rebuilds the entire outbound cache from a list of mappings
     */
    public List<Mapping> rebuildOutboundCache(String tenant, List<Mapping> mappings, ConnectorId connectorId) {
        log.debug("{} - Rebuilding outbound cache with {} mappings (triggered by {})",
                tenant, mappings.size(), connectorId.getName());

        OutboundState state = new OutboundState();
        mappings.forEach(m -> index(state.byId, state.byIdentifier, m));

        TenantCache tc = tenants.computeIfAbsent(tenant, TenantCache::new);
        synchronized (tc.lock) {
            tc.outbound = state;
        }
        return mappings;
    }

    /**
     * Adds or replaces a single mapping in the outbound cache
     */
    public void addOutboundMapping(String tenant, Mapping mapping) {
        TenantCache tc = tenants.get(tenant);
        if (tc == null) {
            log.warn("{} - No mapping cache for tenant, ignoring outbound mapping {}", tenant, mapping.getId());
            return;
        }
        synchronized (tc.lock) {
            OutboundState state = tc.outbound;
            Mapping previous = state.byId.get(mapping.getId());
            unindex(state.byId, state.byIdentifier, previous);
            index(state.byId, state.byIdentifier, mapping);
        }
        log.debug("{} - Added outbound mapping to cache: {}", tenant, mapping.getId());
    }

    /**
     * Removes a mapping from the outbound cache
     */
    public Optional<Mapping> removeOutboundMapping(String tenant, String mappingId) {
        TenantCache tc = tenants.get(tenant);
        if (tc == null) {
            return Optional.empty();
        }
        Mapping removed;
        synchronized (tc.lock) {
            OutboundState state = tc.outbound;
            removed = state.byId.get(mappingId);
            unindex(state.byId, state.byIdentifier, removed);
        }
        if (removed != null) {
            log.debug("{} - Removed outbound mapping from cache: {}", tenant, mappingId);
        }
        return Optional.ofNullable(removed);
    }

    /**
     * Retrieves a mapping from the outbound cache
     */
    public Optional<Mapping> getOutboundMapping(String tenant, String mappingId) {
        return outbound(tenant).map(s -> s.byId.get(mappingId));
    }

    /**
     * Checks if an outbound mapping exists in cache
     */
    public boolean containsOutboundMapping(String tenant, String mappingId) {
        return outbound(tenant).map(s -> s.byId.containsKey(mappingId)).orElse(false);
    }

    /**
     * Checks if an outbound mapping with the given identifier exists in cache.
     * Uses the stable identifier (UUID) rather than the mutable MO id.
     */
    public boolean containsOutboundMappingByIdentifier(String tenant, String identifier) {
        return getOutboundMappingByIdentifier(tenant, identifier).isPresent();
    }

    /**
     * Retrieves an outbound mapping by its stable identifier (UUID).
     */
    public Optional<Mapping> getOutboundMappingByIdentifier(String tenant, String identifier) {
        if (identifier == null) return Optional.empty();
        return outbound(tenant).map(s -> s.byIdentifier.get(identifier));
    }

    /**
     * Gets all outbound mappings for a tenant
     */
    public Map<String, Mapping> getAllOutboundMappings(String tenant) {
        return outbound(tenant).map(s -> (Map<String, Mapping>) new HashMap<>(s.byId)).orElseGet(HashMap::new);
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
     * Adds (or replaces) a mapping in the appropriate cache based on direction
     */
    public void addMapping(String tenant, Mapping mapping) {
        if (Direction.OUTBOUND.equals(mapping.getDirection())) {
            addOutboundMapping(tenant, mapping);
        } else {
            addInboundMapping(tenant, mapping);
        }
    }

    /**
     * Replaces a mapping in its cache. Unlike remove followed by add, a concurrent message never
     * finds the mapping missing.
     */
    public void replaceMapping(String tenant, Mapping mapping) {
        addMapping(tenant, mapping);
    }

    /**
     * Gets a mapping from either cache
     */
    public Optional<Mapping> getMapping(String tenant, String mappingId) {
        Optional<Mapping> inbound = getInboundMapping(tenant, mappingId);
        return inbound.isPresent() ? inbound : getOutboundMapping(tenant, mappingId);
    }

    // ========== Helper Methods ==========

    private Optional<InboundState> inbound(String tenant) {
        TenantCache tc = tenants.get(tenant);
        return tc == null ? Optional.empty() : Optional.of(tc.inbound);
    }

    private Optional<OutboundState> outbound(String tenant) {
        TenantCache tc = tenants.get(tenant);
        return tc == null ? Optional.empty() : Optional.of(tc.outbound);
    }

    private static void index(Map<String, Mapping> byId, Map<String, Mapping> byIdentifier, Mapping mapping) {
        byId.put(mapping.getId(), mapping);
        if (mapping.getIdentifier() != null) {
            byIdentifier.put(mapping.getIdentifier(), mapping);
        }
    }

    private static void unindex(Map<String, Mapping> byId, Map<String, Mapping> byIdentifier, Mapping mapping) {
        if (mapping == null) {
            return;
        }
        byId.remove(mapping.getId());
        if (mapping.getIdentifier() != null) {
            byIdentifier.remove(mapping.getIdentifier(), mapping);
        }
    }

    /**
     * The live resolver tree. A rebuild installs a new tree, so do not keep the reference.
     */
    public MappingTreeNode getResolverMappingInbound(String tenant) {
        return inbound(tenant).map(s -> s.tree).orElseGet(() -> MappingTreeNode.createRootNode(tenant));
    }
}
