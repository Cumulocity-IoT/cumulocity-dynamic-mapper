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

package dynamic.mapper.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.mutable.MutableBoolean;
import org.apache.commons.lang3.mutable.MutableInt;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import dynamic.mapper.util.Utils;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Represents a node in the mapping tree structure.
 * Each node can either be a mapping node or an inner node.
 */
@Slf4j
@Getter
@ToString
@AllArgsConstructor
@Builder(toBuilder = true)
@Schema(description = "Node in the hierarchical mapping tree, used to resolve MQTT topic patterns to mappings")
public class MappingTreeNode {
    // Constants
    private static final String TENANT_LOG_PREFIX = "{} - ";

    // Core properties
    @Builder.Default
    private final Map<String, List<MappingTreeNode>> childNodes = new ConcurrentHashMap<>();
    private Mapping mapping;
    @Builder.Default
    private String nodeId = Utils.createCustomUuid();
    // Defaults to false so a builder call that forgets to set this (e.g. via
    // toBuilder()) can't leave it null and NPE the unboxing call sites
    // (getMappingNode() is used directly as a Predicate<MappingTreeNode> in streams).
    @Builder.Default
    private Boolean mappingNode = false;
    private long depthIndex;
    @ToString.Exclude
    private MappingTreeNode parentNode;
    private String absolutePath;
    private String level;
    private String tenant;
    // A single ReadWriteLock guards the whole tree. All public operations are invoked on the
    // root node (see MappingCacheManager), so every node shares the root's lock by reference
    // instead of allocating its own — this avoids one lock instance per tree node.
    @ToString.Exclude
    private final ReadWriteLock treeLock;

    // Helper class for context; children list is mutable (nodes are added during tree building)
    @AllArgsConstructor
    @Getter
    private static class MappingContext {
        String currentLevel;
        String currentPathMonitoring;
        List<MappingTreeNode> children;
        int level;
        List<String> levels;
    }

    // Factory methods
    public static MappingTreeNode createRootNode(String tenant) {
        return MappingTreeNode.builder()
                .depthIndex(0)
                .level("root")
                .tenant(tenant)
                .parentNode(null)
                .absolutePath("")
                .mappingNode(false)
                .treeLock(new ReentrantReadWriteLock())
                .build();
    }

    public static MappingTreeNode createMappingNode(MappingTreeNode parent, String level, Mapping mapping) {
        return MappingTreeNode.builder()
                .parentNode(parent)
                .mapping(mapping)
                .level(level)
                .tenant(parent.getTenant())
                .absolutePath(buildPath(parent.getAbsolutePath(), level))
                .depthIndex(parent.getDepthIndex() + 1)
                .mappingNode(true)
                .treeLock(parent.treeLock)
                .build();
    }

    public static MappingTreeNode createInnerNode(MappingTreeNode parent, String level) {
        return MappingTreeNode.builder()
                .parentNode(parent)
                .level(level)
                .tenant(parent.getTenant())
                .absolutePath(buildPath(parent.getAbsolutePath(), level))
                .depthIndex(parent.getDepthIndex() + 1)
                .mappingNode(false)
                .treeLock(parent.treeLock)
                .build();
    }

    // Public API methods

    public List<Mapping> resolveMapping(String topic) throws ResolveException {
        treeLock.readLock().lock();
        try {
            List<MappingTreeNode> resolvedMappings = resolveTopicPath(
                    Mapping.splitTopicIncludingSeparatorAsList(topic), 0);
            return resolvedMappings.stream()
                    .filter(MappingTreeNode::getMappingNode)
                    .map(MappingTreeNode::getMapping)
                    .collect(Collectors.toList());
        } finally {
            treeLock.readLock().unlock();
        }
    }

    public void addMapping(Mapping mapping) throws ResolveException {
        if (!hasResolvableTopic(mapping, "add"))
            return;

        treeLock.writeLock().lock();
        try {
            List<String> levels = Mapping.splitTopicIncludingSeparatorAsList(mapping.getMappingTopic());
            addMapping(mapping, levels, 0);
        } finally {
            treeLock.writeLock().unlock();
        }
    }

