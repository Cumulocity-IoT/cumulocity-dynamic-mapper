/*
 * Copyright (c) 2022-2026 Cumulocity GmbH.
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
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Loads the code templates that ship with the service ({@code classpath:templates/template*.js})
 * into a tenant's {@link ServiceConfiguration}.
 * <p>
 * It seeds all templates on first start, adds templates introduced by an upgrade, and re-loads the
 * framework-owned SYSTEM template on every start. It changes only the configuration object it is
 * given; saving it is up to the caller.
 */
@Slf4j
@Component
public class CodeTemplateLoader {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int UUID_LENGTH = 8;

    private final CodeTemplateHeaderService headers;

    public CodeTemplateLoader(CodeTemplateHeaderService headers) {
        this.headers = headers;
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
     * @return {@code true} if at least one template was added, or a stored template received its
     *         sample payload/topic from the packaged version (caller should persist)
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
        Map<String, CodeTemplate> existingByKey = new HashMap<>();
        for (CodeTemplate t : codeTemplates.values()) {
            existingByKey.putIfAbsent(templateKey(t.templateType != null ? t.templateType.name() : "", t.name), t);
        }
        Set<String> existingTemplates = existingByKey.keySet();

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
                int headerEnd = headers.findJSDocHeaderEnd(content);
                String header = (headerEnd != -1) ? content.substring(0, headerEnd) : content;
                String name = headers.extractAnnotation(header, "@name");
                String templateType = headers.extractAnnotation(header, "@templateType");

                CodeTemplate stored = existingByKey.get(templateKey(templateType, name));
                if (stored != null) {
                    // Already stored, so the template itself is left alone (the tenant may have
                    // edited it). Only the sample payload/topic, which stored copies from before
                    // those fields existed lack, are filled in from the packaged header; a value
                    // that is already there is never overwritten.
                    String packaged = headers.packagedHeader(content);
                    if (stored.samplePayload == null) {
                        String samplePayload = headers.extractSamplePayload(packaged);
                        if (samplePayload != null) {
                            stored.samplePayload = samplePayload;
                            anyAdded = true;
                        }
                    }
                    if (stored.sampleTopic == null) {
                        String sampleTopic = headers.extractAnnotation(packaged, "@sampleTopic");
                        if (!sampleTopic.isEmpty()) {
                            stored.sampleTopic = sampleTopic;
                            anyAdded = true;
                        }
                    }
                    continue;
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
     * running "Init internal code templates". Shipping the fix should be enough.
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

        // Scope annotation parsing to the JSDoc header only, not the full file. The shipped
        // templates open with `// @ts-check`, so the header does not start at offset 0 and the
        // prologue has to come off first -- otherwise findJSDocHeaderEnd reports "no header" and
        // every annotation is looked up across the whole file, where a stray `@name` in a comment
        // would win.
        String body = content.substring(headers.leadingDirectives(content).length());
        int headerEnd = headers.findJSDocHeaderEnd(body);
        String header = (headerEnd != -1) ? body.substring(0, headerEnd) : body;

        String name = headers.extractAnnotation(header, "@name");
        String description = headers.extractAnnotation(header, "@description");
        String templateTypeStr = headers.extractAnnotation(header, "@templateType");

        TemplateType templateType;
        try {
            templateType = TemplateType.valueOf(templateTypeStr);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid template type in file {}: {}", fileName, templateTypeStr);
            return;
        }

        boolean defaultTemplate = Boolean.parseBoolean(headers.extractAnnotation(header, "@defaultTemplate"));
        // Derived from the type rather than declared per file: see
        // TemplateType.isFrameworkOwnedWhenShipped(). `internal` (no DELETE) and `readonly`
        // (no PUT) remain separate fields on CodeTemplate because they guard different endpoints;
        // what they are for a *shipped* template is simply not an independent decision.
        boolean frameworkOwned = templateType.isFrameworkOwnedWhenShipped();
        boolean internal = frameworkOwned;
        boolean readonly = frameworkOwned;

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
                headers.encode(content), internal, readonly, defaultTemplate,
                headers.extractSamplePayload(header), headers.extractAnnotation(header, "@sampleTopic"));

        // Migrate header to two-section format on load so the divider is always present
        headers.rectifyHeaderInCodeTemplate(template);

        codeTemplates.put(templateId, template);

        log.info("Loaded template: {} ({})", name, templateId);
    }

    private static String createCustomUuid() {
        return SECURE_RANDOM.ints(UUID_LENGTH, 0, 36)
                .mapToObj(i -> Character.toString(i < 10 ? '0' + i : 'a' + i - 10))
                .collect(Collectors.joining());
    }
}
