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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.lang3.mutable.MutableBoolean;
import org.apache.commons.lang3.mutable.MutableInt;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@ToString
@Setter
@Getter
public class MappingTreeNode {

    private Map<String, List<MappingTreeNode>> childNodes;

    private Mapping mapping;

    private boolean mappingNode;

    private long depthIndex;

    @ToString.Exclude
    private MappingTreeNode parentNode;

    private String absolutePath;

    private String level;

    private String tenant;

    static public MappingTreeNode createRootNode(String tenant) {
        MappingTreeNode in = new MappingTreeNode();
        in.setDepthIndex(0);
        in.setLevel("root");
        in.setTenant(tenant);
        in.setParentNode(null);
        in.setAbsolutePath("");
        in.setMappingNode(false);
        return in;
    }

    public static MappingTreeNode createInnerNode(MappingTreeNode parent, String level) {
        MappingTreeNode node = new MappingTreeNode();
        node.setParentNode(parent);
        node.setLevel(level);
        node.setTenant(parent.getTenant());
        node.setAbsolutePath(parent.getAbsolutePath() + level);
        node.setDepthIndex(parent.getDepthIndex() + 1);
        node.setMappingNode(false);
        return node;
    }

    public static MappingTreeNode createMappingNode(MappingTreeNode parent, String level, Mapping mapping) {
        MappingTreeNode node = new MappingTreeNode();
        node.setParentNode(parent);
        node.setMapping(mapping);
        node.setLevel(level);
        node.setTenant(parent.getTenant());
        node.setAbsolutePath(parent.getAbsolutePath() + level);
        node.setDepthIndex(parent.getDepthIndex() + 1);
        node.setMapping(mapping);
        node.setMappingNode(true);
        return node;
    }

    public MappingTreeNode() {
        this.childNodes = new HashMap<String, List<MappingTreeNode>>();
    }

    public List<MappingTreeNode> resolveTopicPath(List<String> remainingLevels) throws ResolveException {
        Set<String> set = childNodes.keySet();
        String joinedSet = String.join(",", set);
        String joinedPath = String.join("", remainingLevels);
        log.debug("Tenant {} - Trying to resolve: '{}' in [{}]", tenant, joinedPath, joinedSet);
        List<MappingTreeNode> results = new ArrayList<MappingTreeNode>();
        if (remainingLevels.size() >= 1) {
            String currentLevel = remainingLevels.get(0);
            remainingLevels.remove(0);
            if (childNodes.containsKey(currentLevel)) {
                List<MappingTreeNode> revolvedNodes = childNodes.get(currentLevel);
                for (MappingTreeNode node : revolvedNodes) {
                    results.addAll(node.resolveTopicPath(remainingLevels));
                }
            }
            if (childNodes.containsKey(MappingRepresentation.TOPIC_WILDCARD_SINGLE)) {
                List<MappingTreeNode> revolvedNodes = childNodes.get(MappingRepresentation.TOPIC_WILDCARD_SINGLE);
                for (MappingTreeNode node : revolvedNodes) {
                    results.addAll(node.resolveTopicPath(remainingLevels));
                }
                // test if single level wildcard "+" match exists for this level
            }
             else if (childNodes.containsKey(MappingRepresentation.TOPIC_WILDCARD_MULTI)) {
                List<MappingTreeNode> revolvedNodes = childNodes.get(MappingRepresentation.TOPIC_WILDCARD_MULTI);
                for (MappingTreeNode node : revolvedNodes) {
                    results.addAll(node.resolveTopicPath(remainingLevels));
                }
                // test if single level wildcard "+" match exists for this level

            }
        } else if (remainingLevels.size() == 0) {
            if (isMappingNode()) {
                results.add(this);
            } else {
                String remaining = String.join("/", remainingLevels);
                throw new ResolveException(
                        String.format("No mapping registered for this path: %s %s!", this.getAbsolutePath(),
                                remaining));
            }
        }
        return results;
    }

