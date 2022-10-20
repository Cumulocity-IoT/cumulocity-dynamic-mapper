package mqtt.mapping.processor;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import mqtt.mapping.model.Mapping;
import mqtt.mapping.processor.C8YRequest;

import java.util.ArrayList;
import java.util.List;

@Data
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ProcessingContext {
    private Mapping mapping;
    private String deviceIdentifier;

    private String customizeTopic;
    private List<C8YRequest> requests = new ArrayList<C8YRequest>();
    private Exception error;
    public boolean isDeviceIdentifierValid() {
        return deviceIdentifier != null && !deviceIdentifier.equals("");
    }
    public boolean hasError() {
        return error != null;
    }
}
