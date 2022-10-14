package mqtt.mapping.model;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class ProcessingContext {
    private Mapping mapping;
    private String deviceIdentifier;
    public boolean isDeviceIdentifierValid() {
        return deviceIdentifier != null && !deviceIdentifier.equals("");
    }
}