    public void deleteMapping(Mapping mapping) throws ResolveException {
        if (!hasResolvableTopic(mapping, "delete"))
            return;

        treeLock.writeLock().lock();
        try {
            List<String> levels = Mapping.splitTopicIncludingSeparatorAsList(mapping.getMappingTopic());
            MutableInt branchingLevel = new MutableInt(0);
            deleteMapping(mapping, levels, 0, branchingLevel);
        } finally {
            treeLock.writeLock().unlock();
        }
    }

    /**
     * Guards against mappings that cannot be placed in the resolver tree (null mapping or
     * null/blank mapping topic). Such mappings would otherwise trigger a NullPointerException
     * while splitting the topic inside the write lock.
     */
    private boolean hasResolvableTopic(Mapping mapping, String operation) {
        if (mapping == null) {
            return false;
        }
        String topic = mapping.getMappingTopic();
        if (topic == null || topic.isBlank()) {
            log.warn(TENANT_LOG_PREFIX + "Skipping tree {} for mapping {} with empty mapping topic",
                    tenant, operation, mapping.getId());
            return false;
        }
        return true;
    }

    // Helper methods for node operations
    public Optional<List<MappingTreeNode>> getChildrenOptional(String level) {
        return Optional.ofNullable(childNodes.get(level));
    }

    public Optional<MappingTreeNode> getParentOptional() {
        return Optional.ofNullable(parentNode);
    }

    public Optional<Mapping> getMappingOptional() {
        return Optional.ofNullable(mapping);
    }

    // Private implementation methods
    private List<MappingTreeNode> resolveTopicPath(List<String> topicLevels, Integer currentTopicLevelIndex) {
        List<MappingTreeNode> results = new ArrayList<>();

        if (currentTopicLevelIndex < topicLevels.size()) {
            String currentLevel = topicLevels.get(currentTopicLevelIndex);

            // Process exact matches
            getChildrenOptional(currentLevel)
                    .ifPresent(nodes -> nodes.forEach(node ->
                            results.addAll(node.resolveTopicPath(topicLevels, currentTopicLevelIndex + 1))));

            // Process single wildcard matches
            getChildrenOptional(Mapping.TOPIC_WILDCARD_SINGLE)
                    .ifPresent(nodes -> nodes.forEach(node ->
                            results.addAll(node.resolveTopicPath(topicLevels, currentTopicLevelIndex + 1))));

            // Process multi wildcard matches
            getChildrenOptional(Mapping.TOPIC_WILDCARD_MULTI)
                    .ifPresent(nodes -> nodes.forEach(results::add));
        } else if (topicLevels.size() == currentTopicLevelIndex) {
            if (getMappingNode()) {
                results.add(this);
            } else if (log.isDebugEnabled()) {
                // The topic exactly matches an inner (non-mapping) node, i.e. no mapping is
                // registered for this exact path. This is on the message dispatch hot path,
                // so keep it at debug and avoid building strings eagerly.
                log.debug("No mapping registered for exact path [{}]", this.getAbsolutePath());
            }
        }

        return results;
    }

    private void addMapping(Mapping mapping, List<String> levels, int currentLevel) throws ResolveException {
        MappingContext context = createMappingContext(levels, currentLevel);

        if (isLastLevel(context)) {
            addMappingNode(mapping, context);
        } else if (isIntermediateLevel(context)) {
            addInnerNode(mapping, context);
        } else {
            throw new ResolveException(String.format("Could not add mapping to tree: %s", mapping));
        }
    }

    private MappingContext createMappingContext(List<String> levels, int currentLevel) {
        return new MappingContext(
                levels.get(currentLevel),
                createPathMonitoring(levels, currentLevel),
                getChildNodes().getOrDefault(levels.get(currentLevel), new ArrayList<>()),
                currentLevel,
                levels);
    }

    private boolean isLastLevel(MappingContext context) {
        return context.level == context.levels.size() - 1;
    }

