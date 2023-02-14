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

import java.util.*;

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
            log.info("Adding innerNode  : currentLevel: {}, reaminingLevels: {}, currentNode: {} , currentNode.absolutePath: {}",
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
        deleteMapping(mapping, levels);
    }

    private Boolean deleteMapping(Mapping mapping, List<String> remainingLevels) throws ResolveException {
        MutableBoolean stopDeleting = new MutableBoolean(false);
        var currentLevel = remainingLevels.get(0);
        List<TreeNode> specificChildren = getChildNodes().get(currentLevel);
        if (remainingLevels.size() == 1 && specificChildren != null) {
            log.info(
                    "Deleting (?) mappingNode: currentLevel: {}, remainingLevels: {}, currentNode: {}, currentNode.absolutePath: {}, mapping: {}",
                    currentLevel, remainingLevels,  getLevel(), getAbsolutePath(),  mapping.id);
            // find child and remove
            specificChildren.removeIf(tn -> {
                if (tn instanceof MappingNode) {
                    if (((MappingNode) tn).getMapping().id.equals(mapping.id)) {
                        log.info(
                            "Deleting mappingNode    : currentLevel: {}, remainingLevels: {}, currentNode: {}, currentNode.absolutePath: {}, mapping: {}",
                            remainingLevels.get(0), remainingLevels,  tn.getLevel(), tn.getAbsolutePath(),  mapping.id);
                        return true;
                    } else
                        return false;
                } else
                    return true;
            });
            stopDeleting.setFalse();
        } else if (remainingLevels.size() > 1 && specificChildren != null) {
            remainingLevels.remove(0);
            // currentLevel = remainingLevels.get(0);
            // specificChildren = getChildNodes().get(currentLevel);
            log.info("Deleting (?) innerNode  : currentLevel: {}, remainingLevels: {}, currentNode: {} , currentNode.absolutePath: {}",
            currentLevel, remainingLevels, getLevel(), getAbsolutePath());
            InnerNode child;
            if (getChildNodes().containsKey(currentLevel)) {
                if (specificChildren.size() == 1) {
                    if (specificChildren.get(0) instanceof InnerNode) {
                        child = (InnerNode) specificChildren.get(0);
                    } else {
                        throw new ResolveException(
                                "Could not delete mapping to tree, since at this node is already blocked by mapping: "
                                        + specificChildren.get(0).toString());
                    }
                } else {
                    throw new ResolveException(
                            "Could not delete mapping to tree, multiple mappings are only allowed at the end of the tree. This node already contains: "
                                    + specificChildren.size() + " nodes");
                }
                stopDeleting.setValue(child.deleteMapping(mapping, remainingLevels));
                // find child and remove
                if (!stopDeleting.getValue()) {
                    specificChildren.removeIf(tn -> {
                        if (((InnerNode) tn).getChildNodes().size() <= 1) {
                            log.info("Deleting innerNode      : currentLevel: {}, remainingLevels: {}, currentNode: {} , currentNode.absolutePath: {}",
                            currentLevel, remainingLevels, tn.getLevel(), tn.getAbsolutePath());
                            return true;
                        } else {
                            stopDeleting.setValue(true);
                            return false;
                        }
                    });
                log.debug("Current children: {}", specificChildren.size());

                }
            }
        } else {
            //throw new ResolveException("Could not delete mapping from tree: " + mapping.toString());
            log.warn("Could not delete mapping from tree: subscriptionTopic{}, id: {}", mapping.subscriptionTopic, mapping.id);
            stopDeleting.setFalse();
        }
        // test if we are in the root node, then delete the last level
        if (getDepthIndex() == 0 && !stopDeleting.getValue()) {
            log.info("Deleting innerNode      : currentLevel: {}, remainingLevels: {}, currentNode: {} , currentNode.absolutePath: {}",
            currentLevel, remainingLevels, getLevel(), getAbsolutePath());
            getChildNodes().remove(currentLevel);
        }
    
        return stopDeleting.getValue();
    }
}
