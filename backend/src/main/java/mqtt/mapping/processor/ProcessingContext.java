package mqtt.mapping.processor;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;
import lombok.NoArgsConstructor;
import mqtt.mapping.model.Mapping;

@Data
@NoArgsConstructor
public class ProcessingContext {
    private Mapping mapping;
    private String deviceIdentifier;
    private List <C8YRequest> requests = new ArrayList<C8YRequest>();
    private Exception error;
    public boolean isDeviceIdentifierValid() {
        return deviceIdentifier != null && !deviceIdentifier.equals("");
    }
    public boolean hasError() {
        return error != null;
    }
}
