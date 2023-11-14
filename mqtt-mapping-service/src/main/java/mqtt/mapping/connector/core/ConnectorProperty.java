package mqtt.mapping.connector.core;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.ToString;

import javax.validation.constraints.NotNull;

@Data
@ToString()
@AllArgsConstructor
public class ConnectorProperty implements Cloneable {

    @NotNull
    @JsonSetter(nulls = Nulls.SKIP)
    public Boolean required;

    @NotNull
    @JsonSetter(nulls = Nulls.SKIP)
    public ConnectorPropertyType property;

    public Object clone()
    {
        try {
            return super.clone();
        } catch (CloneNotSupportedException e) {
            return null;
        }
    }
}
