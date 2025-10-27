package dynamic.mapper.processor.flow;

import lombok.Getter;
import lombok.Setter;

/**
 * Details of Cumulocity internal ID
 */
@Getter
@Setter
public class CumulocitySource {
    
    /** Cumulocity ID to be looked up and/or created to get C8Y "id" */
    private String internalId;
    
    // Constructors
    public CumulocitySource() {}
    
    public CumulocitySource(String internalId) {
        this.internalId = internalId;
    }
}