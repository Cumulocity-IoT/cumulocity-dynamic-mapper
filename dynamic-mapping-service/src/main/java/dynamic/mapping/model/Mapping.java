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

package dynamic.mapping.model;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import dynamic.mapping.processor.model.MappingType;

import javax.validation.constraints.NotNull;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@ToString(exclude = { "source", "target", "snoopedTemplates" })
public class Mapping implements Serializable {

    public static final String TOKEN_TOPIC_LEVEL = "_TOPIC_LEVEL_";
    public static final String TOKEN_CONTEXT_DATA = "_CONTEXT_DATA_";
    public static final String CONTEXT_DATA_KEY_NAME = "key";

    public static final String TIME = "time";
    public static int SNOOP_TEMPLATES_MAX = 10;
    public static final String SPLIT_TOPIC_REGEXP = "((?<=/)|(?=/))";
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
    public String publishTopicSample;

    @NotNull
    public String mappingTopic;

    @NotNull
    public String mappingTopicSample;

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
    public String filterOutbound;

    // TODO filterOutbound has to be removed and ofr ountbound mappings as well JSONata expressions
    // this has to be changed in MappingComponent.deleteFromMappingCache & MappingComponent.rebuildMappingOutboundCache
    @NotNull
    @JsonSetter(nulls = Nulls.SKIP)
    public String filterMapping;

    @NotNull
    @JsonSetter(nulls = Nulls.SKIP)
    public Boolean autoAckOperation;

    @NotNull
    public boolean debug;

    @NotNull
    public boolean supportsMessageContext;

    @NotNull
    public long lastUpdate;

    @Override
    public boolean equals(Object m) {
        return (m instanceof Mapping) && id == ((Mapping) m).id;
    }

    public void addSnoopedTemplate(String payloadMessage) {
        snoopedTemplates.add(payloadMessage);
        if (snoopedTemplates.size() > SNOOP_TEMPLATES_MAX) {
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
