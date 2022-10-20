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
    private RequestMethod method;
    private String source;
    private String externalIdType;
    private String payload;
    private API targetAPI;
    private Exception error;
    public boolean hasError() {
        return error != null;
    }
}
