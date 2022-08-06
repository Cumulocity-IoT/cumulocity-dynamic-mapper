package mqttagent.configuration;

import lombok.AllArgsConstructor;
import lombok.Data;
import javax.validation.constraints.NotNull;

@Data
@AllArgsConstructor
public class MQTTMappingSubstitution {
    @NotNull
    public String name;

    @NotNull
    public String jsonPath;
}
