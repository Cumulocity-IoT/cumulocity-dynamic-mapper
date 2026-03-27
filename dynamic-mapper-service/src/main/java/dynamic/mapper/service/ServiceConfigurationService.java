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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Service;

import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;
import com.cumulocity.model.option.OptionPK;
import com.cumulocity.rest.representation.tenant.OptionRepresentation;
import com.cumulocity.sdk.client.SDKException;
import com.cumulocity.sdk.client.option.TenantOptionApi;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import dynamic.mapper.configuration.CodeTemplate;
import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.configuration.TemplateType;
import dynamic.mapper.util.Utils;
import dynamic.mapper.model.Direction;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class ServiceConfigurationService {
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int UUID_LENGTH = 8;

    @Value("classpath:mappings/mappings-INBOUND.json")
    private Resource sampleMappingsInbound_01;

    public String getSampleMappingsInbound_01() {
        return validateAndConvert(sampleMappingsInbound_01);
    }

    @Value("classpath:mappings/mappings-OUTBOUND.json")
    private Resource sampleMappingsOutbound_01;

    public String getSampleMappingsOutbound_01() {
        return validateAndConvert(sampleMappingsOutbound_01);
    }

    private static final String OPTION_KEY_SERVICE_CONFIGURATION = "service.configuration";

    private final TenantOptionApi tenantOptionApi;

    @Autowired
    private MicroserviceSubscriptionsService subscriptionsService;

    private ObjectMapper objectMapper;

    @Autowired
    public void setObjectMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ServiceConfigurationService(TenantOptionApi tenantOptionApi) {
        this.tenantOptionApi = tenantOptionApi;
    }

    public void initCodeTemplates(ServiceConfiguration configuration, Boolean overrideSystem) {
        Map<String, CodeTemplate> codeTemplates;
        if (overrideSystem) {
            codeTemplates = configuration.getCodeTemplates();
            codeTemplates.entrySet().removeIf(entry ->
                    entry.getValue().internal && entry.getValue().templateType != TemplateType.SHARED);
        } else {
            codeTemplates = new HashMap<>();
        }

        Map<TemplateType, Boolean> defaultTemplateRegistered = new EnumMap<>(TemplateType.class);
        for (TemplateType type : TemplateType.values()) {
            defaultTemplateRegistered.put(type, false);
        }

        Resource[] resources;
        try {
            ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            resources = resolver.getResources("classpath:templates/template*.js");
        } catch (IOException e) {
            log.error("Failed to load template resources", e);
            configuration.setCodeTemplates(codeTemplates);
            return;
        }

        for (Resource resource : resources) {
            try {
                loadTemplate(resource, codeTemplates, defaultTemplateRegistered);
            } catch (Exception e) {
                log.error("Failed to process template file: {}", resource.getFilename(), e);
            }
        }

        configuration.setCodeTemplates(codeTemplates);
    }

    private void loadTemplate(Resource resource, Map<String, CodeTemplate> codeTemplates,
            Map<TemplateType, Boolean> defaultTemplateRegistered) throws IOException {
        String fileName = resource.getFilename();
        if (fileName == null) {
            return;
        }

        String content;
        try (InputStream is = resource.getInputStream()) {
            content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }

        String name = extractAnnotation(content, "@name");
        String description = extractAnnotation(content, "@description");
        boolean internal = Boolean.parseBoolean(extractAnnotation(content, "@internal"));
        String templateTypeStr = extractAnnotation(content, "@templateType");

        TemplateType templateType;
        try {
            templateType = TemplateType.valueOf(templateTypeStr);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid template type in file {}: {}", fileName, templateTypeStr);
            return;
        }

        // MIGRATION: derive direction from templateType if not found in annotations
        String directionAsString = extractAnnotation(content, "@direction");
        if (directionAsString == null || directionAsString.isEmpty()) {
            directionAsString = templateTypeStr;
        }
        Direction direction = null;
        try {
            direction = Direction.valueOf(directionAsString);
        } catch (IllegalArgumentException e) {
            // direction is optional
        }

        boolean defaultTemplate = Boolean.parseBoolean(extractAnnotation(content, "@defaultTemplate"));
        boolean readonly = Boolean.parseBoolean(extractAnnotation(content, "@readonly"));

        String templateId;
        if (defaultTemplate && !defaultTemplateRegistered.get(templateType)) {
            templateId = templateType.name();
            defaultTemplateRegistered.put(templateType, true);
        } else {
            templateId = createCustomUuid();
        }

        if (codeTemplates.containsKey(templateId)) {
            log.info("Preserving existing template: {} ({}), skipping classpath version", name, templateId);
            return;
        }

        CodeTemplate template = new CodeTemplate(
                templateId, name, description, templateType, direction,
                encode(content), internal, readonly, defaultTemplate);
        codeTemplates.put(templateId, template);

        log.info("Loaded template: {} ({})", name, templateId);
    }

    /*
     * this step is required to use the new templateType:
     * INBOUND_SUBSTITUTION_AS_CODE
     * OUTBOUND_SUBSTITUTION_AS_CODE
     * instead of
     * INBOUND, // deprecated, use INBOUND_SUBSTITUTION_AS_CODE instead
     * OUTBOUND, // deprecated, use OUTBOUND_SUBSTITUTION_AS_CODE instead
     */
    public void migrateCodeTemplates(ServiceConfiguration configuration) {
        Map<String, CodeTemplate> codeTemplates = configuration.getCodeTemplates();
        Map<String, CodeTemplate> templatesToAdd = new HashMap<>();

        try {
            // First pass: collect templates that need to be migrated
            for (CodeTemplate template : codeTemplates.values()) {
                TemplateType templateType = template.templateType;
                String templateId = template.id;
                String name = template.name;

                // MIGRATION
                if (templateType == TemplateType.INBOUND) {
                    // Create a copy with updated templateType
                    CodeTemplate migratedTemplate = createMigratedTemplate(template,
                            TemplateType.INBOUND_SUBSTITUTION_AS_CODE);
                    templatesToAdd.put(TemplateType.INBOUND_SUBSTITUTION_AS_CODE.name(), migratedTemplate);
                    log.info("Prepared migration for template: {} ({})", name, templateId);
                } else if (templateType == TemplateType.OUTBOUND) {
                    // Create a copy with updated templateType
                    CodeTemplate migratedTemplate = createMigratedTemplate(template,
                            TemplateType.OUTBOUND_SUBSTITUTION_AS_CODE);
                    templatesToAdd.put(TemplateType.OUTBOUND_SUBSTITUTION_AS_CODE.name(), migratedTemplate);
                    log.info("Prepared migration for template: {} ({})", name, templateId);
                }
            }

            // Second pass: add the migrated templates
            codeTemplates.putAll(templatesToAdd);

            log.info("Successfully migrated {} templates", templatesToAdd.size());

        } catch (Exception e) {
            log.error("Failed to migrate code templates", e);
        }
    }

    private CodeTemplate createMigratedTemplate(CodeTemplate original, TemplateType newTemplateType) {
        CodeTemplate migrated = (CodeTemplate) original.clone(); // assuming you implement Cloneable
        migrated.templateType = newTemplateType;
        // Set direction based on the original templateType
        migrated.direction = (original.templateType == TemplateType.INBOUND)
                ? Direction.INBOUND
                : Direction.OUTBOUND;
        migrated.id = newTemplateType.name(); // Use the enum name as ID
        return migrated;
    }

    /**
     * Extracts annotation value from the file content.
     * 
     * @param content    The content of the template file
     * @param annotation The annotation name to extract
     * @return The value of the annotation or empty string if not found
     */
    private String extractAnnotation(String content, String annotation) {
        // Find the annotation in the content
        int annotationIndex = content.indexOf(annotation);
        if (annotationIndex == -1) {
            return "";
        }

        // Extract the value after the annotation
        int valueStartIndex = annotationIndex + annotation.length();
        while (valueStartIndex < content.length() &&
                (content.charAt(valueStartIndex) == ' ' || content.charAt(valueStartIndex) == ':')) {
            valueStartIndex++;
        }

        int valueEndIndex = content.indexOf('\n', valueStartIndex);
        if (valueEndIndex == -1) {
            valueEndIndex = content.length();
        }

        return content.substring(valueStartIndex, valueEndIndex).trim();
    }

    public String validateAndConvert(Resource resource) {
        try {
            return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Failed to read mapping file", e);
            return "{}";
        }
    }

    private String encode(String template) {
        if (template == null) {
            return "";
        }
        return Base64.getEncoder().encodeToString(template.getBytes(StandardCharsets.UTF_8));
    }

    public void saveServiceConfiguration(String tenant, final ServiceConfiguration configuration)
            throws JsonProcessingException {
        if (configuration == null) {
            return;
        }

        final String configurationJson = objectMapper.writeValueAsString(configuration);
        final OptionRepresentation optionRepresentation = OptionRepresentation.asOptionRepresentation(
                Utils.OPTION_CATEGORY_CONFIGURATION, OPTION_KEY_SERVICE_CONFIGURATION, configurationJson);
        tenantOptionApi.save(optionRepresentation);
    }

    public ServiceConfiguration getServiceConfiguration(String tenant) {
        final OptionPK option = new OptionPK();
        option.setCategory(Utils.OPTION_CATEGORY_CONFIGURATION);
        option.setKey(OPTION_KEY_SERVICE_CONFIGURATION);
        ServiceConfiguration result = subscriptionsService.callForTenant(tenant, () -> {
            ServiceConfiguration rt = null;
            try {
                final OptionRepresentation optionRepresentation = tenantOptionApi.getOption(option);
                if (optionRepresentation.getValue() == null) {
                    rt = initialize(tenant);
                } else {
                    rt = objectMapper.readValue(optionRepresentation.getValue(),
                            ServiceConfiguration.class);
                }
                log.debug("{} - Returning service configuration found: {}:", tenant, rt.getLogPayload());
                log.debug("{} - Found connection configuration: {}", tenant, rt);
            } catch (SDKException exception) {
                log.warn("{} - No configuration found, returning empty element!", tenant);
                rt = initialize(tenant);
            } catch (Exception e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                log.warn("Failed to convert service object. Error: {}", cause.getMessage());
                rt = initialize(tenant);
            }
            return rt;
        });
        return result;
    }

    public void deleteServiceConfigurations(String tenant) {
        OptionPK optionPK = new OptionPK(Utils.OPTION_CATEGORY_CONFIGURATION, OPTION_KEY_SERVICE_CONFIGURATION);
        tenantOptionApi.delete(optionPK);
    }

    public ServiceConfiguration initialize(String tenant) {
        ServiceConfiguration configuration = new ServiceConfiguration();
        try {
            saveServiceConfiguration(tenant, configuration);
        } catch (JsonProcessingException e) {
            log.warn("{} - failed to initialize ServiceConfiguration!", tenant, e);
        }
        return configuration;
    }

    private static String createCustomUuid() {
        return SECURE_RANDOM.ints(UUID_LENGTH, 0, 36)
                .mapToObj(i -> Character.toString(i < 10 ? '0' + i : 'a' + i - 10))
                .collect(Collectors.joining());
    }

    /**
     * Updates the annotation values in the code header to match the values in the
     * CodeTemplate object. Ensures that the header's metadata is consistent with
     * the object's properties.
     */
    public void rectifyHeaderInCodeTemplate(CodeTemplate codeTemplate, Boolean overrideHeaderWithMetadata) {
        if (codeTemplate == null || codeTemplate.code == null || codeTemplate.code.isEmpty()) {
            log.warn("Cannot rectify header: CodeTemplate or its code is null or empty");
            return;
        }

        try {
            // First decode the Base64 encoded code
            String decodedCode = decode(codeTemplate.code);
            if (decodedCode.isEmpty()) {
                log.warn("Cannot rectify header: Failed to decode template code");
                return;
            }

            // Validate and clean corrupted headers before processing
            decodedCode = cleanCorruptedHeader(decodedCode, codeTemplate);

            // Extract the header section - look for JSDoc block at the start of the file
            int headerEnd = findJSDocHeaderEnd(decodedCode);
            if (headerEnd == -1) {
                // No proper header found, create a new one
                decodedCode = createNewHeader(codeTemplate) + decodedCode;
            } else {
                String header = decodedCode.substring(0, headerEnd);
                String codeBody = stripStaleTemplateHeaders(decodedCode.substring(headerEnd));

                // Determine name and description based on overrideHeaderWithMetadata flag.
                // override=true (rename): payload metadata wins → write into header.
                // override=false (save):  header annotation wins → read from header and sync back to object.
                String name;
                String description;
                if (overrideHeaderWithMetadata) {
                    // Explicit rename: payload name/description must be non-empty
                    name = (codeTemplate.name != null && !codeTemplate.name.isEmpty())
                            ? codeTemplate.name
                            : extractAnnotation(header, "@name");
                    description = (codeTemplate.description != null && !codeTemplate.description.isEmpty())
                            ? codeTemplate.description
                            : extractAnnotation(header, "@description");
                    if (name == null || name.isEmpty()) {
                        log.warn("Rename requested but no name provided for template [{}]; keeping existing header name", codeTemplate.id);
                        name = extractAnnotation(header, "@name");
                    }
                } else {
                    // Regular save: trust the header annotation, fall back to object only when header has no value
                    name = extractAnnotation(header, "@name");
                    description = extractAnnotation(header, "@description");
                    if (name == null || name.isEmpty()) {
                        log.debug("No @name in header for template [{}]; falling back to object name '{}'", codeTemplate.id, codeTemplate.name);
                        name = codeTemplate.name;
                    }
                    if (description == null || description.isEmpty()) {
                        description = codeTemplate.description;
                    }
                }

                // Guard: never write a null or blank name/description into the header
                if (name == null || name.isEmpty()) {
                    log.warn("Could not resolve name for template [{}]; skipping header @name update", codeTemplate.id);
                    name = codeTemplate.name != null ? codeTemplate.name : codeTemplate.id;
                }
                if (description == null) {
                    description = "";
                }

                // Update header annotations
                header = updateAnnotation(header, "@name", name);
                header = updateAnnotation(header, "@description", description);
                header = updateAnnotation(header, "@templateType", codeTemplate.templateType.name());

                // Sync resolved name/description back to the object so callers see the final state
                codeTemplate.name = name;
                codeTemplate.description = description;

                // Handle direction annotation - use codeTemplate.direction if available
                Direction direction = codeTemplate.direction;
                if (direction == null) {
                    // MIGRATION: Try to extract from header for backward compatibility
                    String directionAsString = extractAnnotation(header, "@direction");
                    if (directionAsString != null && !directionAsString.isEmpty()) {
                        try {
                            direction = Direction.valueOf(directionAsString);
                        } catch (IllegalArgumentException e) {
                            log.debug("Could not parse direction from header: {}", directionAsString);
                        }
                    }
                }

                // Only add @direction annotation if direction is not null
                if (direction != null) {
                    header = updateAnnotation(header, "@direction", direction.name());
                }

                // Update boolean annotations
                header = updateAnnotation(header, "@defaultTemplate", String.valueOf(codeTemplate.defaultTemplate));
                header = updateAnnotation(header, "@internal", String.valueOf(codeTemplate.internal));
                header = updateAnnotation(header, "@readonly", String.valueOf(codeTemplate.readonly));

                // Combine updated header with code body
                decodedCode = header + codeBody;
            }

            // Re-encode the updated code and set it back to the template
            codeTemplate.code = encode(decodedCode);
            log.info("Successfully rectified header for template: {}", codeTemplate.name);

        } catch (Exception e) {
            log.error("Error rectifying header for template: {}", codeTemplate.name, e);
        }
    }

    /**
     * Cleans corrupted JSDoc headers that may have duplicate header blocks,
     * malformed closing tags, or invalid annotations like "@direction null".
     *
     * Common corruption patterns:
     * - Duplicate JSDoc blocks at the start
     * - Missing closing star-slash on first block
     * - "@direction null" as a literal string
     *
     * @param content The template code content
     * @param codeTemplate The CodeTemplate for reference
     * @return Cleaned content with only one valid JSDoc header
     */
    private String cleanCorruptedHeader(String content, CodeTemplate codeTemplate) {
        if (content == null || content.trim().isEmpty()) {
            return content;
        }

        String trimmed = content.trim();
        if (!trimmed.startsWith("/**")) {
            return content; // No JSDoc header, nothing to clean
        }

        try {
            // Check for duplicate JSDoc headers
            int firstHeaderEnd = trimmed.indexOf("*/");
            if (firstHeaderEnd == -1) {
                log.warn("Found unclosed JSDoc header for template: {}", codeTemplate.name);
                return content;
            }

            // Look for a second /** after the first header closes
            int secondHeaderStart = trimmed.indexOf("/**", firstHeaderEnd + 2);

            if (secondHeaderStart != -1) {
                // We have potential duplicate headers - check if it's within the first few hundred chars
                String betweenHeaders = trimmed.substring(firstHeaderEnd + 2, secondHeaderStart).trim();

                // If there's only whitespace between headers, we likely have corruption
                if (betweenHeaders.isEmpty()) {
                    log.warn("Detected duplicate JSDoc headers for template: {}. Cleaning...", codeTemplate.name);

                    // Find the end of the second header
                    int secondHeaderEnd = trimmed.indexOf("*/", secondHeaderStart);
                    if (secondHeaderEnd != -1) {
                        // Extract the code body (everything after the second header)
                        String codeBody = trimmed.substring(secondHeaderEnd + 2);

                        // Create a new clean header and combine with code body
                        log.info("Rebuilt header for template: {}", codeTemplate.name);
                        return createNewHeader(codeTemplate) + codeBody;
                    }
                }
            }

            // Check for malformed annotations in the first header
            String firstHeader = trimmed.substring(0, firstHeaderEnd + 2);
            boolean hasCorruption = false;

            // Check for "@direction null" literal
            if (firstHeader.contains("@direction null")) {
                log.warn("Found '@direction null' in template: {}. Cleaning...", codeTemplate.name);
                hasCorruption = true;
            }

            // Check for unclosed comment (missing */ before second header or code)
            if (firstHeader.contains("@direction") && !firstHeader.contains("*/")) {
                log.warn("Found unclosed JSDoc comment in template: {}. Cleaning...", codeTemplate.name);
                hasCorruption = true;
            }

            if (hasCorruption) {
                // Extract code body and rebuild with clean header
                String codeBody = trimmed.substring(firstHeaderEnd + 2);

                // If there's a second /** header, skip past it too
                if (secondHeaderStart != -1) {
                    int secondHeaderEnd = trimmed.indexOf("*/", secondHeaderStart);
                    if (secondHeaderEnd != -1) {
                        codeBody = trimmed.substring(secondHeaderEnd + 2);
                    }
                }

                log.info("Rebuilt header for corrupted template: {}", codeTemplate.name);
                return createNewHeader(codeTemplate) + codeBody;
            }

        } catch (Exception e) {
            log.error("Error cleaning corrupted header for template: {}. Proceeding with original content.",
                      codeTemplate.name, e);
            return content;
        }

        return content;
    }

    /**
     * Finds the end position of a JSDoc comment block that starts at the beginning of the content.
     * Returns -1 if no valid JSDoc header is found at the start.
     *
     * @param content The content to search
     * @return The end position (including the "* /") or -1 if not found
     */
    private int findJSDocHeaderEnd(String content) {
        if (content == null || !content.trim().startsWith("/**")) {
            return -1;
        }

        // Find the closing */ of the JSDoc block
        int pos = 0;
        while (pos < content.length()) {
            int closingPos = content.indexOf("*/", pos);
            if (closingPos == -1) {
                return -1; // No closing found
            }

            // Check if this is the first */ after the opening /**
            // A simple heuristic: if we find /** followed by */ without another /** in between, it's our header
            int nextOpenPos = content.indexOf("/**", pos + 3);
            if (nextOpenPos == -1 || nextOpenPos > closingPos) {
                // This */ closes our header
                return closingPos + 2;
            }

            // Otherwise, continue searching
            pos = closingPos + 2;
        }

        return -1;
    }

    /**
     * Creates a new header block with annotations for a CodeTemplate.
     * 
     * @param codeTemplate The CodeTemplate to create the header for
     * @return A properly formatted header block with annotations
     */
    private String createNewHeader(CodeTemplate codeTemplate) {
        StringBuilder header = new StringBuilder();
        header.append("/**\n");
        header.append(" * @name ").append(codeTemplate.name).append("\n");
        header.append(" * @description ").append(codeTemplate.description).append("\n");
        header.append(" * @templateType ").append(codeTemplate.templateType.name()).append("\n");
        if (codeTemplate.direction != null) {
            header.append(" * @direction ").append(codeTemplate.direction).append("\n");
        }
        header.append(" * @defaultTemplate ").append(codeTemplate.defaultTemplate).append("\n");
        header.append(" * @internal ").append(codeTemplate.internal).append("\n");
        header.append(" * @readonly ").append(codeTemplate.readonly).append("\n");
        header.append(" */\n\n");
        return header.toString();
    }

    /**
     * Updates an annotation in the content with a new value.
     * If the annotation doesn't exist, it will be added inside the existing
     * comment block, or a new block will be created.
     * Ensures exactly one space between the annotation and its value.
     */
    private String updateAnnotation(String content, String annotation, String value) {
        // Find the annotation in the content
        int annotationIndex = content.indexOf(annotation);

        if (annotationIndex == -1) {
            // Annotation not found, add it at the beginning of the file, after any comment
            // block
            if (content.trim().startsWith("/**")) {
                // There's already a comment block, add the annotation inside it
                int commentEndIndex = content.indexOf("*/");
                if (commentEndIndex != -1) {
                    // Insert before the end of the comment with proper formatting
                    return content.substring(0, commentEndIndex)
                            + " * " + annotation + " " + value + "\n"
                            + content.substring(commentEndIndex);
                }
            }

            // No comment block or couldn't find end of comment, add a new comment block
            return "/**\n * " + annotation + " " + value + "\n */\n" + content;
        } else {
            // Find the end of the current line
            int lineEndIndex = content.indexOf('\n', annotationIndex);
            if (lineEndIndex == -1) {
                lineEndIndex = content.length();
            }

            // Replace the entire annotation line to ensure correct spacing
            return content.substring(0, annotationIndex) + annotation + " " + value + content.substring(lineEndIndex);
        }
    }

    /**
     * Removes any stale template metadata JSDoc blocks from the code body.
     * A block is considered a stale template header if it contains {@code @templateType},
     * which is unique to dynamically generated template metadata headers.
     * This handles cases where a previous save prepended a new header without
     * removing an older one embedded in the body.
     */
    private String stripStaleTemplateHeaders(String codeBody) {
        if (codeBody == null || !codeBody.contains("@templateType")) {
            return codeBody;
        }
        StringBuilder result = new StringBuilder(codeBody);
        int searchFrom = 0;
        while (true) {
            int blockStart = result.indexOf("/**", searchFrom);
            if (blockStart == -1) break;
            int blockEnd = result.indexOf("*/", blockStart + 3);
            if (blockEnd == -1) break;
            String block = result.substring(blockStart, blockEnd + 2);
            if (block.contains("@templateType")) {
                log.warn("Removing stale template metadata header found in code body");
                int removeEnd = blockEnd + 2;
                while (removeEnd < result.length() && result.charAt(removeEnd) == '\n') {
                    removeEnd++;
                }
                result.delete(blockStart, removeEnd);
            } else {
                searchFrom = blockEnd + 2;
            }
        }
        return result.toString();
    }

    private String decode(String encodedString) {
        if (encodedString == null || encodedString.isEmpty()) {
            return "";
        }
        try {
            return new String(Base64.getDecoder().decode(encodedString), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            log.error("Failed to decode string", e);
            return "";
        }
    }
}