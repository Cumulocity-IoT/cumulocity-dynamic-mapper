package mqtt.mapping.processor;

import org.springframework.web.bind.annotation.RequestMethod;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import mqtt.mapping.model.API;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class C8YRequest {
    private int predecessor = -1;;
    private RequestMethod method;
    private String source;
    private String externalIdType;
    private String payload;
    private API targetAPI;
    private Exception error;
    // this property documents if a C8Y request was already submitted and is created only for documentation/testing purpose.
    // this happend when a device is created implicitly with mapping.createNonExistingDevice == true
    // private boolean alreadySubmitted;
    public boolean hasError() {
        return error != null;
    }
}
