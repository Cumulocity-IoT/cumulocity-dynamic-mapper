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
import lombok.Builder;
import lombok.ToString;
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
    private final Map<String, List<MappingTreeNode>> childNodes = new HashMap<>();

    private Mapping mapping;

    private String nodeId;

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

    public MappingTreeNode() {
        this.childNodes = Collections.synchronizedMap(new HashMap<>());
        this.nodeId = uuidCustom();
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
                            //results.addAll(node.resolveTopicPath(topicLevels, currentTopicLevelIndex + 1));
                            results.add(node);
                        // } catch (ResolveException e) {
                        //     log.error("Error resolving topic path", e);
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

    private void addMapping(Mapping mapping, List<String> levels, int currentLevel)
            throws ResolveException {
        List<MappingTreeNode> children = getChildNodes().getOrDefault(levels.get(currentLevel),
                new ArrayList<MappingTreeNode>());
        String currentPathMonitoring = createPathMonitoring(levels, currentLevel);
        if (currentLevel == levels.size() - 1) {
            log.debug(
                    "Tenant {} - Adding mappingNode : currentPathMonitoring: {}, currentNode.absolutePath: {}, mappingId : {}",
                    tenant, currentPathMonitoring, getAbsolutePath(), mapping.id);
            MappingTreeNode child = createMappingNode(this, levels.get(currentLevel), mapping);
            log.debug("Tenant {} - Adding mappingNode : currentPathMonitoring {}, child: {}", tenant,
                    currentPathMonitoring,
                    child.toString());
            children.add(child);
            getChildNodes().put(levels.get(currentLevel), children);
        } else if (currentLevel < levels.size() - 1) {
            log.debug(
                    "Tenant {} - Adding innerNode   : currentPathMonitoring: {}, currentNode.absolutePath: {}",
                    tenant, currentPathMonitoring, getLevel(), getAbsolutePath());
            MappingTreeNode child;

            // is currentLevel a known children
            if (getChildNodes().containsKey(levels.get(currentLevel))) {

                // find the one node that is an inner node, so that we can descend further in
                // the tree
                List<MappingTreeNode> innerNodes = children.stream()
                        .filter(node -> !node.isMappingNode())
                        .collect(Collectors.toList());
                if (innerNodes != null && innerNodes.size() > 1) {
                    throw new ResolveException(String.format(
                            "multiple inner nodes are registered : %s",
                            children.toString()));
                } else if (innerNodes != null && innerNodes.size() == 1) {
                    child = innerNodes.get(0);
                } else {
                    child = createInnerNode(this, levels.get(currentLevel));
                    children.add(child);
                    getChildNodes().put(levels.get(currentLevel), children);
                }
                // currentLevel is not a known children, is empty and has to be linked to its
                // parent
            } else {
                child = createInnerNode(this, levels.get(currentLevel));
                log.debug("Tenant {} - Adding innerNode: currentPathMonitoring: {}, child: {}, {}", tenant,
                        currentPathMonitoring,
                        child.toString());
                children.add(child);
                getChildNodes().put(levels.get(currentLevel), children);
            }
            child.addMapping(mapping, levels, currentLevel + 1);
        } else {
            throw new ResolveException(String.format("Could not add mapping to tree: %s", mapping.toString()));
        }
    }

    public void addMapping(Mapping mapping) throws ResolveException {
        if (mapping == null) {
            throw new IllegalArgumentException("Mapping cannot be null");
        }
        synchronized (treeModificationLock) {
            var path = mapping.mappingTopic;
            if (path == null || path.isEmpty()) {
                throw new ResolveException("Mapping topic path cannot be null or empty");
            }
            List<String> levels = Mapping.splitTopicIncludingSeparatorAsList(path);
            addMapping(mapping, levels, 0);
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
    private boolean deleteMapping(Mapping mapping, List<String> levels, int currentLevel, MutableInt branchingLevel)
            throws ResolveException {
        MutableBoolean foundMapping = new MutableBoolean(false);
        String currentPathMonitoring = createPathMonitoring(levels, currentLevel);
        boolean hasChildren = getChildNodes() != null && getChildNodes().size() > 0;
        if (hasChildren) {
            if (currentLevel == levels.size() - 1) {
                log.debug(
                        "Tenant {} - Deleting mappingNode (?)      : currentPathMonitoring: {}, branchingLevel: {}, mappingId: {}",
                        tenant,
                        currentPathMonitoring, branchingLevel, mapping.id);
                // find child and remove
                Set<Entry<String, List<MappingTreeNode>>> childNodesEntrySet = getChildNodes().entrySet();
                childNodesEntrySet.removeIf(childNodesEntry -> {
                    List<MappingTreeNode> listMappingNodes = childNodesEntry.getValue();
                    listMappingNodes.removeIf(tn -> {
                        if (tn.mapping != null) {
                            if (tn.getMapping().id.equals(mapping.id)) {
                                // update the branchingLevel as indicator if other valid mapping in siblings
                                // node exist
                                // in this case the ancestor mapping node must not be deleted, as these sibling
                                // mapping would be deleted as well
                                if (countGrandChildren() > 1) {
                                    branchingLevel.setValue(currentLevel);
                                }
                                log.debug(
                                        "Tenant {} - Deleting mappingNode          : currentPathMonitoring: {}, branchingLevel: {}, mappingId: {}",
                                        tenant,
                                        currentPathMonitoring, branchingLevel, mapping.id);
                                // foundMapping.setTrue();
                                return true;
                            } else
                                return false;
                        } else
                            return false;
                    });
                    if (childNodesEntry.getValue().size() == 0) {
                        foundMapping.setTrue();
                        return true;
                    } else
                        return false; // DUMMY
                });
                return foundMapping.booleanValue();
            } else if (currentLevel < levels.size() - 1) {
                log.debug(
                        "Tenant {} - Deleting innerNode    (?)     : currentPathMonitoring: {}, branchingLevel: {}",
                        tenant,
                        currentPathMonitoring, branchingLevel);
                if (getChildNodes().containsKey(levels.get(currentLevel))) {
                    List<MappingTreeNode> currentChildNodes = getChildNodes().get(levels.get(currentLevel));
                    currentChildNodes.removeIf(tn -> {
                        boolean bm = false;
                        if (!tn.isMappingNode() && !foundMapping.booleanValue()) {
                            // update the branchingLevel as indicator if other valid mapping in siblings
                            // node exist
                            // in this case the ancestor mapping node must not be deleted, as these sibling
                            // mapping would be deleted as well
                            if (countGrandChildren() > 1) {
                                branchingLevel.setValue(currentLevel);
                            }
                            try {
                                bm = tn.deleteMapping(mapping, levels, currentLevel + 1, branchingLevel);
                                foundMapping.setValue(bm);
                            } catch (ResolveException e) {
                                log.error(
                                        "Tenant {} - Deleting mapping error            : currentPathMonitoring: {}, branchingLevel: {}",
                                        tenant,
                                        currentPathMonitoring, branchingLevel, e.getMessage());
                            }
                            if (currentLevel < branchingLevel.getValue()) {
                                log.debug(
                                        "Tenant {} - Deleting innerNode stopped: currentPathMonitoring: {}, branchingLevel: {}",
                                        tenant,
                                        currentPathMonitoring, branchingLevel);
                                bm = false;
                            }
                        }
                        if (bm) {
                            log.debug(
                                    "Tenant {} - Deleting innerNode            : currentPathMonitoring: {}, branchingLevel: {}",
                                    tenant,
                                    currentPathMonitoring, branchingLevel);
                        }
                        return bm;
                    });
                    if (currentChildNodes.size() == 0) {
                        getChildNodes().remove(levels.get(currentLevel));
                    }
                }
            }
        }
        return foundMapping.booleanValue();
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
        //     pathBuilder.append("/");
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
