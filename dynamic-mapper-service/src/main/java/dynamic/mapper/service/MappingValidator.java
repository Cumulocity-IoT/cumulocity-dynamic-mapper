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

package dynamic.mapper.service;

import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;
import dynamic.mapper.model.*;
import dynamic.mapper.processor.model.MappingType;
import dynamic.mapper.processor.model.TransformationType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.springframework.stereotype.Component;

import jakarta.validation.constraints.NotNull;
import java.util.*;
/**
 * Validates mapping configurations against business rules
 * Consolidates all validation logic from the Mapping class
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MappingValidator {

    private static final String TOPIC_WILDCARD_MULTI = "#";
    private static final String TOPIC_WILDCARD_SINGLE = "+";

    private final MicroserviceSubscriptionsService subscriptionsService;
    private final MappingRepository mappingRepository;

    /**
     * Main validation method - validates a mapping against all business rules
     * 
     * @param tenant The tenant context
     * @param mapping The mapping to validate
     * @param excludeMappingId ID to exclude from duplicate checks (for updates)
     * @return List of validation errors (empty if valid)
     */
    public List<ValidationError> validate(String tenant, Mapping mapping, String excludeMappingId) {
        return subscriptionsService.callForTenant(tenant, () -> {
            List<ValidationError> errors = new ArrayList<>();
            
            // Get existing mappings for duplicate checks
            // List<Mapping> existingMappings = mappingRepository.findAll(tenant, Direction.UNSPECIFIED)
            //     .stream()
            //     .filter(m -> excludeMappingId == null || !m.getId().equals(excludeMappingId))
            //     .collect(Collectors.toList());

            // Run all validation checks
            errors.addAll(validateSubstitutions(mapping));
            errors.addAll(validateTopics(mapping));
            errors.addAll(validateJSONTemplates(mapping));
            //errors.addAll(validateFilterOutboundUniqueness(existingMappings, mapping));

            if (!errors.isEmpty()) {
                log.debug("{} - Validation failed for mapping {}: {}", 
                    tenant, mapping.getIdentifier(), errors);
            }

            return errors;
        });
    }

    /**
     * Validates that only one substitution defines the device identifier
     */
    public List<ValidationError> validateSubstitutions(Mapping mapping) {
        List<ValidationError> errors = new ArrayList<>();

        long deviceIdentifierCount = Arrays.stream(mapping.getSubstitutions())
            .filter(mapping::definesDeviceIdentifier)
            .count();

        // Skip device identifier validation for certain mapping types and conditions
        boolean skipDeviceIdentifierValidation = 
            mapping.getSnoopStatus() == SnoopStatus.ENABLED ||
            mapping.getSnoopStatus() == SnoopStatus.STARTED ||
            mapping.getMappingType() == MappingType.EXTENSION_SOURCE ||
            mapping.getMappingType() == MappingType.EXTENSION_SOURCE_TARGET ||
            mapping.getMappingType() == MappingType.PROTOBUF_INTERNAL ||
            mapping.getMappingType() == MappingType.CODE_BASED ||
            mapping.getTransformationType() == TransformationType.SMART_FUNCTION ||
            mapping.getTransformationType() == TransformationType.SUBSTITUTION_AS_CODE ||
            mapping.getDirection() == Direction.OUTBOUND;

        if (!skipDeviceIdentifierValidation) {
            if (deviceIdentifierCount > 1) {
                errors.add(ValidationError.Only_One_Substitution_Defining_Device_Identifier_Can_Be_Used);
            }
            if (deviceIdentifierCount < 1) {
                errors.add(ValidationError.One_Substitution_Defining_Device_Identifier_Must_Be_Used);
            }
        }

        return errors;
    }

    /**
     * Validates topics based on mapping direction
     */
    public List<ValidationError> validateTopics(Mapping mapping) {
        List<ValidationError> errors = new ArrayList<>();

        if (mapping.getDirection() == Direction.INBOUND) {
            // Validate inbound mapping topic
            errors.addAll(validateMappingTopic(mapping.getMappingTopic()));
            
            // Validate mapping topic and sample consistency
            if (mapping.getMappingTopicSample() != null && !mapping.getMappingTopicSample().isEmpty()) {
                errors.addAll(validateMappingTopicAndSampleConsistency(
                    mapping.getMappingTopic(), 
                    mapping.getMappingTopicSample()
                ));
            }
        } else {
            // Validate outbound publish topics
            if (mapping.getPublishTopic() != null && mapping.getPublishTopicSample() != null) {
                errors.addAll(validatePublishTopicAndSampleConsistency(
                    mapping.getPublishTopic(),
                    mapping.getPublishTopicSample()
                ));
            }
        }

        return errors;
    }

    /**
     * Validates mapping topic for wildcards
     */
    public List<ValidationError> validateMappingTopic(String topic) {
        List<ValidationError> errors = new ArrayList<>();

        if (topic == null || topic.isEmpty()) {
            return errors;
        }

        // Check multi-level wildcard rules
        int multiWildcardCount = countOccurrences(topic, TOPIC_WILDCARD_MULTI);
        if (multiWildcardCount > 1) {
            errors.add(ValidationError.Only_One_Multi_Level_Wildcard);
        }
        if (multiWildcardCount >= 1 && topic.indexOf(TOPIC_WILDCARD_MULTI) != topic.length() - 1) {
            errors.add(ValidationError.Multi_Level_Wildcard_Only_At_End);
        }

        return errors;
    }

    /**
     * Validates that mapping topic and sample have the same structure
     */
    public List<ValidationError> validateMappingTopicAndSampleConsistency(
            String mappingTopic, String mappingTopicSample) {
        
        List<ValidationError> errors = new ArrayList<>();

        String[] topicParts = Mapping.splitTopicIncludingSeparatorAsArray(mappingTopic);
        String[] sampleParts = Mapping.splitTopicIncludingSeparatorAsArray(mappingTopicSample);

        // Check same number of levels
        if (topicParts.length != sampleParts.length) {
            errors.add(ValidationError.MappingTopic_And_MappingTopicSample_Do_Not_Have_Same_Number_Of_Levels_In_Topic_Name);
            return errors;
        }

        // Check structure consistency
        for (int i = 0; i < topicParts.length; i++) {
            String topicPart = topicParts[i];
            String samplePart = sampleParts[i];

            // Both should be separators or both should not be
            if ("/".equals(topicPart) != "/".equals(samplePart)) {
                errors.add(ValidationError.MappingTopic_And_MappingTopicSample_Do_Not_Have_Same_Structure_In_Topic_Name);
                break;
            }

            // If not a separator or wildcard, values must match
            if (!"/".equals(topicPart) && !"+".equals(topicPart) && !topicPart.equals(samplePart)) {
                errors.add(ValidationError.MappingTopic_And_MappingTopicSample_Do_Not_Have_Same_Structure_In_Topic_Name);
                break;
            }
        }

        return errors;
    }

    /**
     * Validates that publish topic and sample have the same structure
     */
    public List<ValidationError> validatePublishTopicAndSampleConsistency(
            @NotNull String publishTopic, @NotNull String publishTopicSample) {
        
        List<ValidationError> errors = new ArrayList<>();

        String[] topicParts = Mapping.splitTopicIncludingSeparatorAsArray(publishTopic);
        String[] sampleParts = Mapping.splitTopicIncludingSeparatorAsArray(publishTopicSample);

        // Check same number of levels
        if (topicParts.length != sampleParts.length) {
            errors.add(ValidationError.PublishTopic_And_PublishTopicSample_Do_Not_Have_Same_Number_Of_Levels_In_Topic_Name);
            return errors;
        }

        // Check structure consistency
        for (int i = 0; i < topicParts.length; i++) {
            String topicPart = topicParts[i];
            String samplePart = sampleParts[i];

            // Both should be separators or both should not be
            if ("/".equals(topicPart) != "/".equals(samplePart)) {
                errors.add(ValidationError.PublishTopic_And_PublishTopicSample_Do_Not_Have_Same_Structure_In_Topic_Name);
                break;
            }

            // If not a separator or wildcard, values must match
            if (!"/".equals(topicPart) && !"+".equals(topicPart) && !"#".equals(topicPart) 
                    && !topicPart.equals(samplePart)) {
                errors.add(ValidationError.PublishTopic_And_PublishTopicSample_Do_Not_Have_Same_Structure_In_Topic_Name);
                break;
            }
        }

        return errors;
    }

    /**
     * Validates JSON templates
     */
    public List<ValidationError> validateJSONTemplates(Mapping mapping) {
        List<ValidationError> errors = new ArrayList<>();

        // Validate source template
        if (!isValidJSON(mapping.getSourceTemplate())) {
            errors.add(ValidationError.Source_Template_Must_Be_Valid_JSON);
        }

        // Validate target template (skip for certain mapping types)
        boolean skipTargetValidation = 
            mapping.getMappingType() == MappingType.EXTENSION_SOURCE ||
            mapping.getMappingType() == MappingType.PROTOBUF_INTERNAL;

        if (!skipTargetValidation && !isValidJSON(mapping.getTargetTemplate())) {
            errors.add(ValidationError.Target_Template_Must_Be_Valid_JSON);
        }

        return errors;
    }

    /**
     * Validates that filterOutbound is unique across outbound mappings
     */
    public List<ValidationError> validateFilterOutboundUniqueness(
            List<Mapping> existingMappings, Mapping mapping) {
        
        List<ValidationError> errors = new ArrayList<>();

        // Only validate for outbound mappings with filters
        if (mapping.getDirection() != Direction.OUTBOUND || 
            mapping.getFilterMapping() == null || 
            mapping.getFilterMapping().isEmpty()) {
            return errors;
        }

        String filterMapping = mapping.getFilterMapping();

        // Check if any other mapping has the same filter
        boolean hasDuplicate = existingMappings.stream()
            .filter(m -> m.getDirection() == Direction.OUTBOUND)
            .filter(m -> !m.getId().equals(mapping.getId()))
            .anyMatch(m -> filterMapping.equals(m.getFilterMapping()));

        if (hasDuplicate) {
            errors.add(ValidationError.FilterOutbound_Must_Be_Unique);
        }

        return errors;
    }

    /**
     * Validates if a topic contains wildcards
     */
    public boolean isWildcardTopic(String topic) {
        if (topic == null) {
            return false;
        }
        return topic.contains(TOPIC_WILDCARD_MULTI) || topic.contains(TOPIC_WILDCARD_SINGLE);
    }

    /**
     * Checks if a string is valid JSON
     */
    private Boolean isValidJSON(String jsonString) {
        if (jsonString == null || jsonString.isEmpty()) {
            return false;
        }

        try {
            new JSONTokener(jsonString).nextValue();
            return true;
        } catch (JSONException e) {
            log.debug("Invalid JSON: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Checks if target template is valid JSON object (not just any JSON value)
     */
    private Boolean isValidJSONObject(String jsonString) {
        if (jsonString == null || jsonString.isEmpty()) {
            return false;
        }

        try {
            new JSONObject(jsonString);
            return true;
        } catch (JSONException e) {
            log.debug("Invalid JSON object: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Counts occurrences of a substring in a string
     */
    private int countOccurrences(String text, String substring) {
        if (text == null || substring == null || substring.isEmpty()) {
            return 0;
        }
        return text.length() - text.replace(substring, "").length();
    }

    /**
     * Quick validation method that just checks if mapping has basic required fields
     */
    public boolean hasRequiredFields(Mapping mapping) {
        return mapping.getIdentifier() != null &&
               mapping.getName() != null &&
               mapping.getTargetAPI() != null &&
               mapping.getDirection() != null &&
               mapping.getSourceTemplate() != null &&
               mapping.getTargetTemplate() != null;
    }

    /**
     * Validates only the substitutions (useful for partial validation)
     */
    public boolean hasValidSubstitutions(Mapping mapping) {
        return validateSubstitutions(mapping).isEmpty();
    }

    /**
     * Validates only the topics (useful for partial validation)
     */
    public boolean hasValidTopics(Mapping mapping) {
        return validateTopics(mapping).isEmpty();
    }

    /**
     * Validates only the JSON templates (useful for partial validation)
     */
    public boolean hasValidJSONTemplates(Mapping mapping) {
        return validateJSONTemplates(mapping).isEmpty();
    }
}
