/*
 * Copyright (c) 2022-2025 Cumulocity GmbH.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *  @authors Christof Strack, Stefan Witschel
 *
 */

package dynamic.mapping.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import dynamic.mapping.processor.model.MappingType;

import jakarta.validation.constraints.NotNull;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

@Getter
@Setter
@NoArgsConstructor
@ToString(exclude = { "sourceTemplate", "targetTemplate", "snoopedTemplates" })
public class Mapping implements Serializable {

    public static final String IDENTITY = "_IDENTITY_";
    public static final String TOKEN_TOPIC_LEVEL = "_TOPIC_LEVEL_";
    public static final String TOKEN_CONTEXT_DATA = "_CONTEXT_DATA_";
    public static final String CONTEXT_DATA_KEY_NAME = "key";
    public static final String TIME = "time";

    public static int SNOOP_TEMPLATES_MAX = 10;
    public static final String SPLIT_TOPIC_REGEXP = "((?<=/)|(?=/))";
    public static Mapping UNSPECIFIED_MAPPING;

    static final String REGEXP_REMOVE_TRAILING_SLASHES = "#\\/$";
    static final String REGEXP_REDUCE_LEADING_TRAILING_SLASHES = "(\\/{2,}$)|(^\\/{2,})";
    static String TOPIC_WILDCARD_MULTI = "#";
    static String TOPIC_WILDCARD_SINGLE = "+";

    static {
        UNSPECIFIED_MAPPING = new Mapping();
        UNSPECIFIED_MAPPING.setId(MappingStatus.IDENT_UNSPECIFIED_MAPPING);
        UNSPECIFIED_MAPPING.setIdentifier(MappingStatus.IDENT_UNSPECIFIED_MAPPING);
    }

    @NotNull
    public String id;

    @NotNull
    public String identifier;

    @NotNull
    public String name;

    public String publishTopic;

    public String publishTopicSample;

    public String mappingTopic;

    public String mappingTopicSample;

    @NotNull
    public API targetAPI;

    @NotNull
    @JsonSetter(nulls = Nulls.SKIP)
    public Direction direction;

    @NotNull
    public String sourceTemplate;

    @NotNull
    public String targetTemplate;

    @NotNull
    public MappingType mappingType;

    @NotNull
    public MappingSubstitution[] substitutions;

    @NotNull
    public Boolean active;

    @NotNull
    public Boolean debug;

    @NotNull
    public Boolean tested;

    @NotNull
    public Boolean supportsMessageContext;

    @NotNull
    public Boolean createNonExistingDevice;

    @NotNull
    public Boolean updateExistingDevice;

    @JsonSetter(nulls = Nulls.SKIP)
    public Boolean autoAckOperation;

    @NotNull
    public Boolean useExternalId = false;;

    public String externalIdType;

    @NotNull
    public SnoopStatus snoopStatus;

    @NotNull
    public ArrayList<String> snoopedTemplates;

    @JsonSetter(nulls = Nulls.SKIP)
    public ExtensionEntry extension;

    // TODO filterMapping has to be removed and for ountbound mappings as well
    // JSONata expressions
    // this has to be changed in MappingComponent.deleteFromMappingCache &
    // MappingComponent.rebuildMappingOutboundCache

    @JsonSetter(nulls = Nulls.SKIP)
    public String filterMapping;

    @NotNull
    public QOS qos;

    // code for substitutions encoded in base64
    // @NotNull
    public String code;

    @NotNull
    public long lastUpdate;
    public static final String EXTRACT_FROM_SOURCE = "extractFromSource";

    @Override
    public boolean equals(Object m) {
        return (m instanceof Mapping) && id == ((Mapping) m).id;
    }

    @JsonIgnore
    public String getGenericDeviceIdentifier() {
        if (useExternalId && !("").equals(externalIdType)) {
            return (Mapping.IDENTITY + ".externalId");
        } else {
            return (Mapping.IDENTITY + ".c8ySourceId");
        }
    }

