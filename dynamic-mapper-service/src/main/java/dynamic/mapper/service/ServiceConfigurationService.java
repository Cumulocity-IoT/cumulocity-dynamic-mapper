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
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

        // Scope annotation parsing to the JSDoc header only, not the full file
        int headerEnd = findJSDocHeaderEnd(content);
        String header = (headerEnd != -1) ? content.substring(0, headerEnd) : content;

        String name = extractAnnotation(header, "@name");
        String description = extractAnnotation(header, "@description");
        boolean internal = Boolean.parseBoolean(extractAnnotation(header, "@internal"));
        String templateTypeStr = extractAnnotation(header, "@templateType");

        TemplateType templateType;
        try {
            templateType = TemplateType.valueOf(templateTypeStr);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid template type in file {}: {}", fileName, templateTypeStr);
            return;
        }

        // Derive direction from @direction annotation; fall back to templateType prefix
        String directionAsString = extractAnnotation(header, "@direction");
        Direction direction = null;
        if (directionAsString != null && !directionAsString.isEmpty()) {
            try {
                direction = Direction.valueOf(directionAsString);
            } catch (IllegalArgumentException e) {
                log.debug("Could not parse @direction '{}' in file {}; deriving from templateType", directionAsString, fileName);
            }
        }
        if (direction == null) {
            // Derive from templateType prefix: INBOUND_* → INBOUND, OUTBOUND_* → OUTBOUND
            if (templateTypeStr.startsWith("INBOUND")) {
                direction = Direction.INBOUND;
            } else if (templateTypeStr.startsWith("OUTBOUND")) {
                direction = Direction.OUTBOUND;
            }
        }

        boolean defaultTemplate = Boolean.parseBoolean(extractAnnotation(header, "@defaultTemplate"));
        boolean readonly = Boolean.parseBoolean(extractAnnotation(header, "@readonly"));

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

        // Migrate header to two-section format on load so the divider is always present
        rectifyHeaderInCodeTemplate(template);

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
    /**
     * Synchronizes the JSDoc header inside the code with the POJO fields.
     * The POJO is always the single source of truth — header annotations are
     * a write-only artefact generated from the POJO, never parsed back.
     */
    public void rectifyHeaderInCodeTemplate(CodeTemplate codeTemplate) {
        if (codeTemplate == null || codeTemplate.code == null || codeTemplate.code.isEmpty()) {
            log.warn("Cannot rectify header: CodeTemplate or its code is null or empty");
            return;
        }

        try {
            String decodedCode = decode(codeTemplate.code);
            if (decodedCode.isEmpty()) {
                log.warn("Cannot rectify header: Failed to decode template code");
                return;
            }

            // Remove any corrupted or duplicate headers
            decodedCode = cleanCorruptedHeader(decodedCode, codeTemplate);

            int headerEnd = findJSDocHeaderEnd(decodedCode);
            if (headerEnd == -1) {
                // No header present — prepend one generated from POJO fields
                decodedCode = createNewHeader(codeTemplate) + decodedCode;
            } else {
                String header = decodedCode.substring(0, headerEnd);
                String codeBody = stripStaleTemplateHeaders(decodedCode.substring(headerEnd));

                // Replace the system section (/** to end-of-marker line) with freshly
                // generated content. Everything after the marker line (free-form docs + */)
                // is preserved unchanged.
                int markerPos = header.indexOf(SYSTEM_SECTION_MARKER);
                if (markerPos != -1) {
                    int markerLineEnd = header.indexOf('\n', markerPos);
                    if (markerLineEnd == -1) markerLineEnd = header.length();
                    String tail = header.substring(markerLineEnd); // \n * docs... */
                    header = buildSystemSection(codeTemplate) + tail;
                } else {
                    // No divider yet — migrate to two-section format.
                    // Preserve any free-form lines (sample payloads, docs) that sit between
                    // the old system annotations so they are not lost.
                    header = migrateHeaderToTwoSections(header, codeTemplate);
                }

                // Normalize to exactly one blank line between header closing */ and code body,
                // preventing an extra newline from accumulating on every save
                decodedCode = header + "\n\n" + codeBody.replaceAll("^\n+", "");
            }

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

    private static final Set<String> SYSTEM_ANNOTATIONS =
            Set.of("@name", "@description", "@templateType", "@direction", "@defaultTemplate", "@internal", "@readonly");
    // Note: @direction is included for migration stripping (redundant — derivable from @templateType prefix)

    /**
     * Migrates a legacy single-section header (all annotations mixed with free-form
     * text) to the new two-section format. System annotations are stripped from
     * their original positions and free-form documentation lines are preserved
     * between {@code @description} and the new system section divider.
     */
    private String migrateHeaderToTwoSections(String header, CodeTemplate codeTemplate) {
        // Split the raw header into individual lines
        String[] lines = header.split("\n", -1);
        List<String> freeFormLines = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line.trim();
            // Skip the JSDoc opener/closer, blank comment lines, and system annotations
            if (trimmed.equals("/**") || trimmed.equals("*/") || trimmed.equals("*")) {
                continue;
            }
            boolean isSystemAnnotation = SYSTEM_ANNOTATIONS.stream()
                    .anyMatch(ann -> trimmed.startsWith("* " + ann) || trimmed.equals("*" + ann));
            if (!isSystemAnnotation) {
                freeFormLines.add(line);
            }
        }

        // Strip trailing blank comment lines from the free-form block
        while (!freeFormLines.isEmpty()) {
            String t = freeFormLines.get(freeFormLines.size() - 1).trim();
            if (t.isEmpty() || t.equals("*")) {
                freeFormLines.remove(freeFormLines.size() - 1);
            } else {
                break;
            }
        }

        StringBuilder newHeader = new StringBuilder();
        newHeader.append(buildSystemSection(codeTemplate)).append("\n");
        if (!freeFormLines.isEmpty()) {
            newHeader.append(" *\n");
            for (String line : freeFormLines) {
                newHeader.append(line).append("\n");
            }
        }
        newHeader.append(" */");
        return newHeader.toString();
    }

    private static final String SYSTEM_SECTION_MARKER =
            " * --- metadata above is auto-generated, add your documentation below ---";

    /**
     * Builds the system-managed section of a JSDoc header from POJO fields.
     * This section is auto-generated on every save and should not be edited manually.
     */
    private String buildSystemSection(CodeTemplate codeTemplate) {
        String name = (codeTemplate.name != null && !codeTemplate.name.isEmpty())
                ? codeTemplate.name : codeTemplate.id;
        String description = codeTemplate.description != null ? codeTemplate.description : "";
        StringBuilder sb = new StringBuilder();
        sb.append("/**\n");
        sb.append(" * @name ").append(name).append("\n");
        sb.append(" * @description ").append(description).append("\n");
        sb.append(" * @templateType ").append(codeTemplate.templateType.name()).append("\n");
        sb.append(" * @defaultTemplate ").append(codeTemplate.defaultTemplate).append("\n");
        sb.append(" * @internal ").append(codeTemplate.internal).append("\n");
        sb.append(" * @readonly ").append(codeTemplate.readonly).append("\n");
        sb.append(SYSTEM_SECTION_MARKER);
        return sb.toString();
    }

    /**
     * Creates a new header block for a CodeTemplate with no pre-existing header.
     * The user-editable area above the divider is empty; all metadata lives in
     * the system-managed section below the divider.
     *
     * @param codeTemplate The CodeTemplate to create the header for
     * @return A properly formatted header block
     */
    private String createNewHeader(CodeTemplate codeTemplate) {
        // System section first, then empty user-doc area, then closing */
        return buildSystemSection(codeTemplate) + "\n */\n\n";
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