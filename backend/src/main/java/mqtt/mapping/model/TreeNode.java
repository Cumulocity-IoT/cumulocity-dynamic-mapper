package mqtt.mapping.model;

import java.io.Serializable;
import java.util.List;

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

    abstract public List<TreeNode> resolveTopicPath(List<String> tp) throws ResolveException;

}
