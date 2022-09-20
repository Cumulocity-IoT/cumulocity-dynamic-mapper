package mqttagent.model;

import java.io.Serializable;
import java.util.ArrayList;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@ToString()
public abstract class TreeNode implements Serializable {

    public static String SPLIT_TOPIC_REGEXP = "((?<=/)|(?=/))";

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

    abstract public TreeNode resolveTopicPath(ArrayList<String> tp) throws ResolveException;

}
