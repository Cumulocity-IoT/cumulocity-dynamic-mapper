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

import java.util.Map;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;

import dynamic.mapper.core.ServiceRegistry;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Business rules for the code templates of a tenant: read, create, update, and delete.
 * <p>
 * Every change is saved to the tenant's service configuration, then published to the
 * {@link ServiceRegistry}, then (for the SHARED and SYSTEM templates) pushed into the GraalVM source
 * cache. A rule violation is reported as a {@link CodeTemplateException} that carries the reason;
 * the REST layer maps it to an HTTP status.
 */
@Slf4j
@Service
public class CodeTemplateService {

    /** Why a code template operation was refused or failed. */
    public enum Reason {
        NOT_FOUND, NOT_ALLOWED, ID_MISMATCH, ALREADY_EXISTS, FAILED
    }

    /** A refused or failed code template operation. */
    public static class CodeTemplateException extends RuntimeException {
        @Getter
        private final Reason reason;

        public CodeTemplateException(Reason reason, String message) {
            super(message);
            this.reason = reason;
        }

        public CodeTemplateException(Reason reason, String message, Throwable cause) {
            super(message, cause);
            this.reason = reason;
        }
    }

    private final ServiceConfigurationService serviceConfigurationService;
    private final CodeTemplateLoader codeTemplateLoader;
    private final CodeTemplateHeaderService headers;
    private final ServiceRegistry serviceRegistry;

    public CodeTemplateService(ServiceConfigurationService serviceConfigurationService,
            CodeTemplateLoader codeTemplateLoader, CodeTemplateHeaderService headers,
            ServiceRegistry serviceRegistry) {
        this.serviceConfigurationService = serviceConfigurationService;
        this.codeTemplateLoader = codeTemplateLoader;
        this.headers = headers;
        this.serviceRegistry = serviceRegistry;
    }

    /** All code templates of the tenant, seeding the shipped ones on first access. */
    public Map<String, CodeTemplate> getAll(String tenant) {
        return templates(tenant, serviceConfigurationService.getServiceConfiguration(tenant));
    }

    /** @throws CodeTemplateException with {@link Reason#NOT_FOUND} if the template does not exist */
    public CodeTemplate get(String tenant, String id) {
        CodeTemplate result = getAll(tenant).get(id);
        if (result == null) {
            log.warn("{} - Code template with ID [{}] not found", tenant, id);
            throw new CodeTemplateException(Reason.NOT_FOUND, "Code template with ID [" + id + "] not found");
        }
        return result;
    }

    /**
     * Deletes a template. Internal templates cannot be deleted, and neither can SHARED, because every
     * GraalVM engine loads it; clearing its code is the supported way.
     */
    public CodeTemplate delete(String tenant, String id) {
        ServiceConfiguration serviceConfiguration = serviceConfigurationService.getServiceConfiguration(tenant);
        Map<String, CodeTemplate> codeTemplates = templates(tenant, serviceConfiguration);

        // Checked before the try: both are documented outcomes that the generic failure handling
        // below would otherwise turn into FAILED.
        CodeTemplate existing = codeTemplates.get(id);
        if (existing == null) {
            log.warn("{} - Code template with ID [{}] not found", tenant, id);
            throw new CodeTemplateException(Reason.NOT_FOUND, "Code template with ID [" + id + "] not found");
        }
        if (existing.internal) {
            throw new CodeTemplateException(Reason.NOT_ALLOWED, "Deletion of internal templates not allowed");
        }
        if (TemplateType.SHARED.name().equals(id)) {
            throw new CodeTemplateException(Reason.NOT_ALLOWED,
                    "The SHARED template cannot be deleted; clear its code instead");
        }

        CodeTemplate removed;
        try {
            removed = codeTemplates.remove(id);
            serviceConfigurationService.saveServiceConfiguration(tenant, serviceConfiguration);
            // Unreachable for SHARED (refused above) and normally for SYSTEM (internal), but kept
            // so the cache can never outlive a template that was removed by another route.
            updateGraalSource(tenant, id, "");
        } catch (Exception ex) {
            log.error("{} - Error deleting code template [{}]", tenant, id, ex);
            throw new CodeTemplateException(Reason.FAILED, ex.getLocalizedMessage(), ex);
        }
        serviceRegistry.addServiceConfiguration(tenant, serviceConfiguration);
        return removed;
    }