    private boolean isIntermediateLevel(MappingContext context) {
        return context.level < context.levels.size() - 1;
    }

    private void addMappingNode(Mapping mapping, MappingContext context) {
        logMappingNodeAddition(mapping, context);
        MappingTreeNode child = createMappingNode(this, context.currentLevel, mapping);
        updateChildNodes(context, child);
    }

    private void addInnerNode(Mapping mapping, MappingContext context) throws ResolveException {
        logInnerNodeAddition(context);
        MappingTreeNode child = createOrGetInnerNode(context);
        child.addMapping(mapping, context.levels, context.level + 1);
    }

    private MappingTreeNode createOrGetInnerNode(MappingContext context) throws ResolveException {
        if (getChildNodes().containsKey(context.currentLevel)) {
            return findOrCreateInnerNode(context);
        }
        return createAndLinkInnerNode(context);
    }

    private boolean deleteMapping(Mapping mapping, List<String> levels, int currentLevel, MutableInt branchingLevel)
            throws ResolveException {
        if (!hasChildren()) {
            return false;
        }

        MappingContext context = createMappingContext(levels, currentLevel);
        return isLastLevel(context)
                ? deleteMappingNode(mapping, context, branchingLevel)
                : deleteInnerNode(mapping, context, branchingLevel);
    }

    // Utility methods
    private static String buildPath(String parentPath, String level) {
        return parentPath + level;
    }

    private boolean hasChildren() {
        return !childNodes.isEmpty();
    }

    private void updateChildNodes(MappingContext context, MappingTreeNode child) {
        childNodes.computeIfAbsent(context.currentLevel, k -> new ArrayList<>()).add(child);
    }

    private MappingTreeNode findOrCreateInnerNode(MappingContext context) throws ResolveException {
        List<MappingTreeNode> innerNodes = findInnerNodes(context.children);
        validateInnerNodes(innerNodes);
        return innerNodes.isEmpty() ? createAndLinkInnerNode(context) : innerNodes.get(0);
    }

    private List<MappingTreeNode> findInnerNodes(List<MappingTreeNode> children) {
        return children.stream()
                .filter(node -> !node.getMappingNode())
                .collect(Collectors.toList());
    }

    private void validateInnerNodes(List<MappingTreeNode> innerNodes) throws ResolveException {
        if (innerNodes.size() > 1) {
            throw new ResolveException("Multiple inner nodes are registered: " + innerNodes);
        }
    }

    private MappingTreeNode createAndLinkInnerNode(MappingContext context) {
        MappingTreeNode child = createInnerNode(this, context.currentLevel);
        updateChildNodes(context, child);
        return child;
    }

    private boolean deleteMappingNode(Mapping mapping, MappingContext context, MutableInt branchingLevel) {
        logMappingNodeDeletion(mapping, context, branchingLevel);
        return processChildNodesForDeletion(mapping, context, branchingLevel);
    }

    private boolean deleteInnerNode(Mapping mapping, MappingContext context, MutableInt branchingLevel) {
        logInnerNodeDeletion(context, branchingLevel);
        return processInnerNodeDeletion(mapping, context, branchingLevel);
    }

    private boolean processInnerNodeDeletion(Mapping mapping, MappingContext context, MutableInt branchingLevel) {
        MutableBoolean foundMapping = new MutableBoolean(false);

        if (!childNodes.containsKey(context.currentLevel)) {
            return false;
        }

        List<MappingTreeNode> currentChildNodes = childNodes.get(context.currentLevel);
        currentChildNodes.removeIf(node -> processInnerNodeChild(node, mapping, context, branchingLevel, foundMapping));

        if (currentChildNodes.isEmpty()) {
            childNodes.remove(context.currentLevel);
        }

        return foundMapping.booleanValue();
    }

