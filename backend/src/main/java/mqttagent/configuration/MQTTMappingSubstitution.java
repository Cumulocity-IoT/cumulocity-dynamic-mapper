package mqttagent.configuration;

import lombok.AllArgsConstructor;
import lombok.Data;
import javax.validation.constraints.NotNull;

@Data
@AllArgsConstructor
public class MQTTMappingSubstitution {
    @NotNull
    public String pathSource;

    @NotNull
    public String pathTarget;
}