    @JsonIgnore
    public Boolean definesDeviceIdentifier(
            MappingSubstitution sub) {
        if (Direction.INBOUND.equals(direction)) {
            if (useExternalId && !("").equals(externalIdType)) {
                return (Mapping.IDENTITY + ".externalId").equals(sub.pathTarget);
            } else {
                return (Mapping.IDENTITY + ".c8ySourceId").equals(sub.pathTarget);
            }
        } else {
            if (useExternalId && !("").equals(externalIdType)) {
                return (Mapping.IDENTITY + ".externalId").equals(sub.pathSource);
            } else {
                return (Mapping.IDENTITY + ".c8ySourceId").equals(sub.pathSource);
            }
        }
    }

    @JsonIgnore
    public void addSnoopedTemplate(String payloadMessage) {
        snoopedTemplates.add(payloadMessage);
        if (snoopedTemplates.size() > SNOOP_TEMPLATES_MAX) {
            // remove oldest payload
            snoopedTemplates.remove(0);
        } else {
            snoopStatus = SnoopStatus.STARTED;
        }
    }

    @JsonIgnore
    public void sortSubstitutions() {
        MappingSubstitution[] sortedSubstitutions = Arrays.stream(substitutions).sorted(
                (s1, s2) -> -(Boolean.valueOf(definesDeviceIdentifier(s1))
                        .compareTo(
                                Boolean.valueOf(definesDeviceIdentifier(s2)))))
                .toArray(size -> new MappingSubstitution[size]);
        substitutions = sortedSubstitutions;
    }

    /*
     * "_IDENTITY_.externalId" => source.id
     */
    @JsonIgnore
    public String transformGenericPath2C8YPath(String originalPath) {
        // "_IDENTITY_.externalId" => source.id
        if (getGenericDeviceIdentifier().equals(originalPath)) {
            return targetAPI.identifier;
        } else {
            return originalPath;
        }
    }

    /*
     * source.id => "_IDENTITY_.externalId"
     */
    @JsonIgnore
    public String transformC8YPath2GenericPath(String originalPath) {
        if (targetAPI.identifier.equals(originalPath)) {
            return getGenericDeviceIdentifier();
        } else {
            return originalPath;
        }
    }

    @JsonIgnore
    public List<String> getPathTargetForDeviceIdentifiers() {
        List<String> pss = Arrays.stream(substitutions)
                .filter(sub -> definesDeviceIdentifier(sub))
                .map(sub -> sub.pathTarget)
                .toList();
        return pss;
    }

    public static String[] splitTopicIncludingSeparatorAsArray(String topic) {
        topic = topic.trim();
        StringBuilder result = new StringBuilder();
        boolean wasSlash = false;
        
        for (char c : topic.toCharArray()) {
            if (c == '/') {
                if (!wasSlash) {
                    result.append(c);
                }
                wasSlash = true;
            } else {
                result.append(c);
                wasSlash = false;
            }
        }
        return result.toString().split(SPLIT_TOPIC_REGEXP);
    }

    public static List<String> splitTopicIncludingSeparatorAsList(String topic) {
        return new ArrayList<String>(
                Arrays.asList(Mapping.splitTopicIncludingSeparatorAsArray(topic)));
    }

    public static String[] splitTopicExcludingSeparatorAsArray(String topic, boolean cutOffLeadingSlash) {
        String topix = topic.trim();
        
        if (cutOffLeadingSlash) {
            // Original behavior: remove both leading and trailing slashes
            topix = topix.replaceAll("(\\/{1,}$)|(^\\/{1,})", "");
            return topix.split("\\/");
        } else {
            // New behavior: keep leading slash, remove only trailing slashes
            topix = topix.replaceAll("\\/{1,}$", "");
            if (topix.startsWith("//")) {
                topix = "/" + topix.replaceAll("^/+", "");
            }
            
            if (topix.startsWith("/")) {
                String[] parts = topix.substring(1).split("\\/");
                String[] result = new String[parts.length + 1];
                result[0] = "/";
                System.arraycopy(parts, 0, result, 1, parts.length);
                return result;
            }
            
            return topix.split("\\/");
        }
    }

