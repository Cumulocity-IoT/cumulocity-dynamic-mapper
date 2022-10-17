package mqtt.mapping.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@ToString
public class InnerNode extends TreeNode {

    @Setter
    @Getter
    private Map<String, ArrayList<TreeNode>> childNodes;

    static public InnerNode initTree() {
        InnerNode in = new InnerNode();
        in.setDepthIndex(0);
        in.setLevel("root");
        in.setPreTreeNode(null);
        in.setAbsolutePath("");
        return in;
    }

    public InnerNode() {
        this.childNodes = new HashMap<String, ArrayList<TreeNode>>();
    }

    public boolean isMappingNode() {
        return false;
    }

    public ArrayList<TreeNode> resolveTopicPath(ArrayList<String> levels) throws ResolveException {
        Set<String> set = childNodes.keySet();
        String joinedSet = String.join(",", set);
        String joinedPath = String.join("", levels);
        log.info("Trying to resolve: '{}' in [{}]", joinedPath, joinedSet);
        ArrayList<TreeNode> tn = new ArrayList<TreeNode>();
        ArrayList<TreeNode> results = new ArrayList<TreeNode>();
        if (levels.size() >= 1) {
            if (childNodes.containsKey(levels.get(0))) {
                tn = childNodes.get(levels.get(0));
                // test if exact match exists for this level
            } else if (childNodes.containsKey(MappingsRepresentation.TOPIC_WILDCARD_MULTI)) {
                tn = childNodes.get(MappingsRepresentation.TOPIC_WILDCARD_MULTI);
                // test if multi level wildcard "#" match exists for this level
            } else if (childNodes.containsKey(MappingsRepresentation.TOPIC_WILDCARD_SINGLE)) {
                tn = childNodes.get(MappingsRepresentation.TOPIC_WILDCARD_SINGLE);
                // test if single level wildcard "+" match exists for this level
            } else {
                throw new ResolveException("Path: " + levels.get(0).toString() + " could not be resolved further!");
            }
            if (levels.size() > 1) {
                if (tn.size() > 1) {
                    throw new ResolveException("Path: " + levels.get(0).toString()
                    + " could not be resolved uniquely, since too many innerNodes exist: " + tn.size() + "!");
                }
                levels.remove(0);
                return tn.get(0).resolveTopicPath(levels);
            } else if (levels.size() == 1) {
                levels.remove(0);
                for (TreeNode node : tn) {
                    results.addAll(node.resolveTopicPath(levels));
                }
            }  
        } else {
            throw new ResolveException("Unknown Resolution Error!");
        }
        return results;
    }

    public void insertMapping(InnerNode currentNode, Mapping mapping, ArrayList<String> levels)
            throws ResolveException {
        var currentLevel = levels.get(0);
        //log.info("Trying to add node: {}, {}, {}, {}", currentNode.getLevel(), currentLevel, currentNode, levels);
        log.info("Adding node: {}, {}, {}", levels, currentLevel, currentNode.getLevel());
        if (levels.size() == 1) {
            MappingNode child = new MappingNode();
            child.setPreTreeNode(currentNode);
            child.setMapping(mapping);
            child.setLevel(currentLevel);
            child.setAbsolutePath(currentNode.getAbsolutePath() + currentLevel);
            child.setDeviceIdentifierIndex(mapping.indexDeviceIdentifierInTemplateTopic);
            child.setDepthIndex(currentNode.getDepthIndex() + 1);
            log.debug("Adding mapNode: {}, {}, {}", currentNode.getLevel(), currentLevel, child.toString());
            ArrayList<TreeNode> cn = currentNode.getChildNodes().getOrDefault(currentLevel, new ArrayList<TreeNode>());
            cn.add(child);
            currentNode.getChildNodes().put(currentLevel, cn);
            // currentNode.getChildNodes().put(currentLevel,child);
        } else if (levels.size() > 1) {
            levels.remove(0);
            InnerNode child;
            if (currentNode.getChildNodes().containsKey(currentLevel)) {
                ArrayList<TreeNode> cn = currentNode.getChildNodes().get(currentLevel);
                if (cn.size() == 1) {
                    if (cn.get(0) instanceof InnerNode) {
                        child = (InnerNode) cn.get(0);
                    } else {
                        throw new ResolveException(
                                "Could not add mapping to tree, since at this node is already blocked by mapping: "
                                        + cn.get(0).toString());
                    }
                } else {
                    throw new ResolveException(
                            "Could not add mapping to tree, multiple mappings are only allowed at the end of the tree. This node already contains: "
                                    + cn.size() + " nodes");
                }
            } else {
                child = new InnerNode();
                child.setPreTreeNode(currentNode);
                child.setLevel(currentLevel);
                child.setAbsolutePath(currentNode.getAbsolutePath() + currentLevel);
                child.setDepthIndex(currentNode.getDepthIndex() + 1);
                log.debug("Adding innerNode: {}, {}, {}", currentNode.getLevel(), currentLevel, child.toString());
                ArrayList<TreeNode> cn = currentNode.getChildNodes().getOrDefault(currentLevel,
                        new ArrayList<TreeNode>());
                cn.add(child);
                currentNode.getChildNodes().put(currentLevel, cn);
                // currentNode.getChildNodes().put(currentLevel,child);
            }
            child.insertMapping(child, mapping, levels);
        } else {
            throw new ResolveException("Could not add mapping to tree: " + mapping.toString());
        }
    }

    public void insertMapping(Mapping mapping) throws ResolveException {
        var path = mapping.templateTopic;
        // if templateTopic is not set use topic instead
        if (path == null || path.equals("")) {
            path = mapping.subscriptionTopic;
        }
        ArrayList<String> levels = new ArrayList<String>(Arrays.asList(path.split(TreeNode.SPLIT_TOPIC_REGEXP)));
        insertMapping(this, mapping, levels);
    }
}
