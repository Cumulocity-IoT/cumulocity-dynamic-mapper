package mqttagent.model;

import java.io.Serializable;
import java.util.ArrayList;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@ToString(exclude = { "depthIndex", "preTreeNode", "level" })
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

    abstract public TreeNode resolveTopicPath(ArrayList<String> tp) throws ResolveException;

}
