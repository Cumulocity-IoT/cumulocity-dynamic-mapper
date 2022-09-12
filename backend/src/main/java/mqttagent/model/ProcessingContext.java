package mqttagent.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Data
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ProcessingContext {
    private Mapping mapping;
    private String deviceIdentifier;
    public boolean isDeviceIdentifierValid() {
        return deviceIdentifier != null && !deviceIdentifier.equals("");
    }
}