    public void addMapping(Mapping mapping, List<String> levels, int currentLevel)
            throws ResolveException {
        List<MappingTreeNode> specificChildren = getChildNodes().getOrDefault(levels.get(currentLevel),
                new ArrayList<MappingTreeNode>());
        String currentPathMonitoring = createPathMonitoring(levels, currentLevel);
        if (currentLevel == levels.size() - 1) {
            log.debug(
                    "Tenant {} - Adding mappingNode : currentPathMonitoring: {}, currentNode.absolutePath: {}, mappingId : {}",
                    tenant, currentPathMonitoring, getAbsolutePath(), mapping.id);
            MappingTreeNode child = MappingTreeNode.createMappingNode(this, levels.get(currentLevel), mapping);
            log.debug("Tenant {} - Adding mappingNode : currentPathMonitoring {}, child: {}", tenant,
                    currentPathMonitoring,
                    child.toString());
            specificChildren.add(child);
            getChildNodes().put(levels.get(currentLevel), specificChildren);
        } else if (currentLevel < levels.size() - 1) {
            log.debug(
                    "Tenant {} - Adding innerNode   : currentPathMonitoring: {}, currentNode.absolutePath: {}",
                    tenant, currentPathMonitoring, getLevel(), getAbsolutePath());
            MappingTreeNode child;
            if (getChildNodes().containsKey(levels.get(currentLevel))) {
                if (specificChildren.size() == 1) {
                    if (!specificChildren.get(0).isMappingNode()) {
                        child = specificChildren.get(0);
                    } else {
                        throw new ResolveException(String.format(
                                "Could not add mapping to tree, since at this node is already blocked by mappingId : %s",
                                specificChildren.get(0).toString()));
                    }
                } else {
                    throw new ResolveException(String.format(
                            "Could not add mapping to tree, multiple mappings are only allowed at the end of the tree. This node already contains: %s nodes",
                            specificChildren.size()));
                }
            } else {
                child = MappingTreeNode.createInnerNode(this, levels.get(currentLevel));
                log.debug("Tenant {} - Adding innerNode: currentPathMonitoring: {}, child: {}, {}", tenant,
                        currentPathMonitoring,
                        child.toString());
                specificChildren.add(child);
                getChildNodes().put(levels.get(currentLevel), specificChildren);
            }
            child.addMapping(mapping, levels, currentLevel + 1);
        } else {
            throw new ResolveException(String.format("Could not add mapping to tree: %s", mapping.toString()));
        }
    }

    public void addMapping(Mapping mapping) throws ResolveException {
        if (mapping != null) {
            var path = mapping.mappingTopic;
            // if mappingTopic is not set use topic instead
            if (path == null || path.equals("")) {
                path = mapping.subscriptionTopic;
            }
            List<String> levels = Mapping.splitTopicIncludingSeparatorAsList(path);
            addMapping(mapping, levels, 0);
        }
    }

    public void deleteMapping(Mapping mapping) throws ResolveException {
        if (mapping != null) {
            // var path = mapping.mappingTopic;
            var path = mapping.subscriptionTopic;
            // if mappingTopic is not set use topic instead
            if (path == null || path.equals("")) {
                path = mapping.subscriptionTopic;
            }
            List<String> levels = Mapping.splitTopicIncludingSeparatorAsList(path);
            MutableInt branchingLevel = new MutableInt(0);
            deleteMapping(mapping, levels, 0, branchingLevel);
        }
    }

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
                getChildNodes().entrySet().removeIf(tn -> {
                    tn.getValue().removeIf(tnn -> {
                        if (tnn.isMappingNode()) {
                            if (tnn.getMapping().id.equals(mapping.id)) {
                                log.info(
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
                    if (tn.getValue().size() == 0) {
                        foundMapping.setTrue();
                        return true;
                    } else
                        return false; // DUMMY
                });
                return foundMapping.booleanValue();
            } else if (currentLevel < levels.size() - 1) {
                log.debug(
                        "Tenant {} - Deleting innerNode  (?)       : currentPathMonitoring: {}, branchingLevel: {}",
                        tenant,
                        currentPathMonitoring, branchingLevel);
                if (getChildNodes().containsKey(levels.get(currentLevel))) {
                    List<MappingTreeNode> tns = getChildNodes().get(levels.get(currentLevel));
                    tns.removeIf(tn -> {
                        boolean bm = false;
                        if (!tn.isMappingNode() && !foundMapping.booleanValue()) {
                            if (getChildNodes().size() > 1) {
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
                                log.info(
                                        "Tenant {} - Deleting innerNode stopped: currentPathMonitoring: {}, branchingLevel: {}",
                                        tenant,
                                        currentPathMonitoring, branchingLevel);
                                bm = false;
                            }
                        }
                        if (bm) {
                            log.info(
                                    "Tenant {} - Deleting innerNode            : currentPathMonitoring: {}, branchingLevel: {}",
                                    tenant,
                                    currentPathMonitoring, branchingLevel);
                        }
                        return bm;
                    });
                    if (tns.size() == 0) {
                        getChildNodes().remove(levels.get(currentLevel));
                    }
                }
            }
        }
        return foundMapping.booleanValue();
    }

    private String createPathMonitoring(List<String> levels, int currentLevel) {
        List<String> copyLevels = levels.stream().collect(Collectors.toList());
        String cl = levels.get(currentLevel);
        copyLevels.set(currentLevel, "__" + cl + "__");
        return copyLevels.toString();
    }
}
