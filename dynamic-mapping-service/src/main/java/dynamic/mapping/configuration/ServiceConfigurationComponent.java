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

package dynamic.mapping.configuration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;
import com.cumulocity.model.option.OptionPK;
import com.cumulocity.rest.representation.tenant.OptionRepresentation;
import com.cumulocity.sdk.client.SDKException;
import com.cumulocity.sdk.client.option.TenantOptionApi;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class ServiceConfigurationComponent {
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int UUID_LENGTH = 6;

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

    private static final String OPTION_CATEGORY_CONFIGURATION = "dynamic.mapper.service";

    private static final String OPTION_KEY_SERVICE_CONFIGURATION = "service.configuration";

    private final TenantOptionApi tenantOptionApi;

    @Autowired
    private MicroserviceSubscriptionsService subscriptionsService;

    private ObjectMapper objectMapper;

    @Autowired
    public void setObjectMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ServiceConfigurationComponent(TenantOptionApi tenantOptionApi) {
        this.tenantOptionApi = tenantOptionApi;
    }

    /*
     * enhance this method to
     * 1. load all templates in the directory templates, they are javascript files
     * 2. parse the header of the file for the
     * annotation @name, @internal, @templateType, @defaultTemplate, @readonly
     * 3. the first template with the value defaultTemplate set to true for every
     * category in TemplateType should be registered with its own key instead of
     * createCustomUuid
     */
    public void initCodeTemplates(ServiceConfiguration configuration) {
        Map<String, CodeTemplate> codeTemplates = new HashMap<>();
        Map<TemplateType, Boolean> defaultTemplateRegistered = new HashMap<>();

        try {
            // Initialize the defaultTemplateRegistered map with false for each template
            // type
            for (TemplateType type : TemplateType.values()) {
                defaultTemplateRegistered.put(type, false);
            }

            // Get all template files from the resources
            Resource[] resources;
            try {
                ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
                resources = resolver.getResources("classpath:templates/template*.js");
            } catch (IOException e) {
                log.error("Failed to load template resources", e);
                throw e;
            }

            for (Resource resource : resources) {
                try {
                    String fileName = resource.getFilename();
                    if (fileName == null || !fileName.startsWith("template")) {
                        continue;
                    }

                    // Read the file content
                    String content = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

                    // Parse the header annotations
                    String name = extractAnnotation(content, "@name");
                    boolean internal = Boolean.parseBoolean(extractAnnotation(content, "@internal"));
                    String templateTypeStr = extractAnnotation(content, "@templateType");
                    boolean defaultTemplate = Boolean.parseBoolean(extractAnnotation(content, "@defaultTemplate"));
                    boolean readonly = Boolean.parseBoolean(extractAnnotation(content, "@readonly"));

                    // Convert templateType string to enum
                    TemplateType templateType = null;
                    try {
                        templateType = TemplateType.valueOf(templateTypeStr);
                    } catch (Exception e) {
                        log.warn("Invalid template type in file {}: {}", fileName, templateTypeStr);
                        continue;
                    }

                    // Determine the ID for the template
                    String templateId;
                    if (defaultTemplate && !defaultTemplateRegistered.get(templateType)) {
                        // Use the template type as ID for the first default template of each type
                        templateId = templateType.name();
                        defaultTemplateRegistered.put(templateType, true);
                    } else {
                        // Generate a random ID for non-default templates
                        templateId = createCustomUuid();
                    }

                    // Create and add the template
                    CodeTemplate template = new CodeTemplate(
                            templateId,
                            name,
                            templateType,
                            encode(content),
                            internal,
                            readonly,
                            defaultTemplate);

                    codeTemplates.put(templateId, template);
                    log.info("Loaded template: {} ({})", name, templateId);

                } catch (Exception e) {
                    log.error("Failed to process template file: {}", resource.getFilename(), e);
                }
            }
        } catch (Exception e) {
            log.error("Failed to initialize code templates", e);
        }

        configuration.setCodeTemplates(codeTemplates);
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
            // Log the error
            // logger.error("Failed to read mapping file", e);

            // You can return a default value
            return "{}"; // Empty JSON object

            // Or rethrow as an unchecked exception
            // throw new RuntimeException("Failed to read mapping file", e);
        }
    }

    /**
     * Encodes a given template string to Base64.
     * 
     * @param template The string to be encoded
     * @return Base64 encoded template as string
     */
    private String encode(String template) {
        if (template == null) {
            return "";
        }

        try {
            // Convert string to byte array
            byte[] templateBytes = template.getBytes(StandardCharsets.UTF_8);

            // Encode to Base64
            byte[] encodedBytes = Base64.getEncoder().encode(templateBytes);

            // Convert encoded bytes back to string
            return new String(encodedBytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            // Log exception or handle as appropriate for your application
            // logger.error("Failed to encode template", e);
            return "";
        }
    }

    public void saveServiceConfiguration(String tenant, final ServiceConfiguration configuration)
            throws JsonProcessingException {
        if (configuration == null) {
            return;
        }

        final String configurationJson = objectMapper.writeValueAsString(configuration);
        final OptionRepresentation optionRepresentation = OptionRepresentation.asOptionRepresentation(
                OPTION_CATEGORY_CONFIGURATION, OPTION_KEY_SERVICE_CONFIGURATION, configurationJson);
        tenantOptionApi.save(optionRepresentation);
    }

    public ServiceConfiguration getServiceConfiguration(String tenant) {
        final OptionPK option = new OptionPK();
        option.setCategory(OPTION_CATEGORY_CONFIGURATION);
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
                log.debug("Tenant {} - Returning service configuration found: {}:", tenant, rt.logPayload);
                log.debug("Tenant {} - Found connection configuration: {}", tenant, rt);
            } catch (SDKException exception) {
                log.warn("Tenant {} - No configuration found, returning empty element!", tenant);
                rt = initialize(tenant);
            } catch (Exception e) {
                String exceptionMsg = e.getCause() == null ? e.getMessage() : e.getCause().getMessage();
                String msg = String.format("Failed to convert service object. Error: %s",
                        exceptionMsg);
                log.warn(msg);
            }
            return rt;
        });
        return result;
    }

    public void deleteServiceConfigurations(String tenant) {
        OptionPK optionPK = new OptionPK(OPTION_CATEGORY_CONFIGURATION, OPTION_KEY_SERVICE_CONFIGURATION);
        tenantOptionApi.delete(optionPK);
    }

    public ServiceConfiguration initialize(String tenant) {
        ServiceConfiguration configuration = new ServiceConfiguration();
        try {
            saveServiceConfiguration(tenant, configuration);
        } catch (JsonProcessingException e) {
            log.warn("Tenant {} - failed to initializes ServiceConfiguration!", tenant);
            e.printStackTrace();
        }
        return configuration;
    }

    private static String createCustomUuid() {
        return SECURE_RANDOM.ints(UUID_LENGTH, 0, 36)
                .mapToObj(i -> Character.toString(i < 10 ? '0' + i : 'a' + i - 10))
                .collect(Collectors.joining());
    }
}