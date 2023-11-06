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

package mqtt.mapping.model;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.mutable.MutableBoolean;
import org.apache.commons.lang3.mutable.MutableInt;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@ToString
public class InnerNode {

    @Setter
    @Getter
    private Map<String, List<InnerNode>> childNodes;

    @Getter
    @Setter
    private Mapping mapping;

    @Getter
    @Setter
    private boolean mappingNode;

    @Setter
    @Getter
    private long depthIndex;

    @Setter
    @Getter
    @ToString.Exclude
    private InnerNode parentNode;

    @Setter
    @Getter
    private String absolutePath;

    @Setter
    @Getter
    private String level;

    static public InnerNode createRootNode() {
        InnerNode in = new InnerNode();
        in.setDepthIndex(0);
        in.setLevel("root");
        in.setParentNode(null);
        in.setAbsolutePath("");
        in.setMappingNode(false);
        return in;
    }

    public static InnerNode createInnerNode(InnerNode parent, String level) {
        InnerNode node = new InnerNode();
        node.setParentNode(parent);
        node.setLevel(level);
        node.setAbsolutePath(parent.getAbsolutePath() + level);
        node.setDepthIndex(parent.getDepthIndex() + 1);
        node.setMappingNode(false);
        return node;
    }

    public static InnerNode createMappingNode(InnerNode parent, String level, Mapping mapping) {
        InnerNode node = new InnerNode();
        node.setParentNode(parent);
        node.setMapping(mapping);
        node.setLevel(level);
        node.setAbsolutePath(parent.getAbsolutePath() + level);
        node.setDepthIndex(parent.getDepthIndex() + 1);
        node.setMapping(mapping);
        node.setMappingNode(true);
        return node;
    }

    public InnerNode() {
        this.childNodes = new HashMap<String, List<InnerNode>>();
    }

    public List<InnerNode> resolveTopicPath(List<String> remainingLevels) throws ResolveException {
        Set<String> set = childNodes.keySet();
        String joinedSet = String.join(",", set);
        String joinedPath = String.join("", remainingLevels);
        log.debug("Trying to resolve: '{}' in [{}]", joinedPath, joinedSet);
        List<InnerNode> results = new ArrayList<InnerNode>();
        if (remainingLevels.size() >= 1) {
            String currentLevel = remainingLevels.get(0);
            remainingLevels.remove(0);
            if (childNodes.containsKey(currentLevel)) {
                List<InnerNode> revolvedNodes = childNodes.get(currentLevel);
                for (InnerNode node : revolvedNodes) {
                    results.addAll(node.resolveTopicPath(remainingLevels));
                }
            }
            if (childNodes.containsKey(MappingRepresentation.TOPIC_WILDCARD_SINGLE)) {
                List<InnerNode> revolvedNodes = childNodes.get(MappingRepresentation.TOPIC_WILDCARD_SINGLE);
                for (InnerNode node : revolvedNodes) {
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
                        "No mapping registered for this path: " + this.getAbsolutePath() + remaining + "!");
            }
        }
        return results;
    }

