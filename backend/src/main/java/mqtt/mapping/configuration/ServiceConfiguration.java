package mqtt.mapping.configuration;
import javax.validation.constraints.NotNull;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Data
@ToString ()
@NoArgsConstructor
@AllArgsConstructor
public class ServiceConfiguration implements Cloneable {
    @NotNull
    public boolean logPayload;
}
