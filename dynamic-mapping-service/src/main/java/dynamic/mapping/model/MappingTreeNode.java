/*
 * Copyright (c) 2022 Software AG, Darmstadt, Germany and/or Software AG USA Inc., Reston, VA, USA,
 * and/or its subsidiaries and/or its affiliates and/or their licensors.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * @authors Christof Strack, Stefan Witschel
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

import java.security.SecureRandom;
import java.util.*;
import java.util.Map.Entry;
import java.util.stream.Collectors;

/**
 * Represents a node in the mapping tree structure.
 * Each node can either be a mapping node or an inner node.
 */
@Slf4j
@Getter
@ToString
@AllArgsConstructor // Add this annotation
@Builder(toBuilder = true)
public class MappingTreeNode {
    @Builder.Default
    private final Map<String, List<MappingTreeNode>> childNodes =  Collections.synchronizedMap(new HashMap<>());

    private Mapping mapping;

    @Builder.Default
    private String nodeId = uuidCustom();

    private boolean mappingNode;

    private long depthIndex;

    @ToString.Exclude
    private MappingTreeNode parentNode;

    private String absolutePath;

    private String level;

    private String tenant;

    private final Object treeModificationLock = new Object();

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private static final int UUID_LENGTH = 6;

    private static final String TENANT_LOG_PREFIX = "Tenant {} - ";

    // Helper classes
    @Value
    private static class MappingContext {
        String currentLevel;
        String currentPathMonitoring;
        List<MappingTreeNode> children;
        int level;
        List<String> levels;
    }

    // Static factory methods using the generated builder
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

    public List<Mapping> resolveMapping(String topic) throws ResolveException {
        List<MappingTreeNode> resolvedMappings = resolveTopicPath(Mapping.splitTopicIncludingSeparatorAsList(topic), 0);
        return resolvedMappings.stream().filter(tn -> tn.isMappingNode())
                .map(mn -> mn.getMapping()).collect(Collectors.toList());
    }

    private List<MappingTreeNode> resolveTopicPath(List<String> topicLevels, Integer currentTopicLevelIndex)
            throws ResolveException {
        List<MappingTreeNode> results = new ArrayList<>();

        if (currentTopicLevelIndex < topicLevels.size()) {
            String currentLevel = topicLevels.get(currentTopicLevelIndex);

            // Using Optional for cleaner null handling
            getChildrenOptional(currentLevel)
                    .ifPresent(nodes -> nodes.forEach(node -> {
                        try {
                            results.addAll(node.resolveTopicPath(topicLevels, currentTopicLevelIndex + 1));
                        } catch (ResolveException e) {
                            log.error("Error resolving topic path", e);
                        }
                    }));

            // Check for wildcard matches
            getChildrenOptional(Mapping.TOPIC_WILDCARD_SINGLE)
                    .ifPresent(nodes -> nodes.forEach(node -> {
                        try {
                            results.addAll(node.resolveTopicPath(topicLevels, currentTopicLevelIndex + 1));
                        } catch (ResolveException e) {
                            log.error("Error resolving topic path", e);
                        }
                    }));

            getChildrenOptional(Mapping.TOPIC_WILDCARD_MULTI)
                    .ifPresent(nodes -> nodes.forEach(node -> {
                        // try {
                        // results.addAll(node.resolveTopicPath(topicLevels, currentTopicLevelIndex +
                        // 1));
                        results.add(node);
                        // } catch (ResolveException e) {
                        // log.error("Error resolving topic path", e);
                        // }
                    }));
        } else if (topicLevels.size() == currentTopicLevelIndex) {
            // Handle the case where we've reached the end of the topic levels
            if (isMappingNode()) {
                results.add(this);
            } else {
                String remaining = String.join("/", topicLevels);
                String msg = String.format("Sibling path mapping registered for this path: %s %s!",
                        this.getAbsolutePath(), remaining);
                log.info(msg);
            }
        }

        return results;
    }

