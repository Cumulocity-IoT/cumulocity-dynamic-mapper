/*
 * Copyright (c) 2022 Software AG, Darmstadt, Germany and/or Software AG USA Inc., Reston, VA, USA,
 * and/or its subsidiaries and/or its affiliates and/or their licensors.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * @authors Christof Strack, Stefan Witschel
 */

package mqtt.mapping.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import mqtt.mapping.processor.model.MappingType;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import javax.validation.constraints.NotNull;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MappingRepresentation implements Serializable {

  public static final String MAPPING_TYPE = "d11r_mapping";
  public static final String MAPPING_FRAGMENT = "d11r_mapping";
  public static final String MAPPING_GENERATED_TEST_DEVICE = "d11r_device_generatedType";
  static final String REGEXP_REMOVE_TRAILING_SLASHES = "#\\/$";
  static final String REGEXP_REDUCE_LEADING_TRAILING_SLASHES = "(\\/{2,}$)|(^\\/{2,})";
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

  @JsonProperty(value = MAPPING_FRAGMENT)
  private Mapping c8yMQTTMapping;

  static public boolean isWildcardTopic(String topic) {
    var result = topic.contains(TOPIC_WILDCARD_MULTI) || topic.contains(TOPIC_WILDCARD_SINGLE);
    return result;
  }

  /*
   * only one substitution can be marked with definesIdentifier == true
   */
  static public ArrayList<ValidationError> isSubstituionValid(Mapping mapping) {
    ArrayList<ValidationError> result = new ArrayList<ValidationError>();
    long count = Arrays.asList(mapping.substitutions).stream()
        .filter(sub -> sub.definesDeviceIdentifier(mapping.targetAPI, mapping.direction)).count();

    if (mapping.snoopStatus != SnoopStatus.ENABLED && mapping.snoopStatus != SnoopStatus.STARTED
        && !mapping.mappingType.equals(MappingType.PROCESSOR_EXTENSION)
        && !mapping.mappingType.equals(MappingType.PROTOBUF_STATIC)
        && !mapping.direction.equals(Direction.OUTBOUND)) {
      if (count > 1) {
        result.add(ValidationError.Only_One_Substitution_Defining_Device_Identifier_Can_Be_Used);
      }
      if (count < 1) {
        result.add(ValidationError.One_Substitution_Defining_Device_Identifier_Must_Be_Used);
      }

    }
    return result;
  }

  static public ArrayList<ValidationError> isTemplateTopicValid(String topic) {
    // templateTopic can contain any number of "+" TOPIC_WILDCARD_SINGLE but no "#"
    // TOPIC_WILDCARD_MULTI
    ArrayList<ValidationError> result = new ArrayList<ValidationError>();
    int count = topic.length() - topic.replace(TOPIC_WILDCARD_MULTI, "").length();
    if (count >= 1) {
      result.add(ValidationError.No_Multi_Level_Wildcard_Allowed_In_TemplateTopic);
    }
    return result;
  }

  static public ArrayList<ValidationError> isSubscriptionTopicValid(String topic) {
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

  static public List<ValidationError> isTemplateTopicSubscriptionTopicValid(Mapping mapping) {
    List<ValidationError> result = new ArrayList<ValidationError>();

    // does the template topic is covered by the subscriptionTopic
    BiFunction<String, String, Boolean> topicMatcher = (st,
        tt) -> (Pattern.matches(String.join("[^\\/]+", st.replace("/", "\\/").split("\\+")).replace("#", ".*"), tt));
    boolean error = (!topicMatcher.apply(mapping.subscriptionTopic, mapping.templateTopic));
    if (error) {
      result.add(ValidationError.TemplateTopic_Must_Match_The_SubscriptionTopic);
    }
    return result;
  }

  static public List<ValidationError> isTemplateTopicUnique(List<Mapping> mappings, Mapping mapping) {
    ArrayList<ValidationError> result = new ArrayList<ValidationError>();
    var templateTopic = mapping.templateTopic;
    mappings.forEach(m -> {
      if ((templateTopic.startsWith(m.templateTopic) || m.templateTopic.startsWith(templateTopic))
          && (mapping.id != m.id)) {
        result.add(ValidationError.TemplateTopic_Must_Not_Be_Substring_Of_Other_TemplateTopic);
      }
    });
    return result;
  }

  static public List<ValidationError> isFilterOutboundUnique(List<Mapping> mappings, Mapping mapping) {
    ArrayList<ValidationError> result = new ArrayList<ValidationError>();
    var filterOutbound = mapping.filterOutbound;
    mappings.forEach(m -> {
      if ((filterOutbound.equals(m.filterOutbound))
          && (mapping.id != m.id)) {
        result.add(ValidationError.FilterOutbound_Must_Be_Unique);
      }
    });
    return result;
  }

  static public List<ValidationError> isMappingValid(List<Mapping> mappings, Mapping mapping) {
    ArrayList<ValidationError> result = new ArrayList<ValidationError>();
    result.addAll(isSubstituionValid(mapping));
    result.addAll(isTemplateTopicValid(mapping.templateTopic));
    if (mapping.direction.equals(Direction.INBOUND)) {
      result.addAll(isSubscriptionTopicValid(mapping.subscriptionTopic));
      result.addAll(isTemplateTopicTemplateAndTopicSampleValid(mapping.templateTopic, mapping.templateTopicSample));
    } else {
      // test if we can attach multiple outbound mappings to the same filterOutbound
      // result.addAll(isFilterOutboundUnique(mappings,mapping));
      result.addAll(isPublishTopicTemplateAndTopicSampleValid(mapping.publishTopic, mapping.templateTopicSample));
    }

    result.addAll(areJSONTemplatesValid(mapping));
    // result.addAll(isTemplateTopicUnique(mappings, mapping));
    return result;
  }

  private static Collection<? extends ValidationError> isPublishTopicTemplateAndTopicSampleValid(
      @NotNull String publishTopic, @NotNull String templateTopicSample) {
    ArrayList<ValidationError> result = new ArrayList<ValidationError>();
    String[] splitPT = Mapping.splitTopicIncludingSeparatorAsArray(publishTopic);
    String[] splitTTS = Mapping.splitTopicIncludingSeparatorAsArray(templateTopicSample);
    if (splitPT.length != splitTTS.length) {
      result.add(ValidationError.PublishTopic_And_TemplateTopicSample_Do_Not_Have_Same_Number_Of_Levels_In_Topic_Name);
    } else {
      for (int i = 0; i < splitPT.length; i++) {
        if (("/").equals(splitPT[i]) && !("/").equals(splitTTS[i])) {
          result.add(ValidationError.PublishTopic_And_TemplateTopicSample_Do_Not_Have_Same_Structure_In_Topic_Name);
          break;
        }
        if (("/").equals(splitTTS[i]) && !("/").equals(splitPT[i])) {
          result.add(ValidationError.PublishTopic_And_TemplateTopicSample_Do_Not_Have_Same_Structure_In_Topic_Name);
          break;
        }
        if (!("/").equals(splitPT[i]) && !("+").equals(splitPT[i]) && !("#").equals(splitPT[i])) {
          if (!splitPT[i].equals(splitTTS[i])) {
            result.add(ValidationError.PublishTopic_And_TemplateTopicSample_Do_Not_Have_Same_Structure_In_Topic_Name);
            break;
          }
        }
      }
    }
    return result;
  }

  /*
   * test if mapping.templateTopic and mapping.templateTopicSample have the same
   * structure and same number of levels
   */
  public static List<ValidationError> isTemplateTopicTemplateAndTopicSampleValid(String templateTopic,
      String templateTopicSample) {
    ArrayList<ValidationError> result = new ArrayList<ValidationError>();
    String[] splitTT = Mapping.splitTopicIncludingSeparatorAsArray(templateTopic);
    String[] splitTTS = Mapping.splitTopicIncludingSeparatorAsArray(templateTopicSample);
    if (splitTT.length != splitTTS.length) {
      result.add(ValidationError.TemplateTopic_And_TemplateTopicSample_Do_Not_Have_Same_Number_Of_Levels_In_Topic_Name);
    } else {
      for (int i = 0; i < splitTT.length; i++) {
        if (("/").equals(splitTT[i]) && !("/").equals(splitTTS[i])) {
          result.add(ValidationError.TemplateTopic_And_TemplateTopicSample_Do_Not_Have_Same_Structure_In_Topic_Name);
          break;
        }
        if (("/").equals(splitTTS[i]) && !("/").equals(splitTT[i])) {
          result.add(ValidationError.TemplateTopic_And_TemplateTopicSample_Do_Not_Have_Same_Structure_In_Topic_Name);
          break;
        }
        if (!("/").equals(splitTT[i]) && !("+").equals(splitTT[i])) {
          if (!splitTT[i].equals(splitTTS[i])) {
            result.add(ValidationError.TemplateTopic_And_TemplateTopicSample_Do_Not_Have_Same_Structure_In_Topic_Name);
            break;
          }
        }
      }
    }
    return result;
  }

  private static Collection<ValidationError> areJSONTemplatesValid(Mapping mapping) {
    ArrayList<ValidationError> result = new ArrayList<ValidationError>();
    try {
      new JSONTokener(mapping.source).nextValue();
    } catch (JSONException e) {
      result.add(ValidationError.Source_Template_Must_Be_Valid_JSON);
    }

    if (!mapping.mappingType.equals(MappingType.PROCESSOR_EXTENSION)
        && !mapping.mappingType.equals(MappingType.PROTOBUF_STATIC)) {
      try {
        new JSONObject(mapping.target);
      } catch (JSONException e) {
        result.add(ValidationError.Target_Template_Must_Be_Valid_JSON);
      }
    }

    return result;
  }

  static public String normalizeTopic(String topic) {
    if (topic == null)
      topic = "";
    // reduce multiple leading or trailing "/" to just one "/"
    String nt = topic.trim().replaceAll(REGEXP_REDUCE_LEADING_TRAILING_SLASHES, "/");
    // do not use starting slashes, see as well
    // https://www.hivemq.com/blog/mqtt-essentials-part-5-mqtt-topics-best-practices/
    nt = nt.replaceAll(REGEXP_REMOVE_TRAILING_SLASHES, "#");
    return nt;
  }

  static public MappingSubstitution findDeviceIdentifier(Mapping mapping) {
    Object[] mp = Arrays.stream(mapping.substitutions)
        .filter(sub -> sub.definesDeviceIdentifier(mapping.targetAPI, mapping.direction)).toArray();
    if (mp.length > 0) {
      return (MappingSubstitution) mp[0];
    } else {
      return null;
    }
  }
}
