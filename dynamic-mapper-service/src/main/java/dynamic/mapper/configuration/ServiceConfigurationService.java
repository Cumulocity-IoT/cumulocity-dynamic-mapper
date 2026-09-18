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

package dynamic.mapper.configuration;

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
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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

import dynamic.mapper.util.Utils;
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

    private final MicroserviceSubscriptionsService subscriptionsService;

    private final ObjectMapper objectMapper;

    public ServiceConfigurationService(TenantOptionApi tenantOptionApi,
            MicroserviceSubscriptionsService subscriptionsService, ObjectMapper objectMapper) {
        this.tenantOptionApi = tenantOptionApi;
        this.subscriptionsService = subscriptionsService;
        this.objectMapper = objectMapper;
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

    /**
     * Scans classpath templates and adds any that are not yet present in the stored
     * configuration (matched by {@code @name}).  Called on startup when templates
     * already exist, so newly deployed internal templates are picked up automatically
     * without requiring a manual "Reset System Templates" operation.
     *
     * @return {@code true} if at least one template was added (caller should persist)
     */
    public boolean addMissingInternalTemplates(ServiceConfiguration configuration) {
        Map<String, CodeTemplate> codeTemplates = configuration.getCodeTemplates();
        if (codeTemplates == null) {
            codeTemplates = new HashMap<>();
            configuration.setCodeTemplates(codeTemplates);
        }

        // Index existing templates for an O(1) duplicate check, regardless of origin.
        // The key is @templateType + @name, not @name alone: the inbound and the outbound
        // default both ship as "Default template for Smart Function", and matching on the
        // name alone meant a tenant that had one of them never received the other.
        Set<String> existingTemplates = codeTemplates.values().stream()
                .map(t -> templateKey(t.templateType != null ? t.templateType.name() : "", t.name))
                .collect(Collectors.toSet());

        // Track which templateTypes already have a default registered
        Map<TemplateType, Boolean> defaultTemplateRegistered = new EnumMap<>(TemplateType.class);
        for (TemplateType type : TemplateType.values()) {
            boolean alreadyHasDefault = codeTemplates.values().stream()
                    .anyMatch(t -> t.templateType == type && t.defaultTemplate);
            defaultTemplateRegistered.put(type, alreadyHasDefault);
        }

        Resource[] resources;
        try {
            ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            resources = resolver.getResources("classpath:templates/template*.js");
        } catch (IOException e) {
            log.error("Failed to load template resources during migration check", e);
            return false;
        }

        boolean anyAdded = false;
        for (Resource resource : resources) {
            try {
                if (resource.getFilename() == null) continue;

                // Peek at the header only to extract the @name for duplicate check
                String content;
                try (InputStream is = resource.getInputStream()) {
                    content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                }
                int headerEnd = findJSDocHeaderEnd(content);
                String header = (headerEnd != -1) ? content.substring(0, headerEnd) : content;
                String name = extractAnnotation(header, "@name");
                String templateType = extractAnnotation(header, "@templateType");

                if (existingTemplates.contains(templateKey(templateType, name))) {
                    continue; // Already stored — skip
                }

                int sizeBefore = codeTemplates.size();
                loadTemplate(resource, codeTemplates, defaultTemplateRegistered);
                if (codeTemplates.size() > sizeBefore) {
                    log.info("Added new internal template on startup: {}", name);
                    anyAdded = true;
                }
            } catch (Exception e) {
                log.error("Failed to check/add template: {}", resource.getFilename(), e);
            }
        }
        return anyAdded;
    }

    /** Identity of a code template for duplicate detection: its type plus its case-folded name. */
    private static String templateKey(String templateType, String name) {
        return templateType + "|" + (name != null ? name.toLowerCase() : "");
    }

    /**
     * Re-loads the SYSTEM code template from the classpath on every startup, replacing whatever
     * the tenant has stored.
     *
     * <p>SYSTEM is framework-owned: it declares {@code @internal true} / {@code @readonly true}
     * and holds nothing but the {@code Java.type(...)} bindings and polyfills the runtime needs.
     * It is not a place for customer code — that is what SHARED
     * ({@code @internal false} / {@code @readonly false}) is for, and SHARED is never touched
     * here.
     *
     * <p>Without this, a template that names a Java class which has since moved package keeps
     * failing after an upgrade, because {@link #addMissingInternalTemplates} only adds templates
     * that are absent by {@code @name} and never overwrites one already stored. The symptom is
     * every Smart Function mapping in the tenant dying at activation with
     * {@code Access to host class ... is not allowed or does not exist}, fixable only by manually
     * running "Init system code templates". Shipping the fix should be enough.
     *
     * @return {@code true} when the stored template differed and was replaced (caller should
     *         persist the configuration)
     */
    public boolean refreshSystemTemplate(ServiceConfiguration configuration) {
        Map<String, CodeTemplate> codeTemplates = configuration.getCodeTemplates();
        if (codeTemplates == null) {
            return false;
        }

        String systemId = TemplateType.SYSTEM.name();
        CodeTemplate stored = codeTemplates.get(systemId);

        Resource resource;
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath:templates/template-SYSTEM.js");
            if (resources.length == 0) {
                log.error("Packaged system template not found on the classpath; keeping the stored one");
                return false;
            }
            resource = resources[0];
        } catch (IOException e) {
            log.error("Failed to read the packaged system template; keeping the stored one", e);
            return false;
        }

        // Load into a scratch map so a failure cannot leave the tenant without a SYSTEM template.
        Map<String, CodeTemplate> loaded = new HashMap<>();
        Map<TemplateType, Boolean> defaultTemplateRegistered = new EnumMap<>(TemplateType.class);
        for (TemplateType type : TemplateType.values()) {
            defaultTemplateRegistered.put(type, false);
        }
        try {
            loadTemplate(resource, loaded, defaultTemplateRegistered);
        } catch (Exception e) {
            log.error("Failed to parse the packaged system template; keeping the stored one", e);
            return false;
        }

        CodeTemplate packaged = loaded.get(systemId);
        if (packaged == null) {
            log.error("Packaged system template did not register under '{}'; keeping the stored one", systemId);
            return false;
        }

        if (stored != null && Objects.equals(stored.code, packaged.code)) {
            return false;
        }

        codeTemplates.put(systemId, packaged);
        log.info("Refreshed the SYSTEM code template from the packaged version{}",
                stored == null ? " (none was stored)" : " (stored copy was out of date)");
        return true;
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
        String templateTypeStr = extractAnnotation(header, "@templateType");

        TemplateType templateType;
        try {
            templateType = TemplateType.valueOf(templateTypeStr);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid template type in file {}: {}", fileName, templateTypeStr);
            return;
        }

        boolean defaultTemplate = Boolean.parseBoolean(extractAnnotation(header, "@defaultTemplate"));
        // @internal/@readonly decide whether "Init system code templates" may replace a stored
        // template. Absent, they parse to false, which silently turns a shipped template into an
        // editable tenant copy that no reset can ever clear -- so say so rather than default quietly.
        boolean internal = parseRequiredFlag(header, "@internal", fileName, name);
        boolean readonly = parseRequiredFlag(header, "@readonly", fileName, name);

        String templateId;
        if (defaultTemplate && !defaultTemplateRegistered.get(templateType)) {
            templateId = templateType.name();
            defaultTemplateRegistered.put(templateType, true);
        } else {
            if (defaultTemplate) {
                // Only one template per type can own the type's id, and which one wins would
                // come down to classpath enumeration order — so say so instead of picking silently.
                log.warn("Template '{}' declares @defaultTemplate true but {} already has a default;"
                        + " loading it as an ordinary template", name, templateType);
            }
            templateId = createCustomUuid();
        }

        if (codeTemplates.containsKey(templateId)) {
            log.info("Preserving existing template: {} ({}), skipping classpath version", name, templateId);
            return;
        }

        CodeTemplate template = new CodeTemplate(
                templateId, name, description, templateType,
                encode(content), internal, readonly, defaultTemplate);

        // Migrate header to two-section format on load so the divider is always present
        rectifyHeaderInCodeTemplate(template);

        codeTemplates.put(templateId, template);

        log.info("Loaded template: {} ({})", name, templateId);
    }

    /**
     * Parses a boolean flag that every packaged template is expected to declare, warning when it
     * is missing instead of defaulting to {@code false} without a trace.
     */
    private boolean parseRequiredFlag(String header, String annotation, String fileName, String name) {
        String raw = extractAnnotation(header, annotation);
        if (raw == null || raw.isEmpty()) {
            log.warn("Template '{}' in file {} does not declare {}; assuming false", name, fileName, annotation);
            return false;
        }
        return Boolean.parseBoolean(raw);
    }

    /**
     * Extracts annotation value from the file content.
     *
     * <p>Matches only occurrences anchored at the start of a JSDoc comment line
     * (e.g. {@code " * @name Foo"}), not a bare substring search — otherwise an
     * annotation-like token appearing inside the free-form documentation area
     * (below the {@link #SYSTEM_SECTION_MARKER}) could be mistaken for the real
     * system annotation.
     *
     * <p>Package-private so the header round-trip (render → parse) can be tested directly.
     *
     * @param content    The content of the template file
     * @param annotation The annotation name to extract (e.g. {@code "@name"})
     * @return The value of the annotation or empty string if not found
     */
    String extractAnnotation(String content, String annotation) {
        if (content == null) {
            return "";
        }
        Pattern pattern = Pattern.compile(
                "^[ \\t]*\\*[ \\t]*" + Pattern.quote(annotation) + "\\b[ \\t:]*(.*)$");
        String[] lines = content.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            Matcher matcher = pattern.matcher(lines[i]);
            if (!matcher.matches()) {
                continue;
            }
            StringBuilder value = new StringBuilder(matcher.group(1).trim());
            // Long values (in practice @description) are wrapped over several lines; without
            // this the value was truncated at the first line and the rest of the sentence was
            // left behind in the header, where the next save relocated it below the
            // auto-generated marker as orphaned prose.
            for (int j = i + 1; j < lines.length; j++) {
                String continuation = annotationContinuation(lines[j]);
                if (continuation == null) {
                    break;
                }
                if (value.length() > 0) {
                    value.append(' ');
                }
                value.append(continuation);
            }
            return value.toString().trim();
        }
        return "";
    }

    /**
     * Matches a JSDoc line that continues the annotation started on the previous line: a comment
     * line whose text is indented past the single space that normal doc lines use, and that does
     * not open a new tag. This is how {@link #renderDescription} writes wrapped values and how
     * the shipped templates are hand-written.
     *
     * <p>Requiring the extra indentation is what keeps free-form documentation out of the value:
     * a line like {@code " * Sample payload"} written straight under an annotation ends it.
     */
    private static final Pattern ANNOTATION_CONTINUATION =
            Pattern.compile("^[ \\t]*\\*[ \\t]{2,}(?!@)(\\S.*?)[ \\t]*$");

    /**
     * @return the continuation text of {@code line}, or {@code null} when the line ends the
     *         annotation (a blank {@code *} line, a new {@code @tag}, the section marker, the
     *         closing {@code *}{@code /} or an unindented doc line).
     */
    private String annotationContinuation(String line) {
        Matcher matcher = ANNOTATION_CONTINUATION.matcher(line);
        if (!matcher.matches()) {
            return null;
        }
        String text = matcher.group(1);
        return text.startsWith("---") ? null : text;
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
                    applyDefaults(rt);
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

    private void applyDefaults(ServiceConfiguration config) {
        if (config.getEngineRotationThreshold() == null) config.setEngineRotationThreshold(100);
        if (config.getEngineMaxAgeMinutes() == null)      config.setEngineMaxAgeMinutes(0);
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
    // Note: @direction is no longer parsed or emitted -- it is listed only so that legacy headers
    // still carrying it have the line stripped on the next rectify. See TemplateType#getDirection.

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
        boolean inSystemAnnotation = false;
        for (String line : lines) {
            String trimmed = line.trim();
            // Skip the JSDoc opener/closer and system annotations
            if (trimmed.equals("/**") || trimmed.equals("*/")) {
                inSystemAnnotation = false;
                continue;
            }
            if (trimmed.equals("*")) {
                inSystemAnnotation = false;
                // Keep blank lines *inside* the free-form block — they separate its paragraphs.
                // Leading ones are dropped here, trailing ones below.
                if (!freeFormLines.isEmpty()) {
                    freeFormLines.add(line);
                }
                continue;
            }
            boolean isSystemAnnotation = SYSTEM_ANNOTATIONS.stream()
                    .anyMatch(ann -> trimmed.startsWith("* " + ann) || trimmed.equals("*" + ann));
            if (isSystemAnnotation) {
                inSystemAnnotation = true;
                continue;
            }
            // A wrapped system annotation keeps its continuation lines: they belong to the value
            // that has just been regenerated from the POJO, so carrying them over would duplicate
            // half a sentence as free-form documentation.
            if (inSystemAnnotation && annotationContinuation(line) != null) {
                continue;
            }
            inSystemAnnotation = false;
            freeFormLines.add(line);
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
        StringBuilder sb = new StringBuilder();
        sb.append("/**\n");
        sb.append(" * @name ").append(name).append("\n");
        sb.append(renderDescription(codeTemplate.description)).append("\n");
        sb.append(" * @templateType ").append(codeTemplate.templateType.name()).append("\n");
        sb.append(" * @defaultTemplate ").append(codeTemplate.defaultTemplate).append("\n");
        sb.append(" * @internal ").append(codeTemplate.internal).append("\n");
        sb.append(" * @readonly ").append(codeTemplate.readonly).append("\n");
        sb.append(SYSTEM_SECTION_MARKER);
        return sb.toString();
    }

    /** Indentation of a wrapped {@code @description} line, aligned under the tag's value. */
    private static final String DESCRIPTION_CONTINUATION_INDENT = " *              ";

    /** Column at which a long {@code @description} is wrapped onto a continuation line. */
    private static final int DESCRIPTION_WRAP_WIDTH = 100;

    /**
     * Renders the {@code @description} line(s), wrapping a long description over continuation
     * lines that {@link #extractAnnotation} reads back as one value. Any newline the description
     * carries is folded into a space first, so a multi-line value can never break out of the
     * JSDoc block.
     */
    private String renderDescription(String description) {
        String text = description == null ? "" : description.trim().replaceAll("\\s+", " ");
        if (text.isEmpty()) {
            return " * @description";
        }
        StringBuilder rendered = new StringBuilder();
        StringBuilder line = new StringBuilder(" * @description");
        for (String word : text.split(" ")) {
            boolean lineIsEmpty = line.length() <= DESCRIPTION_CONTINUATION_INDENT.length();
            if (!lineIsEmpty && line.length() + 1 + word.length() > DESCRIPTION_WRAP_WIDTH) {
                rendered.append(line).append("\n");
                line = new StringBuilder(DESCRIPTION_CONTINUATION_INDENT);
                line.append(word);
                continue;
            }
            if (line.charAt(line.length() - 1) != ' ') {
                line.append(' ');
            }
            line.append(word);
        }
        return rendered.append(line).toString();
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