    public void addMapping(Mapping mapping) throws ResolveException {
		synchronized (treeModificationLock) {
			if (mapping != null) {
				var path = mapping.mappingTopic;
				List<String> levels = Mapping.splitTopicIncludingSeparatorAsList(path);
				addMapping(mapping, levels, 0);
			}
		}
	}

    public void deleteMapping(Mapping mapping) throws ResolveException {
		synchronized (treeModificationLock) {
			if (mapping != null) {
				var path = mapping.mappingTopic;
				List<String> levels = Mapping.splitTopicIncludingSeparatorAsList(path);
				MutableInt branchingLevel = new MutableInt(0);
				deleteMapping(mapping, levels, 0, branchingLevel);
			}
		}
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

    private MappingTreeNode findOrCreateInnerNode(MappingContext context) throws ResolveException {
        List<MappingTreeNode> innerNodes = findInnerNodes(context.children);
        validateInnerNodes(innerNodes);
        if (innerNodes.size() > 1) log.warn(TENANT_LOG_PREFIX + "Something wrong innerNode size should never be > 1: {}",
        tenant, innerNodes.size());
        return innerNodes.isEmpty()
                ? createAndLinkInnerNode(context)
                : innerNodes.get(0);
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

    private void updateChildNodes(MappingContext context, MappingTreeNode child) {
        context.children.add(child);
        getChildNodes().put(context.currentLevel, context.children);
    }

    private boolean hasChildren() {
        return getChildNodes() != null && !getChildNodes().isEmpty();
    }

    // Deletion method improvements
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

    /**
     * @param mapping
     * @param levels
     * @param currentLevel
     * @param branchingLevel the branchingLevel is an indicator if other valid
     *                       mapping in siblings node exist.
     *                       this is used when deleting nodes from the tree. In the
     *                       case
     *                       where >0 the ancestor mapping node must not be deleted,
     *                       as these
     *                       sibling
     *                       mapping would be deleted as well
     * @return
     * @throws ResolveException
     */
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

        if (!getChildNodes().containsKey(context.currentLevel)) {
            return false;
        }

        List<MappingTreeNode> currentChildNodes = getChildNodes().get(context.currentLevel);
        currentChildNodes.removeIf(node -> processInnerNodeChild(node, mapping, context, branchingLevel, foundMapping));

        if (currentChildNodes.isEmpty()) {
            getChildNodes().remove(context.currentLevel);
        }

        return foundMapping.booleanValue();
    }

    private boolean processInnerNodeChild(
            MappingTreeNode node,
            Mapping mapping,
            MappingContext context,
            MutableInt branchingLevel,
            MutableBoolean foundMapping) {

        if (node.isMappingNode() || foundMapping.booleanValue()) {
            return false;
        }

        try {
            updateBranchingLevelIfNeeded(context, branchingLevel);
            boolean deleted = node.deleteMapping(mapping, context.levels, context.level + 1, branchingLevel);
            foundMapping.setValue(deleted);

            return shouldDeleteNode(deleted, context.level, branchingLevel);
        } catch (ResolveException e) {
            log.error(TENANT_LOG_PREFIX + "Deleting mapping error: currentPathMonitoring: {}, branchingLevel: {}",
                    tenant, context.currentPathMonitoring, branchingLevel, e);
            return false;
        }
    }

    private void updateBranchingLevelIfNeeded(MappingContext context, MutableInt branchingLevel) {
        if (countGrandChildren() > 1) {
            branchingLevel.setValue(context.level);
        }
    }

    private boolean shouldDeleteNode(boolean deleted, int currentLevel, MutableInt branchingLevel) {
        if (currentLevel < branchingLevel.getValue()) {
            log.debug(TENANT_LOG_PREFIX + "Deleting innerNode stopped: currentLevel: {}, branchingLevel: {}",
                    tenant, currentLevel, branchingLevel);
            return false;
        }
        return deleted;
    }

    private boolean processChildNodesForDeletion(Mapping mapping, MappingContext context, MutableInt branchingLevel) {
        MutableBoolean foundMapping = new MutableBoolean(false);
        Set<Entry<String, List<MappingTreeNode>>> childNodesEntrySet = getChildNodes().entrySet();

        childNodesEntrySet
                .removeIf(entry -> processMappingNodeEntry(entry, mapping, context, branchingLevel, foundMapping));

        return foundMapping.booleanValue();
    }

    private boolean processMappingNodeEntry(
            Entry<String, List<MappingTreeNode>> entry,
            Mapping mapping,
            MappingContext context,
            MutableInt branchingLevel,
            MutableBoolean foundMapping) {

        List<MappingTreeNode> nodes = entry.getValue();
        nodes.removeIf(node -> shouldRemoveNode(node, mapping, context, branchingLevel));

        if (nodes.isEmpty()) {
            foundMapping.setTrue();
            return true;
        }
        return false;
    }

    private boolean shouldRemoveNode(
            MappingTreeNode node,
            Mapping mapping,
            MappingContext context,
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

    // Logging methods
    // Logging methods
    private void logMappingNodeAddition(Mapping mapping, MappingContext context) {
        log.debug(
                TENANT_LOG_PREFIX
                        + "Adding mappingNode: currentPathMonitoring: {}, currentNode.absolutePath: {}, mappingId: {}",
                tenant, context.currentPathMonitoring, getAbsolutePath(), mapping.id);
    }

    private void logInnerNodeAddition(MappingContext context) {
        log.debug(TENANT_LOG_PREFIX + "Adding innerNode: currentPathMonitoring: {}, currentNode.absolutePath: {}",
                tenant, context.currentPathMonitoring, getAbsolutePath());
    }

    private void logMappingNodeDeletion(Mapping mapping, MappingContext context, MutableInt branchingLevel) {
        log.debug(
                TENANT_LOG_PREFIX
                        + "Deleting mappingNode: currentPathMonitoring: {}, branchingLevel: {}, mappingId: {}",
                tenant, context.currentPathMonitoring, branchingLevel, mapping.id);
    }

    private void logInnerNodeDeletion(MappingContext context, MutableInt branchingLevel) {
        log.debug(TENANT_LOG_PREFIX + "Deleting innerNode: currentPathMonitoring: {}, branchingLevel: {}",
                tenant, context.currentPathMonitoring, branchingLevel);
    }

    private int countGrandChildren() {
        return getChildNodes().values().stream().mapToInt(List::size).sum();
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

    /**
     * Safely retrieves children nodes for a given level.
     * 
     * @param level the level to look up
     * @return Optional containing the list of child nodes if present, empty
     *         Optional otherwise
     */
    public Optional<List<MappingTreeNode>> getChildrenOptional(String level) {
        return Optional.ofNullable(childNodes.get(level));
    }

    /**
     * Safely retrieves the parent node.
     * 
     * @return Optional containing the parent node if present, empty Optional
     *         otherwise
     */
    public Optional<MappingTreeNode> getParentOptional() {
        return Optional.ofNullable(parentNode);
    }

    /**
     * Safely retrieves the mapping.
     * 
     * @return Optional containing the mapping if present, empty Optional otherwise
     */
    public Optional<Mapping> getMappingOptional() {
        return Optional.ofNullable(mapping);
    }

    private static String buildPath(String parentPath, String level) {
        StringBuilder pathBuilder = new StringBuilder(parentPath.length() + level.length());
        pathBuilder.append(parentPath);
        // if (!parentPath.isEmpty() && !parentPath.endsWith("/")) {
        // pathBuilder.append("/");
        // }
        pathBuilder.append(level);
        return pathBuilder.toString();
    }

    public static String uuidCustom() {
        return SECURE_RANDOM.ints(UUID_LENGTH, 0, 36)
                .mapToObj(i -> Character.toString(i < 10 ? '0' + i : 'a' + i - 10))
                .collect(Collectors.joining());
    }
}
