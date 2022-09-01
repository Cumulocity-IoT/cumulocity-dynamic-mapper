package mqttagent.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;


@Data
@NoArgsConstructor
@AllArgsConstructor
public class MQTTMappingsRepresentation implements Serializable {
    
    @JsonProperty("id")
    private String id;

    @JsonProperty("type")
    private String type;

    @JsonProperty(value = "name")
    private String name;

    @JsonProperty(value = "description")
    private String description;

    @JsonProperty(value = "c8y_mqttMapping")
    private ArrayList<MQTTMapping> c8yMQTTMapping;

    private Map<String, Object> dynamicProperties;

    @JsonAnyGetter
    public Map<String, Object> getDynamicProperties() {
        return dynamicProperties;
    }

    @JsonAnySetter
    public void setDynamicProperties(String key, Object value) {
        if (dynamicProperties == null) {
            this.dynamicProperties = new LinkedHashMap<>();
        }
        this.dynamicProperties.put(key, value);
    }
}
