package mqtt.mapping.processor;

import java.util.ArrayList;

import lombok.Data;
import lombok.NoArgsConstructor;
import mqtt.mapping.model.Mapping;

@Data
@NoArgsConstructor
public class ProcessingContext {
    private Mapping mapping;
    private String deviceIdentifier;
    private String topic;
    private String payload;
    private ArrayList <C8YRequest> requests = new ArrayList<C8YRequest>();
    private Exception error;
    public boolean isDeviceIdentifierValid() {
        return !"".equals(deviceIdentifier);
    }
    public boolean hasError() {
        return error != null;
    }
    public int addRequest(C8YRequest c8yRequest) {
        requests.add(c8yRequest);
        return requests.size()-1;
    }

}