    /**
     * Updates a template, creating it when none is stored under the id. A read-only template
     * (SYSTEM) cannot be modified: it holds the Java bindings the runtime needs and is re-loaded
     * from the classpath on every start.
     */
    public void update(String tenant, String id, CodeTemplate codeTemplate) {
        if (codeTemplate.id != null && !id.equals(codeTemplate.id)) {
            throw new CodeTemplateException(Reason.ID_MISMATCH,
                    String.format("Path id '%s' does not match body id '%s'", id, codeTemplate.id));
        }
        codeTemplate.id = id;
        try {
            ServiceConfiguration serviceConfiguration = serviceConfigurationService.getServiceConfiguration(tenant);
            Map<String, CodeTemplate> codeTemplates = templates(tenant, serviceConfiguration);

            CodeTemplate existing = codeTemplates.get(id);
            if (existing != null && existing.readonly) {
                throw new CodeTemplateException(Reason.NOT_ALLOWED,
                        String.format("Template '%s' is read-only and cannot be modified", id));
            }

            if (existing != null) {
                // A hand-edit of @name / @description in the header is adopted before the header is
                // regenerated from the fields, which would otherwise overwrite it.
                headers.adoptHeaderMetadataEdits(codeTemplate, existing);
                // A client that predates the sample fields drops them on a round-trip; keep the
                // stored values rather than persisting a null over them.
                if (codeTemplate.samplePayload == null) {
                    codeTemplate.samplePayload = existing.samplePayload;
                }
                if (codeTemplate.sampleTopic == null) {
                    codeTemplate.sampleTopic = existing.sampleTopic;
                }
            }
            persist(tenant, serviceConfiguration, codeTemplates, codeTemplate);
            log.debug("{} - Updated code template", tenant);
        } catch (CodeTemplateException ex) {
            log.warn("{} - Error updating code template [{}]: {}", tenant, id, ex.getMessage());
            throw ex;
        } catch (Exception ex) {
            log.error("{} - Error updating code template [{}]", tenant, id, ex);
            throw new CodeTemplateException(Reason.FAILED, ex.getLocalizedMessage(), ex);
        }
    }

    /**
     * Creates a template.
     * <p>
     * {@code defaultTemplate} means "the template a new mapping of this type starts from", which the
     * seeding code expresses by giving it the type's name as its id. A template with another id
     * cannot be the default, whatever the request says: the UI's Duplicate action copied the flag,
     * which left two templates of one type claiming it, and the next start then gave the shipped
     * template a random id and broke the editor's lookup of the starting template.
     */
    public void create(String tenant, CodeTemplate codeTemplate) {
        try {
            ServiceConfiguration serviceConfiguration = serviceConfigurationService.getServiceConfiguration(tenant);
            Map<String, CodeTemplate> codeTemplates = templates(tenant, serviceConfiguration);
            if (codeTemplates.containsKey(codeTemplate.id)) {
                throw new CodeTemplateException(Reason.ALREADY_EXISTS,
                        String.format("Template with id '%s' already exists", codeTemplate.id));
            }
            if (codeTemplate.defaultTemplate && codeTemplate.templateType != null
                    && !codeTemplate.templateType.name().equals(codeTemplate.id)) {
                log.info("{} - Ignoring defaultTemplate=true on new template [{}]: only '{}' can be "
                        + "the default for {}", tenant, codeTemplate.id,
                        codeTemplate.templateType.name(), codeTemplate.templateType);
                codeTemplate.defaultTemplate = false;
            }
            persist(tenant, serviceConfiguration, codeTemplates, codeTemplate);
            log.debug("{} - Create code template", tenant);
        } catch (CodeTemplateException ex) {
            log.warn("{} - Error creating code template: {}", tenant, ex.getMessage());
            throw ex;
        } catch (Exception ex) {
            log.error("{} - Error creating code template", tenant, ex);
            throw new CodeTemplateException(Reason.FAILED, ex.getLocalizedMessage(), ex);
        }
    }

    /** Writes the header, stores the template, saves and publishes the configuration, refreshes the source cache. */
    private void persist(String tenant, ServiceConfiguration serviceConfiguration,
            Map<String, CodeTemplate> codeTemplates, CodeTemplate codeTemplate) throws JsonProcessingException {
        headers.rectifyHeaderInCodeTemplate(codeTemplate);
        codeTemplates.put(codeTemplate.id, codeTemplate);
        serviceConfigurationService.saveServiceConfiguration(tenant, serviceConfiguration);
        serviceRegistry.addServiceConfiguration(tenant, serviceConfiguration);
        updateGraalSource(tenant, codeTemplate.id, codeTemplate.getCode());
    }

    /** SHARED and SYSTEM code is cached by the GraalVM service; every other template is not. */
    private void updateGraalSource(String tenant, String id, String code) {
        if (TemplateType.SHARED.name().equals(id)) {
            serviceRegistry.getGraalVMContextService().updateGraalsSourceShared(tenant, code);
            log.info("{} - Updated cached SHARED code source", tenant);
        } else if (TemplateType.SYSTEM.name().equals(id)) {
            serviceRegistry.getGraalVMContextService().updateGraalsSourceSystem(tenant, code);
            log.info("{} - Updated cached SYSTEM code source", tenant);
        }
    }

    /** The templates of a configuration; the shipped ones are seeded, saved, and published when there are none. */
    private Map<String, CodeTemplate> templates(String tenant, ServiceConfiguration serviceConfiguration) {
        Map<String, CodeTemplate> codeTemplates = serviceConfiguration.getCodeTemplates();
        if (codeTemplates == null || codeTemplates.isEmpty()) {
            codeTemplateLoader.initCodeTemplates(serviceConfiguration, false);
            codeTemplates = serviceConfiguration.getCodeTemplates();
            try {
                serviceConfigurationService.saveServiceConfiguration(tenant, serviceConfiguration);
                serviceRegistry.addServiceConfiguration(tenant, serviceConfiguration);
            } catch (JsonProcessingException ex) {
                log.error("{} - Error saving service configuration with code templates", tenant, ex);
                throw new CodeTemplateException(Reason.FAILED, ex.getLocalizedMessage(), ex);
            }
        }
        return codeTemplates;
    }
}
