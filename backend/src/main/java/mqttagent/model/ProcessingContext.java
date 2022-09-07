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
    private MQTTMapping mapping;
    private String deviceIdentifier;
}
