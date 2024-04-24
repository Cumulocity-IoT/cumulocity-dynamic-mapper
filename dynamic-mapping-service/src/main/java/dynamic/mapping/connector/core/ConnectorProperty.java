package dynamic.mapping.connector.core;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.ToString;

import java.util.Map;

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
    public Integer order;

    @NotNull
    @JsonSetter(nulls = Nulls.SKIP)
    public ConnectorPropertyType type;

    @NotNull
    @JsonSetter(nulls = Nulls.SKIP)
    public Boolean readonly;

    @NotNull
    @JsonSetter(nulls = Nulls.SKIP)
    public Boolean hidden;

    @NotNull
    @JsonSetter(nulls = Nulls.SKIP)
    public Object defaultValue;

    @NotNull
    @JsonSetter(nulls = Nulls.SKIP)
    public Map<String, String> options;

    public Object clone() {
        try {
            return super.clone();
        } catch (CloneNotSupportedException e) {
            return null;
        }
    }
}
