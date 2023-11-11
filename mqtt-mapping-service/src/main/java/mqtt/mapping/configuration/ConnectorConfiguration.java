package mqtt.mapping.configuration;

import com.fasterxml.jackson.annotation.*;
import lombok.Data;
import lombok.ToString;

import javax.validation.constraints.NotNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
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
    @JsonProperty("connectorId")
    public String connectorId;

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
}
