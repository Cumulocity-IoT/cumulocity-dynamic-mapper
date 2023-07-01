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
public class InnerNode extends TreeNode {

    @Setter
    @Getter
    private Map<String, List<TreeNode>> childNodes;

    static public InnerNode createRootNode() {
        InnerNode in = new InnerNode();
        in.setDepthIndex(0);
        in.setLevel("root");
        in.setParentNode(null);
        in.setAbsolutePath("");
        return in;
    }

    public static InnerNode createInnerNode(InnerNode parent, String level) {
        InnerNode node = new InnerNode();
        node.setParentNode(parent);
        node.setLevel(level);
        node.setAbsolutePath(parent.getAbsolutePath() + level);
        node.setDepthIndex(parent.getDepthIndex() + 1);
        return node;
    }

    public InnerNode() {
        this.childNodes = new HashMap<String, List<TreeNode>>();
    }

    public boolean isMappingNode() {
        return false;
    }

    public List<TreeNode> resolveTopicPath(List<String> remainingLevels) throws ResolveException {
        Set<String> set = childNodes.keySet();
        String joinedSet = String.join(",", set);
        String joinedPath = String.join("", remainingLevels);
        log.debug("Trying to resolve: '{}' in [{}]", joinedPath, joinedSet);
        List<TreeNode> results = new ArrayList<TreeNode>();
        if (remainingLevels.size() >= 1) {
            String currentLevel = remainingLevels.get(0);
            remainingLevels.remove(0);
            if (childNodes.containsKey(currentLevel)) {
                List<TreeNode> revolvedNodes = childNodes.get(currentLevel);
                for (TreeNode node : revolvedNodes) {
                    results.addAll(node.resolveTopicPath(remainingLevels));
                }
            }
            if (childNodes.containsKey(MappingRepresentation.TOPIC_WILDCARD_SINGLE)) {
                List<TreeNode> revolvedNodes = childNodes.get(MappingRepresentation.TOPIC_WILDCARD_SINGLE);
                for (TreeNode node : revolvedNodes) {
                    results.addAll(node.resolveTopicPath(remainingLevels));
                }
                // test if single level wildcard "+" match exists for this level
            }
        } else if (remainingLevels.size() == 0)
            throw new ResolveException(
                    "Path could not be resolved, since it is end in an InnerNodes instead of a mappingNode!");

        return results;
    }

    public void addMapping(Mapping mapping, List<String> remainingLevels)
            throws ResolveException {
        var currentLevel = remainingLevels.get(0);
        List<TreeNode> specificChildren = getChildNodes().getOrDefault(currentLevel, new ArrayList<TreeNode>());
        if (remainingLevels.size() == 1) {
            log.info(
                    "Adding mappingNode: currentLevel: {}, reaminingLevels: {}, currentNode: {}, currentNode.absolutePath: {}, mapping: {}",
                    currentLevel,
                    remainingLevels, getLevel(), getAbsolutePath(), mapping.id);
            MappingNode child = MappingNode.createMappingNode(this, mapping, currentLevel);
            log.debug("Adding mappingNode: {}, {}, {}", getLevel(), currentLevel, child.toString());
            specificChildren.add(child);
            getChildNodes().put(currentLevel, specificChildren);
        } else if (remainingLevels.size() > 1) {
            remainingLevels.remove(0);
            log.info(
                    "Adding innerNode  : currentLevel: {}, reaminingLevels: {}, currentNode: {} , currentNode.absolutePath: {}",
                    currentLevel, remainingLevels, getLevel(), getAbsolutePath());
            InnerNode child;
            if (getChildNodes().containsKey(currentLevel)) {
                if (specificChildren.size() == 1) {
                    if (specificChildren.get(0) instanceof InnerNode) {
                        child = (InnerNode) specificChildren.get(0);
                    } else {
                        throw new ResolveException(
                                "Could not add mapping to tree, since at this node is already blocked by mapping: "
                                        + specificChildren.get(0).toString());
                    }
                } else {
                    throw new ResolveException(
                            "Could not add mapping to tree, multiple mappings are only allowed at the end of the tree. This node already contains: "
                                    + specificChildren.size() + " nodes");
                }
            } else {
                child = InnerNode.createInnerNode(this, currentLevel);
                log.debug("Adding innerNode: {}, {}, {}", getLevel(), currentLevel, child.toString());
                specificChildren.add(child);
                getChildNodes().put(currentLevel, specificChildren);
            }
            child.addMapping(mapping, remainingLevels);
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
        addMapping(mapping, levels);
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
        if (getChildNodes().size() > 1) {
            branchingLevel.setValue(currentLevel);
        }

        if (currentLevel == levels.size() - 1 && hasChildren) {
            log.info(
                    "Deleting mappingNode (?)      : currentPathMonitoring: {}, branchingLevel: {}, mapppingId: {}",
                    currentPathMonitoring, branchingLevel, mapping.id);
            // find child and remove
            getChildNodes().entrySet().removeIf(tn -> {
                if (tn.getValue().get(0) instanceof MappingNode) {
                    if (((MappingNode) tn.getValue().get(0)).getMapping().id.equals(mapping.id)) {
                        log.info(
                                "Deleting mappingNode          : currentPathMonitoring: {}, branchingLevel: {}, mapppingId: {}",
                                currentPathMonitoring, branchingLevel, mapping.id);
                        foundMapping.setTrue();
                        return true;
                    } else
                        return false;
                } else
                    return true;
            });
            return foundMapping.booleanValue();
        } else if (currentLevel < levels.size() - 1 && hasChildren) {
            log.info(
                    "Deleting innerNode  (?)       : currentPathMonitoring: {}, branchingLevel: {}",
                    currentPathMonitoring, branchingLevel);
            if (getChildNodes().containsKey(levels.get(currentLevel))) {
                List<TreeNode> list = getChildNodes().get(levels.get(currentLevel));
                list.removeIf(tn -> {
                    boolean bm = false;
                    if (tn instanceof InnerNode) {
                        try {
                            bm = ((InnerNode) tn).deleteMapping(mapping, levels, currentLevel + 1, branchingLevel);
                            foundMapping.setValue(bm);
                        } catch (ResolveException e) {
                            log.error(
                                    "Deleting mapping error            : currentPathMonitoring: {}, branchingLevel: {}",
                                    currentPathMonitoring, branchingLevel, e.getMessage());
                        }
                        if (currentLevel  < branchingLevel.getValue()) {
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
                if (list.size() == 0 ) {
                    getChildNodes().remove(levels.get(currentLevel));
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
