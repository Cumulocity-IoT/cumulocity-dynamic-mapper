package mqttagent.model;

import java.util.ArrayList;

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

    public TreeNode resolveTopicPath(ArrayList<String> tp) throws ResolveException {
        log.info("Resolved mapping:{}, tp.size()", mapping, tp.size());
         if (tp.size() == 0){
            return this;
        } else {
            throw new ResolveException("Unknown Resolution Error!");
        }
    }
}
