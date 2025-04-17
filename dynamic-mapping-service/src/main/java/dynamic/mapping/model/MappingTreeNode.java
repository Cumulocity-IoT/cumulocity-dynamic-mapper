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

package dynamic.mapping.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.mutable.MutableBoolean;
import org.apache.commons.lang3.mutable.MutableInt;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Collections;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import dynamic.mapping.util.Utils;;

/**
 * Represents a node in the mapping tree structure.
 * Each node can either be a mapping node or an inner node.
 */
@Slf4j
@Getter
@ToString
@AllArgsConstructor
@Builder(toBuilder = true)
public class MappingTreeNode {
    // Constants
    private static final String TENANT_LOG_PREFIX = "Tenant {} - ";

    // Core properties
    @Builder.Default
    private final Map<String, List<MappingTreeNode>> childNodes = Collections.synchronizedMap(new HashMap<>());
    private Mapping mapping;
    @Builder.Default
    private String nodeId = Utils.createCustomUuid();
    private boolean mappingNode;
    private long depthIndex;
    @ToString.Exclude
    private MappingTreeNode parentNode;
    private String absolutePath;
    private String level;
    private String tenant;
    private final Object treeModificationLock = new Object();

    // Helper class for context
    @Value
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
                .build();
    }

    // Public API methods
    public List<Mapping> resolveMapping(String topic) throws ResolveException {
        List<MappingTreeNode> resolvedMappings = resolveTopicPath(
                Mapping.splitTopicIncludingSeparatorAsList(topic), 0);
        return resolvedMappings.stream()
                .filter(MappingTreeNode::isMappingNode)
                .map(MappingTreeNode::getMapping)
                .collect(Collectors.toList());
    }

    public void addMapping(Mapping mapping) throws ResolveException {
        synchronized (treeModificationLock) {
            if (mapping != null) {
                List<String> levels = Mapping.splitTopicIncludingSeparatorAsList(mapping.mappingTopic);
                addMapping(mapping, levels, 0);
            }
        }
    }

    public void deleteMapping(Mapping mapping) throws ResolveException {
        synchronized (treeModificationLock) {
            if (mapping != null) {
                List<String> levels = Mapping.splitTopicIncludingSeparatorAsList(mapping.mappingTopic);
                MutableInt branchingLevel = new MutableInt(0);
                deleteMapping(mapping, levels, 0, branchingLevel);
            }
        }
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
    private List<MappingTreeNode> resolveTopicPath(List<String> topicLevels, Integer currentTopicLevelIndex)
            throws ResolveException {
        List<MappingTreeNode> results = new ArrayList<>();

        if (currentTopicLevelIndex < topicLevels.size()) {
            String currentLevel = topicLevels.get(currentTopicLevelIndex);

            // Process exact matches
            getChildrenOptional(currentLevel)
                    .ifPresent(nodes -> nodes.forEach(node -> {
                        try {
                            results.addAll(node.resolveTopicPath(topicLevels, currentTopicLevelIndex + 1));
                        } catch (ResolveException e) {
                            log.error("Error resolving topic path", e);
                        }
                    }));

            // Process single wildcard matches
            getChildrenOptional(Mapping.TOPIC_WILDCARD_SINGLE)
                    .ifPresent(nodes -> nodes.forEach(node -> {
                        try {
                            results.addAll(node.resolveTopicPath(topicLevels, currentTopicLevelIndex + 1));
                        } catch (ResolveException e) {
                            log.error("Error resolving topic path", e);
                        }
                    }));

            // Process multi wildcard matches
            getChildrenOptional(Mapping.TOPIC_WILDCARD_MULTI)
                    .ifPresent(nodes -> nodes.forEach(results::add));
        } else if (topicLevels.size() == currentTopicLevelIndex) {
            if (isMappingNode()) {
                results.add(this);
            } else {
                String remaining = String.join("", topicLevels);
                log.info("Sibling path mapping registered for this path [{}], remaining {}!", this.getAbsolutePath(),
                        remaining);
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
        context.children.add(child);
        childNodes.put(context.currentLevel, context.children);
    }

    private MappingTreeNode findOrCreateInnerNode(MappingContext context) throws ResolveException {
        List<MappingTreeNode> innerNodes = findInnerNodes(context.children);
        validateInnerNodes(innerNodes);
        if (innerNodes.size() > 1) {
            log.warn(TENANT_LOG_PREFIX + "Something wrong innerNode size should never be > 1 [{}]", tenant,
                    innerNodes.size());
        }
        return innerNodes.isEmpty() ? createAndLinkInnerNode(context) : innerNodes.get(0);
    }

    private List<MappingTreeNode> findInnerNodes(List<MappingTreeNode> children) {
        return children.stream()
                .filter(node -> !node.isMappingNode())
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
        if (node.isMappingNode() || foundMapping.booleanValue()) {
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
        MutableBoolean foundMapping = new MutableBoolean(false);
        Set<Entry<String, List<MappingTreeNode>>> childNodesEntrySet = childNodes.entrySet();

        childNodesEntrySet
                .removeIf(entry -> processMappingNodeEntry(entry, mapping, context, branchingLevel, foundMapping));

        return foundMapping.booleanValue();
    }

    private boolean processMappingNodeEntry(Entry<String, List<MappingTreeNode>> entry, Mapping mapping,
            MappingContext context, MutableInt branchingLevel, MutableBoolean foundMapping) {
        List<MappingTreeNode> nodes = entry.getValue();
        nodes.removeIf(node -> shouldRemoveNode(node, mapping, context, branchingLevel));

        if (nodes.isEmpty()) {
            foundMapping.setTrue();
            return true;
        }
        return false;
    }

    private boolean shouldRemoveNode(MappingTreeNode node, Mapping mapping, MappingContext context,
            MutableInt branchingLevel) {
        return Optional.ofNullable(node.getMapping())
                .map(m -> m.id.equals(mapping.id))
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
        if (currentLevel < branchingLevel.getValue()) {
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
                tenant, context.currentPathMonitoring, getAbsolutePath(), mapping.id);
    }

    private void logInnerNodeAddition(MappingContext context) {
        log.debug(TENANT_LOG_PREFIX + "Adding innerNode    : currentPathMonitoring [{}], currentNode.absolutePath [{}]",
                tenant, context.currentPathMonitoring, getAbsolutePath());
    }

    private void logMappingNodeDeletion(Mapping mapping, MappingContext context, MutableInt branchingLevel) {
        log.debug(
                TENANT_LOG_PREFIX
                        + "Deleting mappingNode: currentPathMonitoring [{}], branchingLevel [{}], mappingId [{}]",
                tenant, context.currentPathMonitoring, branchingLevel, mapping.id);
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
