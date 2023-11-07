package mqtt.mapping.configuration;

import com.fasterxml.jackson.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.ToString;

import javax.validation.constraints.NotNull;
import java.util.Map;

@Data
@ToString()
public class ConnectorConfiguration implements Cloneable {

    public ConnectorConfiguration() {
        super();
    }

    @NotNull
    @JsonSetter(nulls = Nulls.SKIP)
    @JsonProperty("connectorId")
    public String connectorId;

    @NotNull
    @JsonProperty("enabled")
    public boolean enabled;

    @NotNull
    @JsonProperty("properties")
    public Map<String, Object> properties;

    /*
    @JsonAnySetter
    public void add(String key, Object value) {
        properties.put(key, value);
    }

    @JsonAnyGetter
    public Map<String, Object> getProperties() {
        return properties;
    }

     */

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
