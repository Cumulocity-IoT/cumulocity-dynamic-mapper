package mqttagent.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
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

  static String TOPIC_WILDCARD_MULTI = "#";
  static String TOPIC_WILDCARD_SINGLE = "+";

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


  static public boolean isWildcardTopic(String topic) {
    var result = topic.contains(TOPIC_WILDCARD_MULTI) || topic.contains(TOPIC_WILDCARD_SINGLE);
    return result;
  }

  /*
   * only one substitution can be marked with definesIdentifier == true
   */
  static public ArrayList<ValidationError> isSubstituionValid(Mapping mapping) {
    ArrayList<ValidationError> result = new ArrayList<ValidationError>();
    long count =Arrays.asList(mapping.substitutions).stream().filter(sub -> sub.definesIdentifier).count();
    if (count > 1) {
      result.add(ValidationError.Only_One_Substitution_Defining_Device_Identifier_Can_Be_Used);
    }
    return result;
  }

  static public ArrayList<ValidationError> isTopicNameValid(String topic) {
    ArrayList<ValidationError> result = new ArrayList<ValidationError>();
    int count = topic.length() - topic.replace(TOPIC_WILDCARD_SINGLE, "").length();
    if (count > 1) {
      result.add(ValidationError.Only_One_Single_Level_Wildcard);
    }
    count = topic.length() - topic.replace(TOPIC_WILDCARD_MULTI, "").length();
    if (count > 1) {
      result.add(ValidationError.Only_One_Multi_Level_Wildcard);
    }
    if (count >= 1 && topic.indexOf(TOPIC_WILDCARD_MULTI) != topic.length() - 1) {
      result.add(ValidationError.Multi_Level_Wildcard_Only_At_End);
    }
    return result;
  }

  static public ArrayList<ValidationError> isTemplateTopicValid(Mapping mapping) {
    ArrayList<ValidationError> result = new ArrayList<ValidationError>();
    boolean error = (!mapping.templateTopic.startsWith(mapping.topic));
    if (error) {
      result.add(ValidationError.Topic_Must_Be_Substring_Of_TemplateTopic);
    }
    return result;
  }

  static public ArrayList<ValidationError> isTemplateTopicUnique(ArrayList<Mapping> mappings, Mapping mapping) {
    ArrayList<ValidationError> result = new ArrayList<ValidationError>();
    var templateTopic = mapping.templateTopic;
    mappings.forEach(m -> {
      if ((templateTopic.startsWith(m.templateTopic) || m.templateTopic.startsWith(templateTopic) ) && (mapping.id != m.id)) {
        result.add(ValidationError.TemplateTopic_Must_Not_Be_Substring_Of_Other_TemplateTopic);
      }
    });
    return result;
  }

  static public ArrayList<ValidationError> isMappingValid(ArrayList<Mapping> mappings, Mapping mapping) {
    ArrayList<ValidationError> result = new ArrayList<ValidationError>();
    result.addAll(isSubstituionValid(mapping));
    result.addAll(isTopicNameValid(mapping.topic));
    result.addAll(isTopicNameValid(mapping.templateTopic));
    result.addAll(isTemplateTopicUnique(mappings, mapping));
    return result;
  }

  static public String normalizeTopic(String topic) {
    String nt = topic.trim().replace("\\/+$", "").replace("^\\/+", "");
    // do not use starting slashes, see as well https://www.hivemq.com/blog/mqtt-essentials-part-5-mqtt-topics-best-practices/
    // nt = "/".concat(nt);
    if (!nt.endsWith(TOPIC_WILDCARD_MULTI) && !nt.endsWith(TOPIC_WILDCARD_SINGLE)) {
      nt = nt.concat("/");
    }
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
