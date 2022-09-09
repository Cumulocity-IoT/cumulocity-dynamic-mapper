package mqttagent.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;

import org.apache.commons.lang3.mutable.MutableBoolean;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MappingsRepresentation implements Serializable {

  static String TOPIC_WILDCARD = "#";

  @JsonProperty("id")
  private String id;

  @JsonProperty("type")
  private String type;

  @JsonProperty(value = "name")
  private String name;

  @JsonProperty(value = "description")
  private String description;

  @JsonProperty(value = "c8y_mqttMapping")
  private ArrayList<Mapping> c8yMQTTMapping;

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

  static public boolean checkTopicIsUnique(ArrayList<Mapping> mappings, Mapping mapping) {
    var topic = mapping.topic;
    MutableBoolean result = new MutableBoolean(true);
    mappings.forEach(m -> {
      if (topic.equals(m.topic) && (mapping.id != m.id)) {
        result.setFalse();
      }
    });
    return result.booleanValue();
  }

  static public boolean checkTemplateTopicIsUnique(ArrayList<Mapping> mappings, Mapping mapping) {
    var topic = mapping.templateTopic;
    MutableBoolean result = new MutableBoolean(true);
    mappings.forEach(m -> {
      if (topic.equals(m.templateTopic) && (mapping.id != m.id)) {
        result.setFalse();
      }
    });
    return result.booleanValue();
  }

  static public String normalizeTopic(String topic) {
    String nt = topic.trim().replace("\\/+$", "").replace("^\\/+", "");
    // append trailing slash if last character is not wildcard #
    nt = nt.concat(nt.endsWith(TOPIC_WILDCARD) ? "" : "/");
    return nt;
  }

  static public Long nextId(ArrayList<Mapping> mappings) {
    Long max = mappings
        .stream()
        .mapToLong(v -> v.id)
        .max().orElseThrow(NoSuchElementException::new);
    return max + 1L;
  }
}
