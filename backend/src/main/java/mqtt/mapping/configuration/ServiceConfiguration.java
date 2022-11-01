package mqtt.mapping.configuration;
import javax.validation.constraints.NotNull;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Data
@ToString ()
@AllArgsConstructor
public class ServiceConfiguration implements Cloneable {
    public ServiceConfiguration () {
        this.logPayload = false;
        this.logSubstitution = false;
    }

    @NotNull
    @JsonSetter(nulls = Nulls.SKIP)
    public boolean logPayload;

    @NotNull
    @JsonSetter(nulls = Nulls.SKIP)
    public boolean logSubstitution;
}
