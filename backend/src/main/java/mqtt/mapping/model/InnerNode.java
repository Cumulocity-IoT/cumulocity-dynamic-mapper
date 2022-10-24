package mqtt.mapping.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
    private Map<String, List<TreeNode>> childNodes;

    static public InnerNode initTree() {
        InnerNode in = new InnerNode();
        in.setDepthIndex(0);
        in.setLevel("root");
        in.setPreTreeNode(null);
        in.setAbsolutePath("");
        return in;
    }

    public InnerNode() {
        this.childNodes = new HashMap<String, List<TreeNode>>();
    }

    public boolean isMappingNode() {
        return false;
    }

    public List<TreeNode> resolveTopicPath(List<String> levels) throws ResolveException {
        Set<String> set = childNodes.keySet();
        String joinedSet = String.join(",", set);
        String joinedPath = String.join("", levels);
        log.info("Trying to resolve: '{}' in [{}]", joinedPath, joinedSet);
        List<TreeNode> results = new ArrayList<TreeNode>();
        if (levels.size() >= 1) {
            String currentLevel = levels.get(0);
            levels.remove(0);
            if (childNodes.containsKey(currentLevel)) {
                List<TreeNode> revolvedNodes = childNodes.get(currentLevel);
                for (TreeNode node : revolvedNodes) {
                    results.addAll(node.resolveTopicPath(levels));
                }
            }
            if (childNodes.containsKey(MappingsRepresentation.TOPIC_WILDCARD_SINGLE)) {
                List<TreeNode> revolvedNodes = childNodes.get(MappingsRepresentation.TOPIC_WILDCARD_SINGLE);
                for (TreeNode node : revolvedNodes) {
                    results.addAll(node.resolveTopicPath(levels));
                }
                // test if single level wildcard "+" match exists for this level
            } 
        } else if (levels.size() == 0)
            throw new ResolveException(
                    "Path could not be resolved, since it is end in an InnerNodes instead of a mappingNode!");

        return results;
    }

    public void insertMapping(InnerNode currentNode, Mapping mapping, List<String> levels)
            throws ResolveException {
        var currentLevel = levels.get(0);
        log.debug("Trying to add node: {}, {}, {}, {}", currentNode.getLevel(), currentLevel, currentNode, levels);
        log.info("Adding node: levels: {}, currentLevel: {}, currentNode: {} , currentNode.absolutePath: {}", levels,
                currentLevel, currentNode.getLevel(), currentNode.getAbsolutePath());
        if (levels.size() == 1) {
            MappingNode child = new MappingNode();
            child.setPreTreeNode(currentNode);
            child.setMapping(mapping);
            child.setLevel(currentLevel);
            child.setAbsolutePath(currentNode.getAbsolutePath() + currentLevel);
            child.setDepthIndex(currentNode.getDepthIndex() + 1);
            log.debug("Adding mapNode: {}, {}, {}", currentNode.getLevel(), currentLevel, child.toString());
            List<TreeNode> cn = currentNode.getChildNodes().getOrDefault(currentLevel, new ArrayList<TreeNode>());
            cn.add(child);
            currentNode.getChildNodes().put(currentLevel, cn);
            // currentNode.getChildNodes().put(currentLevel,child);
        } else if (levels.size() > 1) {
            levels.remove(0);
            InnerNode child;
            if (currentNode.getChildNodes().containsKey(currentLevel)) {
                List<TreeNode> cn = currentNode.getChildNodes().get(currentLevel);
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
                List<TreeNode> cn = currentNode.getChildNodes().getOrDefault(currentLevel,
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
        List<String> levels = Mapping.splitTopicIncludingSeparatorAsList(path);
        insertMapping(this, mapping, levels);
    }
}
