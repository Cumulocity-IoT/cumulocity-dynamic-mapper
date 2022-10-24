package mqtt.mapping.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@ToString()
public class MappingNode extends TreeNode{
    @Setter
    @Getter
    private Mapping mapping;

    @Setter
    @Getter
    private long deviceIdentifierIndex;

    public boolean isMappingNode() {
        return true;
    }  

    public List<TreeNode> resolveTopicPath(List<String> tp) throws ResolveException {
        log.debug("Resolved mapping: {}, tp.size(): {}", mapping, tp.size());
         if (tp.size() == 0){
            return new ArrayList<TreeNode> (Arrays.asList(this));
        } else {
            throw new ResolveException("Unknown Resolution Error!");
        }
    }
}
