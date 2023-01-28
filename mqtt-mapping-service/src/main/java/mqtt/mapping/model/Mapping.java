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

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.validation.constraints.NotNull;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import mqtt.mapping.processor.model.MappingType;

@Getter
@Setter
@NoArgsConstructor
@ToString(exclude = { "source", "target", "snoopedTemplates" })
public class Mapping implements Serializable {

  public static int SNOOP_TEMPLATES_MAX = 5;
  public static String SPLIT_TOPIC_REGEXP = "((?<=/)|(?=/))";
  public static Mapping UNSPECIFIED_MAPPING;

  static {
    UNSPECIFIED_MAPPING = new Mapping();
    UNSPECIFIED_MAPPING.setId(MappingStatus.IDENT_UNSPECIFIED_MAPPING);
    UNSPECIFIED_MAPPING.setIdent(MappingStatus.IDENT_UNSPECIFIED_MAPPING);
  }

  @NotNull
  public String name;

  @NotNull
  public String id;

  @NotNull
  public String ident;

  @NotNull
  public String subscriptionTopic;

  @NotNull
  public String publishTopic;

  @NotNull
  public String templateTopic;

  @NotNull
  public String templateTopicSample;

  @NotNull
  public API targetAPI;

  @NotNull
  public String source;

  @NotNull
  public String target;

  @NotNull
  public boolean active;

  @NotNull
  public boolean tested;

  @NotNull
  public QOS qos;

  @NotNull
  public MappingSubstitution[] substitutions;

  @NotNull
  public boolean mapDeviceIdentifier;

  @NotNull
  public boolean createNonExistingDevice;

  @NotNull
  public boolean updateExistingDevice;

  @NotNull
  public String externalIdType;

  @NotNull
  public SnoopStatus snoopStatus;

  @NotNull
  public ArrayList<String> snoopedTemplates;

  @NotNull
  public MappingType mappingType;

  @NotNull
  @JsonSetter(nulls = Nulls.SKIP)
  public ExtensionEntry extension;

  @NotNull
  @JsonSetter(nulls = Nulls.SKIP)
  public Direction direction;

  @NotNull
  @JsonSetter(nulls = Nulls.SKIP)
  public String filterOutgoing;

  @NotNull
  public long lastUpdate;

  @Override
  public boolean equals(Object m) {
    return (m instanceof Mapping) && id == ((Mapping) m).id;
  }

  public void addSnoopedTemplate(String payloadMessage) {
    snoopedTemplates.add(payloadMessage);
    if (snoopedTemplates.size() >= SNOOP_TEMPLATES_MAX) {
      // remove oldest payload
      snoopedTemplates.remove(0);
    } else {
      snoopStatus = SnoopStatus.STARTED;
    }
  }

  public void sortSubstitutions() {
    MappingSubstitution[] sortedSubstitutions = Arrays.stream(substitutions).sorted(
        (s1, s2) -> -(Boolean.valueOf(s1.definesDeviceIdentifier(targetAPI, direction))
            .compareTo(Boolean.valueOf(s2.definesDeviceIdentifier(targetAPI, direction)))))
        .toArray(size -> new MappingSubstitution[size]);
    substitutions = sortedSubstitutions;
  }

  public static String[] splitTopicIncludingSeparatorAsArray(String topic) {
    topic = topic.trim().replaceAll("(\\/{1,}$)|(^\\/{1,})", "/");
    return topic.split(SPLIT_TOPIC_REGEXP);
  }

  public static List<String> splitTopicIncludingSeparatorAsList(String topic) {
    return new ArrayList<String>(
        Arrays.asList(Mapping.splitTopicIncludingSeparatorAsArray(topic)));
  }

  public static String[] splitTopicExcludingSeparatorAsArray(String topic) {
    topic = topic.trim().replaceAll("(\\/{1,}$)|(^\\/{1,})", "");
    return topic.split("\\/");
  }

  public static List<String> splitTopicExcludingSeparatorAsList(String topic) {
    return new ArrayList<String>(
        Arrays.asList(Mapping.splitTopicExcludingSeparatorAsArray(topic)));
  }
}
