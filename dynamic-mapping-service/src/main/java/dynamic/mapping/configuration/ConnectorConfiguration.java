package dynamic.mapping.configuration;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;

import dynamic.mapping.connector.core.ConnectorProperty;
import dynamic.mapping.connector.core.ConnectorSpecification;
import dynamic.mapping.connector.core.client.ConnectorType;
import lombok.Data;
import lombok.ToString;

import javax.validation.constraints.NotNull;
import java.io.*;
import java.util.Map;

@Data
@ToString()
public class ConnectorConfiguration implements Cloneable, Serializable {

    public ConnectorConfiguration() {
        super();
    }

    @NotNull
    @JsonSetter(nulls = Nulls.SKIP)
    @JsonProperty("ident")
    public String ident;

    @NotNull
    @JsonSetter(nulls = Nulls.SKIP)
    @JsonProperty("connectorType")
    public ConnectorType connectorType;

    @NotNull
    @JsonProperty("enabled")
    public boolean enabled;

    @NotNull
    @JsonProperty("name")
    public String name;

    @NotNull
    @JsonProperty("properties")
    public Map<String, Object> properties;

    /*
     * @JsonAnySetter
     * public void add(String key, Object value) {
     * properties.put(key, value);
     * }
     * 
     * @JsonAnyGetter
     * public Map<String, Object> getProperties() {
     * return properties;
     * }
     * 
     */

    public boolean isEnabled() {
        return this.enabled;
    }

    public Object clone() {
        Object result = null;
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(bos);
            oos.writeObject(this);
            oos.flush();
            oos.close();
            bos.close();
            byte[] byteData = bos.toByteArray();
            ByteArrayInputStream bais = new ByteArrayInputStream(byteData);
            result = new ObjectInputStream(bais).readObject();
        } catch (Exception e) {
            return null;
        }
        return result;
        // this way we don't get a deep clone
        // try {
        // return super.clone();
        // } catch (CloneNotSupportedException e) {
        // return null;
        // }
    }

    public void copyPredefinedValues(ConnectorSpecification spec) {
        spec.getProperties().entrySet().forEach(prop -> {
            ConnectorProperty p = prop.getValue();
            if (!p.readonly) {
                properties.put(prop.getKey(), p.defaultValue);
            }
        });
    }
}
