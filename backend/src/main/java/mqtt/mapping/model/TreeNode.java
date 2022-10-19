package mqtt.mapping.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@ToString()
public abstract class TreeNode implements Serializable {

    @Setter
    @Getter
    private long depthIndex;
    
    @Setter
    @Getter
    private TreeNode preTreeNode;

    @Setter
    @Getter
    private String absolutePath;

    @Setter
    @Getter
    private String level;

    abstract public boolean isMappingNode();

    abstract public ArrayList<TreeNode> resolveTopicPath(ArrayList<String> tp) throws ResolveException;

    public static ArrayList<String> splitTopic(String topic) {
        return new ArrayList<String>(
            Arrays.asList(Mapping.splitTopic(topic)));
    }
}