    private boolean processInnerNodeChild(MappingTreeNode node, Mapping mapping, MappingContext context,
            MutableInt branchingLevel, MutableBoolean foundMapping) {
        if (node.getMappingNode() || foundMapping.booleanValue()) {
            return false;
        }

        try {
            updateBranchingLevelIfNeeded(context, branchingLevel);
            boolean deleted = node.deleteMapping(mapping, context.levels, context.level + 1, branchingLevel);
            foundMapping.setValue(deleted);
            return shouldDeleteNode(deleted, context.level, branchingLevel);
        } catch (ResolveException e) {
            log.error(TENANT_LOG_PREFIX + "Deleting mapping error: currentPathMonitoring [{}], branchingLevel [{}]",
                    tenant, context.currentPathMonitoring, branchingLevel, e);
            return false;
        }
    }

    private boolean processChildNodesForDeletion(Mapping mapping, MappingContext context, MutableInt branchingLevel) {
        List<MappingTreeNode> currentChildNodes = childNodes.get(context.currentLevel);
        if (currentChildNodes == null) {
            return false;
        }

        boolean removedAny = currentChildNodes
                .removeIf(node -> shouldRemoveNode(node, mapping, context, branchingLevel));

        // Drop the (now empty) level entry from the parent's child map.
        if (currentChildNodes.isEmpty()) {
            childNodes.remove(context.currentLevel);
        }

        return removedAny;
    }

    private boolean shouldRemoveNode(MappingTreeNode node, Mapping mapping, MappingContext context,
            MutableInt branchingLevel) {
        return Optional.ofNullable(node.getMapping())
                .map(m -> m.getId().equals(mapping.getId()))
                .map(matches -> {
                    if (matches && countGrandChildren() > 1) {
                        branchingLevel.setValue(context.level);
                    }
                    return matches;
                })
                .orElse(false);
    }

    private void updateBranchingLevelIfNeeded(MappingContext context, MutableInt branchingLevel) {
        if (countGrandChildren() > 1) {
            branchingLevel.setValue(context.level);
        }
    }

    private boolean shouldDeleteNode(boolean deleted, int currentLevel, MutableInt branchingLevel) {
        if (currentLevel < branchingLevel.intValue()) {
            log.debug(TENANT_LOG_PREFIX + "Deleting innerNode stopped: currentLevel [{}], branchingLevel [{}]",
                    tenant, currentLevel, branchingLevel);
            return false;
        }
        return deleted;
    }

    private int countGrandChildren() {
        return childNodes.values().stream().mapToInt(List::size).sum();
    }

    // Logging methods
    private void logMappingNodeAddition(Mapping mapping, MappingContext context) {
        log.debug(TENANT_LOG_PREFIX
                + "Adding mappingNode  : currentPathMonitoring [{}], currentNode.absolutePath [{}], mappingId [{}]",
                tenant, context.currentPathMonitoring, getAbsolutePath(), mapping.getId());
    }

    private void logInnerNodeAddition(MappingContext context) {
        log.debug(TENANT_LOG_PREFIX + "Adding innerNode    : currentPathMonitoring [{}], currentNode.absolutePath [{}]",
                tenant, context.currentPathMonitoring, getAbsolutePath());
    }

    private void logMappingNodeDeletion(Mapping mapping, MappingContext context, MutableInt branchingLevel) {
        log.debug(
                TENANT_LOG_PREFIX
                        + "Deleting mappingNode: currentPathMonitoring [{}], branchingLevel [{}], mappingId [{}]",
                tenant, context.currentPathMonitoring, branchingLevel, mapping.getId());
    }

    private void logInnerNodeDeletion(MappingContext context, MutableInt branchingLevel) {
        log.debug(TENANT_LOG_PREFIX + "Deleting innerNode  : currentPathMonitoring [{}], branchingLevel [{}]",
                tenant, context.currentPathMonitoring, branchingLevel);
    }

    private String createPathMonitoring(List<String> levels, int currentLevel) {
        StringBuilder pathBuilder = new StringBuilder();
        for (int i = 0; i < levels.size(); i++) {
            if (i > 0) {
                pathBuilder.append("/");
            }
            if (i == currentLevel) {
                pathBuilder.append("__").append(levels.get(i)).append("__");
            } else {
                pathBuilder.append(levels.get(i));
            }
        }
        return pathBuilder.toString();
    }
}