    public static List<String> splitTopicExcludingSeparatorAsList(String topic, boolean cutOffLeadingSlash) {
        return new ArrayList<String>(
                Arrays.asList(Mapping.splitTopicExcludingSeparatorAsArray(topic, cutOffLeadingSlash)));
    }

    /*
     * only one substitution can be marked with definesIdentifier == true
     */
    static public ArrayList<ValidationError> isSubstitutionValid(Mapping mapping) {
        ArrayList<ValidationError> result = new ArrayList<ValidationError>();
        long count = Arrays.asList(mapping.substitutions).stream()
                .filter(sub -> mapping.definesDeviceIdentifier(sub))
                .count();

        if (mapping.snoopStatus != SnoopStatus.ENABLED && mapping.snoopStatus != SnoopStatus.STARTED
                && !mapping.mappingType.equals(MappingType.EXTENSION_SOURCE)
                && !mapping.mappingType.equals(MappingType.EXTENSION_SOURCE_TARGET)
                && !mapping.mappingType.equals(MappingType.PROTOBUF_INTERNAL)
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

    static public ArrayList<ValidationError> isMappingTopicValid(String topic) {
        ArrayList<ValidationError> result = new ArrayList<ValidationError>();
        int count = topic.length() - topic.replace(TOPIC_WILDCARD_SINGLE, "").length();
        // disable this test: Why is it still needed?
        // if (count > 1) {
        // result.add(ValidationError.Only_One_Single_Level_Wildcard);
        // }

        count = topic.length() - topic.replace(TOPIC_WILDCARD_MULTI, "").length();
        if (count > 1) {
            result.add(ValidationError.Only_One_Multi_Level_Wildcard);
        }
        if (count >= 1 && topic.indexOf(TOPIC_WILDCARD_MULTI) != topic.length() - 1) {
            result.add(ValidationError.Multi_Level_Wildcard_Only_At_End);
        }
        return result;
    }

    static public Boolean isWildcardTopic(String topic) {
        var result = topic.contains(TOPIC_WILDCARD_MULTI) || topic.contains(TOPIC_WILDCARD_SINGLE);
        return result;
    }

    static public List<ValidationError> isFilterOutboundUnique(List<Mapping> mappings, Mapping mapping) {
        ArrayList<ValidationError> result = new ArrayList<ValidationError>();
        var filterMapping = mapping.filterMapping;
        mappings.forEach(m -> {
            if ((filterMapping.equals(m.filterMapping))
                    && (mapping.id != m.id)) {
                result.add(ValidationError.FilterOutbound_Must_Be_Unique);
            }
        });
        return result;
    }

    static public List<ValidationError> isMappingValid(List<Mapping> mappings, Mapping mapping) {
        ArrayList<ValidationError> result = new ArrayList<ValidationError>();
        result.addAll(isSubstitutionValid(mapping));
        if (mapping.direction.equals(Direction.INBOUND)) {
            result.addAll(isMappingTopicValid(mapping.mappingTopic));
        } else {
            // test if we can attach multiple outbound mappings to the same filterMapping
            result.addAll(
                    Mapping.isPublishTopicTemplateAndPublishTopicSampleValid(mapping.publishTopic,
                            mapping.publishTopicSample));
        }

        result.addAll(areJSONTemplatesValid(mapping));
        // result.addAll(isMappingTopicUnique(mappings, mapping));
        return result;
    }

    static Collection<? extends ValidationError> isPublishTopicTemplateAndPublishTopicSampleValid(
            @NotNull String publishTopic, @NotNull String publishTopicSample) {
        ArrayList<ValidationError> result = new ArrayList<ValidationError>();
        String[] splitPT = splitTopicIncludingSeparatorAsArray(publishTopic);
        String[] splitTTS = splitTopicIncludingSeparatorAsArray(publishTopicSample);
        if (splitPT.length != splitTTS.length) {
            result.add(
                    ValidationError.PublishTopic_And_PublishTopicSample_Do_Not_Have_Same_Number_Of_Levels_In_Topic_Name);
        } else {
            for (int i = 0; i < splitPT.length; i++) {
                if (("/").equals(splitPT[i]) && !("/").equals(splitTTS[i])) {
                    result.add(
                            ValidationError.PublishTopic_And_PublishTopicSample_Do_Not_Have_Same_Structure_In_Topic_Name);
                    break;
                }
                if (("/").equals(splitTTS[i]) && !("/").equals(splitPT[i])) {
                    result.add(
                            ValidationError.PublishTopic_And_PublishTopicSample_Do_Not_Have_Same_Structure_In_Topic_Name);
                    break;
                }
                if (!("/").equals(splitPT[i]) && !("+").equals(splitPT[i]) && !("#").equals(splitPT[i])) {
                    if (!splitPT[i].equals(splitTTS[i])) {
                        result.add(
                                ValidationError.PublishTopic_And_PublishTopicSample_Do_Not_Have_Same_Structure_In_Topic_Name);
                        break;
                    }
                }
            }
        }
        return result;
    }

    /*
     * test if mapping.mappingTopic and mapping.mappingTopicSample have the same
     * structure and same number of levels
     */
    public static List<ValidationError> isMappingTopicAndMappingTopicSampleValid(String mappingTopic,
            String mappingTopicSample) {
        ArrayList<ValidationError> result = new ArrayList<ValidationError>();
        String[] splitTT = Mapping.splitTopicIncludingSeparatorAsArray(mappingTopic);
        String[] splitTTS = Mapping.splitTopicIncludingSeparatorAsArray(mappingTopicSample);
        if (splitTT.length != splitTTS.length) {
            result.add(
                    ValidationError.MappingTopic_And_MappingTopicSample_Do_Not_Have_Same_Number_Of_Levels_In_Topic_Name);
        } else {
            for (int i = 0; i < splitTT.length; i++) {
                if (("/").equals(splitTT[i]) && !("/").equals(splitTTS[i])) {
                    result.add(
                            ValidationError.MappingTopic_And_MappingTopicSample_Do_Not_Have_Same_Structure_In_Topic_Name);
                    break;
                }
                if (("/").equals(splitTTS[i]) && !("/").equals(splitTT[i])) {
                    result.add(
                            ValidationError.MappingTopic_And_MappingTopicSample_Do_Not_Have_Same_Structure_In_Topic_Name);
                    break;
                }
                if (!("/").equals(splitTT[i]) && !("+").equals(splitTT[i])) {
                    if (!splitTT[i].equals(splitTTS[i])) {
                        result.add(
                                ValidationError.MappingTopic_And_MappingTopicSample_Do_Not_Have_Same_Structure_In_Topic_Name);
                        break;
                    }
                }
            }
        }
        return result;
    }

    static Collection<ValidationError> areJSONTemplatesValid(Mapping mapping) {
        ArrayList<ValidationError> result = new ArrayList<ValidationError>();
        try {
            new JSONTokener(mapping.sourceTemplate).nextValue();
        } catch (JSONException e) {
            result.add(ValidationError.Source_Template_Must_Be_Valid_JSON);
        }

        if (!mapping.mappingType.equals(MappingType.EXTENSION_SOURCE)
                && !mapping.mappingType.equals(MappingType.PROTOBUF_INTERNAL)) {
            try {
                new JSONObject(mapping.targetTemplate);
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

    static public List<MappingSubstitution> getDeviceIdentifiers(Mapping mapping) {
        List<MappingSubstitution> mp = Arrays.stream(mapping.substitutions)
                .filter(sub -> mapping.definesDeviceIdentifier(sub))
                .toList();
        return mp;
    }

}