    public void addMapping(Mapping mapping, List<String> levels, int currentLevel)
            throws ResolveException {
        List<InnerNode> specificChildren = getChildNodes().getOrDefault(levels.get(currentLevel),
                new ArrayList<InnerNode>());
        String currentPathMonitoring = createPathMonitoring(levels, currentLevel);
        if (currentLevel == levels.size() - 1) {
            log.info(
                    "Adding mappingNode : currentPathMonitoring: {}, currentNode.absolutePath: {}, mappingId : {}",
                    currentPathMonitoring, getAbsolutePath(), mapping.id);
            InnerNode child = InnerNode.createMappingNode(this, levels.get(currentLevel), mapping);
            log.debug("Adding mappingNode : currentPathMonitoring {}, child: {}", currentPathMonitoring,
                    child.toString());
            specificChildren.add(child);
            getChildNodes().put(levels.get(currentLevel), specificChildren);
        } else if (currentLevel < levels.size() - 1) {
            log.info(
                    "Adding innerNode   : currentPathMonitoring: {}, currentNode.absolutePath: {}",
                    currentPathMonitoring, getLevel(), getAbsolutePath());
            InnerNode child;
            if (getChildNodes().containsKey(levels.get(currentLevel))) {
                if (specificChildren.size() == 1) {
                    if (!specificChildren.get(0).isMappingNode()) {
                        child = specificChildren.get(0);
                    } else {
                        throw new ResolveException(
                                "Could not add mapping to tree, since at this node is already blocked by mappingId : "
                                        + specificChildren.get(0).toString());
                    }
                } else {
                    throw new ResolveException(
                            "Could not add mapping to tree, multiple mappings are only allowed at the end of the tree. This node already contains: "
                                    + specificChildren.size() + " nodes");
                }
            } else {
                child = InnerNode.createInnerNode(this, levels.get(currentLevel));
                log.debug("Adding innerNode: currentPathMonitoring: {}, child: {}, {}", currentPathMonitoring,
                        child.toString());
                specificChildren.add(child);
                getChildNodes().put(levels.get(currentLevel), specificChildren);
            }
            child.addMapping(mapping, levels, currentLevel + 1);
        } else {
            throw new ResolveException("Could not add mapping to tree: " + mapping.toString());
        }
    }

    public void addMapping(Mapping mapping) throws ResolveException {
        var path = mapping.templateTopic;
        // if templateTopic is not set use topic instead
        if (path == null || path.equals("")) {
            path = mapping.subscriptionTopic;
        }
        List<String> levels = Mapping.splitTopicIncludingSeparatorAsList(path);
        addMapping(mapping, levels, 0);
    }

    public void deleteMapping(Mapping mapping) throws ResolveException {
        var path = mapping.templateTopic;
        // if templateTopic is not set use topic instead
        if (path == null || path.equals("")) {
            path = mapping.subscriptionTopic;
        }
        List<String> levels = Mapping.splitTopicIncludingSeparatorAsList(path);
        MutableInt branchingLevel = new MutableInt(0);
        deleteMapping(mapping, levels, 0, branchingLevel);
    }

    private boolean deleteMapping(Mapping mapping, List<String> levels, int currentLevel, MutableInt branchingLevel)
            throws ResolveException {
        MutableBoolean foundMapping = new MutableBoolean(false);
        String currentPathMonitoring = createPathMonitoring(levels, currentLevel);
        boolean hasChildren = getChildNodes() != null && getChildNodes().size() > 0;
        if (hasChildren) {
            if (currentLevel == levels.size() - 1) {
                log.info(
                        "Deleting mappingNode (?)      : currentPathMonitoring: {}, branchingLevel: {}, mapppingId: {}",
                        currentPathMonitoring, branchingLevel, mapping.id);
                // find child and remove
                getChildNodes().entrySet().removeIf(tn -> {
                    tn.getValue().removeIf(tnn -> {
                        if (tnn.isMappingNode()) {
                            if (tnn.getMapping().id.equals(mapping.id)) {
                                log.info(
                                        "Deleting mappingNode          : currentPathMonitoring: {}, branchingLevel: {}, mapppingId: {}",
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
                log.info(
                        "Deleting innerNode  (?)       : currentPathMonitoring: {}, branchingLevel: {}",
                        currentPathMonitoring, branchingLevel);
                if (getChildNodes().containsKey(levels.get(currentLevel))) {
                    List<InnerNode> tns = getChildNodes().get(levels.get(currentLevel));
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
                                        "Deleting mapping error            : currentPathMonitoring: {}, branchingLevel: {}",
                                        currentPathMonitoring, branchingLevel, e.getMessage());
                            }
                            if (currentLevel < branchingLevel.getValue()) {
                                log.info(
                                        "Deleting innerNode stopped: currentPathMonitoring: {}, branchingLevel: {}",
                                        currentPathMonitoring, branchingLevel);
                                bm = false;
                            }
                        }
                        if (bm) {
                            log.info(
                                    "Deleting innerNode            : currentPathMonitoring: {}, branchingLevel: {}",
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
