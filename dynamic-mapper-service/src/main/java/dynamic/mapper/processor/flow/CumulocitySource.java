package dynamic.mapper.processor.flow;

/**
 * Details of Cumulocity internal ID
 */
public class CumulocitySource {
    
    /** Cumulocity ID to be looked up and/or created to get C8Y "id" */
    private String internalId;
    
    // Constructors
    public CumulocitySource() {}
    
    public CumulocitySource(String internalId) {
        this.internalId = internalId;
    }
    
    // Getters and Setters
    public String getInternalId() { return internalId; }
    public void setInternalId(String internalId) { this.internalId = internalId; }
}