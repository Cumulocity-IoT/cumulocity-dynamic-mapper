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
                    String description = extractAnnotation(content, "@description");
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
                            description,
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

    /*
     * method should update the annotation in the code header with the values that
     * are defined in the codeTemplate
     * extractAnnotation(content, "@internal");
     * extractAnnotation(content, "@templateType");
     * extractAnnotation(content, "@defaultTemplate");
     * extractAnnotation(content, "@readonly");
     */
    /**
     * Updates the annotation values in the code header to match the values in the
     * CodeTemplate object.
     * Ensures that the header's metadata is consistent with the object's
     * properties.
     * 
     * @param codeTemplate The CodeTemplate object whose header needs to be updated
     */
    public void rectifyHeaderInCodeTemplate(CodeTemplate codeTemplate) {
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

            // Extract the header section
            int headerEnd = decodedCode.indexOf("*/");
            if (headerEnd == -1) {
                // No proper header found, create a new one
                decodedCode = createNewHeader(codeTemplate) + decodedCode;
            } else {
                headerEnd += 2; // Include the */ in the header
                String header = decodedCode.substring(0, headerEnd);
                String codeBody = decodedCode.substring(headerEnd);

                // Update annotations in the header
                header = updateAnnotation(header, "@name", codeTemplate.name);
                header = updateAnnotation(header, "@description", "Please add a description");
                header = updateAnnotation(header, "@templateType", codeTemplate.templateType.name());
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
        header.append(" * @defaultTemplate ").append(codeTemplate.defaultTemplate).append("\n");
        header.append(" * @internal ").append(codeTemplate.internal).append("\n");
        header.append(" * @readonly ").append(codeTemplate.readonly).append("\n");
        header.append(" */\n\n");
        return header.toString();
    }

    /**
     * Updates a specific annotation in the header text.
     * If the annotation exists, it updates its value; otherwise, it adds the
     * annotation.
     * 
     * @param header     The header text to modify
     * @param annotation The annotation to update (e.g., "@name")
     * @param value      The new value for the annotation
     * @return The updated header text
     */
    /**
     * Updates an annotation in the content with a new value.
     * If the annotation doesn't exist, it will be added at the beginning of the
     * file.
     * Ensures exactly one space between the annotation and its value.
     * 
     * @param content    The content to update
     * @param annotation The annotation to update
     * @param value      The new value for the annotation
     * @return The updated content
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
                    // Insert before the end of the comment
                    return content.substring(0, commentEndIndex)
                            + "\n * " + annotation + " " + value
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
     * Decodes a Base64 encoded string back to a regular string.
     * 
     * @param encodedString The Base64 encoded string
     * @return The decoded string
     */
    private String decode(String encodedString) {
        if (encodedString == null || encodedString.isEmpty()) {
            return "";
        }

        try {
            byte[] decodedBytes = Base64.getDecoder().decode(encodedString);
            return new String(decodedBytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("Failed to decode string", e);
            return "";
        }
    }
}