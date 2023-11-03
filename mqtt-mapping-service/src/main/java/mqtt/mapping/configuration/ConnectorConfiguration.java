package mqtt.mapping.configuration;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.ToString;

import javax.validation.constraints.NotNull;
import java.util.Map;

@Data
@ToString()
@AllArgsConstructor
public class ConnectorConfiguration implements Cloneable {

    @NotNull
    @JsonSetter(nulls = Nulls.SKIP)
    public String connectorId;

    @NotNull
    public boolean enabled;

    @NotNull
    @JsonSetter(nulls = Nulls.SKIP)
    public Map<String, Object> properties;

    public boolean isEnabled() {
        return this.enabled;
    }
    public Object clone()
    {
        try {
            return super.clone();
        } catch (CloneNotSupportedException e) {
            return null;
        }
    }
}
