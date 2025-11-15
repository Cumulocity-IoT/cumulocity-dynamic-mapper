package dynamic.mapper.processor.flow;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * Details of external ID which will be looked up by IDP to get the C8Y id.
 */
@Setter
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ExternalId {
    
    /**
     * External id to be looked up and/or created to get C8Y "id"
     */
    private String externalId;
    
    /**
     * External id type e.g. "c8y_Serial"
     */
    private String type;
}