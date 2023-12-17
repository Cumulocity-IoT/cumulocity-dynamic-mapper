package dynamic.mapping.connector.core;

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
public class ConnectorSpecification implements Cloneable {

    @NotNull
    @JsonSetter(nulls = Nulls.SKIP)
    public String connectorId;

    @NotNull
    @JsonSetter(nulls = Nulls.SKIP)
    public boolean supportsWildcardInTopic;

    @NotNull
    @JsonSetter(nulls = Nulls.SKIP)
    public Map<String, ConnectorProperty> properties;

    public boolean isPropetySensitive(String property) {
        return ConnectorPropertyType.SENSITIVE_STRING_PROPERTY == properties.get(property).type;
    }

    public Object clone() {
        try {
            return super.clone();
        } catch (CloneNotSupportedException e) {
            return null;
        }
    }
}
