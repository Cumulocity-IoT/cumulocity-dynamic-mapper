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

package dynamic.mapper.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import jakarta.validation.Valid;
import dynamic.mapper.configuration.CodeTemplate;
import dynamic.mapper.configuration.CodeTemplateService;
import dynamic.mapper.configuration.ConnectorConfiguration;
import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.connector.core.ConnectorSpecification;
import dynamic.mapper.connector.core.client.ConnectorType;
import dynamic.mapper.connector.core.client.ConnectionTestResult;
import dynamic.mapper.connector.core.registry.ConnectorRegistry;
import dynamic.mapper.core.ConnectorLifecycleService;
import dynamic.mapper.core.ServiceRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.cumulocity.microservice.context.ContextService;
import com.cumulocity.microservice.context.credentials.UserCredentials;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import dynamic.mapper.model.Feature;
import dynamic.mapper.configuration.ConnectorConfigurationService;
import dynamic.mapper.mapping.MappingService;
import dynamic.mapper.configuration.ServiceConfigurationService;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.info.License;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.tags.Tag;

@Slf4j
@RequiredArgsConstructor
@RequestMapping("/configuration")
@RestController
@SecurityScheme(type = SecuritySchemeType.HTTP, name = "basicAuth", scheme = "basic", in = SecuritySchemeIn.HEADER, description = "Basic Authentication using Cumulocity IoT credentials")
@OpenAPIDefinition(info = @Info(title = "Dynamic Mapper API", version = "v1.0", description = """
        REST API for managing Dynamic Mapper configurations including connectors, mappings,
        service settings, and code templates. The Dynamic Mapper enables bi-directional
        data transformation between external systems and Cumulocity IoT.

        **Key Features:**
        - Connector management (MQTT, HTTP, TCP, etc.)
        - Service configuration and feature flags
        - Code template management for custom processing
        - Multi-tenant support with role-based access control
        """, contact = @Contact(name = "Cumulocity Dynamic Mapper Team", email = "support@cumulocity.com"), license = @License(name = "Apache 2.0", url = "https://www.apache.org/licenses/LICENSE-2.0")), security = {
        @SecurityRequirement(name = "basicAuth") })
@Tag(name = "Configuration Management", description = "Core configuration endpoints for connectors, service settings, and code templates")
public class ConfigurationController {

    private final ConnectorRegistry connectorRegistry;
    private final MappingService mappingService;
    private final ConnectorConfigurationService connectorConfigurationService;
    private final ServiceConfigurationService serviceConfigurationService;
    private final CodeTemplateService codeTemplateService;
    private final ConnectorLifecycleService connectorLifecycleService;
    private final ContextService<UserCredentials> contextService;
    private final ServiceRegistry serviceRegistry;
    private final ObjectMapper objectMapper;

    @Value("${APP.externalExtensionsEnabled}")
    private Boolean externalExtensionsEnabled;

    @Operation(summary = "Get feature flags", description = """
            Returns feature flags indicating which functionality is available for the current tenant and user.
            This is useful for UI applications to conditionally enable/disable features.

            **Feature Flags:**
            - `outputMappingEnabled`: Whether outbound mapping is available
            - `externalExtensionsEnabled`: Whether external processor extensions are supported
            - `userHasMappingCreateRole`: Whether user can create/modify mappings
            - `userHasMappingAdminRole`: Whether user has administrative privileges
            - `pulsarAvailable`: Whether pulsar is available
            - `deviceIsolationMQTTServiceEnabled`: Whether device isolation for Cumulocity MQTT Service is enabled
            """)
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Feature flags retrieved successfully", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Feature.class))),
            @ApiResponse(responseCode = "500", description = "Internal server error", content = @Content)
    })
    @GetMapping(value = "/feature", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Feature> getFeatures() {
        String tenant = contextService.getContext().getTenant();
        ServiceConfiguration serviceConfiguration = serviceConfigurationService.getServiceConfiguration(tenant);
        log.debug("{} - Get feature", tenant);
        Feature feature = new Feature();
        feature.setOutputMappingEnabled(serviceConfiguration.getOutboundMappingEnabled());
        feature.setExternalExtensionsEnabled(externalExtensionsEnabled);
        feature.setUserHasMappingCreateRole(Utils.userHasMappingCreateRole());
        feature.setUserHasMappingAdminRole(Utils.userHasMappingAdminRole());
        feature.setPulsarAvailable(serviceRegistry.isPulsarAvailable(tenant));
        feature.setDeviceIsolationMQTTServiceEnabled(serviceConfiguration.getDeviceIsolationMQTTServiceEnabled());
        feature.setSuppressDeprecationWarning(serviceConfiguration.getSuppressDeprecationWarning());
        feature.setAcceptedDeprecationNotice(serviceConfiguration.getAcceptedDeprecationNotice());
        return new ResponseEntity<Feature>(feature, HttpStatus.OK);
    }

    @Operation(summary = "Get connector specifications", description = """
            Returns all available connector specifications with their supported properties and capabilities.
            Use this endpoint to discover which connector types are available and their configuration requirements.
            """)
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Connector specifications retrieved successfully", content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = ConnectorSpecification.class)))),
            @ApiResponse(responseCode = "500", description = "Internal server error", content = @Content)
    })
    @GetMapping(value = "/connector/specifications", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<ConnectorSpecification>> getConnectorSpecifications() {
        String tenant = contextService.getContext().getTenant();
        List<ConnectorSpecification> connectorConfigurations = new ArrayList<>();
        log.debug("{} - Get connector specifications", tenant);
        Map<ConnectorType, ConnectorSpecification> spec = connectorRegistry
                .getConnectorSpecifications();
        // Iterate over all connectors
        for (ConnectorType connectorType : spec.keySet()) {
            connectorConfigurations.add(spec.get(connectorType));
        }
        return ResponseEntity.ok(connectorConfigurations);
    }

    @Operation(summary = "Create connector configuration", description = """
            Creates a new connector configuration for the specified type. The connector will be created
            in disabled state and must be explicitly enabled through a separate operation.

            **Note:** HTTP connectors cannot be created through this endpoint as they are system-managed.

            **Security:** Requires `ROLE_DYNAMIC_MAPPER_ADMIN`
            """)
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Connector configuration created successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid request or unsupported connector type", content = @Content),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content),
            @ApiResponse(responseCode = "500", description = "Internal server error", content = @Content)
    })
    @PreAuthorize("hasRole('ROLE_DYNAMIC_MAPPER_ADMIN')")
    @PostMapping(value = "/connector/instance", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<HttpStatus> createConnectorConfiguration(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "Connector configuration to be created", required = true, content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConnectorConfiguration.class), examples = @ExampleObject(name = "MQTT Connector", description = "Example MQTT connector configuration", value = """
                    {
                      "identifier": "mqtt-prod-01",
                      "connectorType": "MQTT",
                      "name": "Production MQTT Broker",
                      "description": "Connection to production MQTT broker",
                      "enabled": false,
                      "properties": {
                        "mqttHost": "mqtt.example.com",
                        "mqttPort": 1883,
                        "clientId": "dynamic_mapper_client1",
                        "username": "mqtt_user",
                        "password": "mqtt_password"
                      }
                    }
                    """))) @Valid @RequestBody ConnectorConfiguration configuration) {
        String tenant = contextService.getContext().getTenant();
        try {
            connectorLifecycleService.createConnectorConfiguration(tenant, configuration);
        } catch (ConnectorLifecycleService.ConnectorOperationException ex) {
            throw Utils.toStatusException(ex);
        }
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @Operation(summary = "Get connector configurations", description = """
            Returns a list of all connector configurations for the current tenant.
            Sensitive properties (passwords, tokens) are masked in the response.
            Optionally filter results by name using wildcards (* supported).
            """)
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "List of connector configurations", content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = ConnectorConfiguration.class)))),
            @ApiResponse(responseCode = "500", description = "Internal server error", content = @Content)
    })
    @GetMapping(value = "/connector/instance", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<ConnectorConfiguration>> getConnectionConfigurations(
            @Parameter(description = "Filter by connector name (wildcards * supported)", example = "mqtt*") @RequestParam(required = false) String name) {
        String tenant = contextService.getContext().getTenant();
        log.debug("{} - Get connector instances", tenant);

        try {
            // Convert wildcard pattern to regex pattern if name is provided
            Pattern pattern = null;
            if (name != null) {
                // Escape all special regex characters except *
                String escapedName = Pattern.quote(name).replace("*", ".*");
                // Remove the quotes added by Pattern.quote() at the start and end
                escapedName = escapedName.substring(2, escapedName.length() - 2);
                pattern = Pattern.compile("^" + escapedName + "$");
            }

            List<ConnectorConfiguration> configurations = connectorConfigurationService
                    .getConnectorConfigurations(tenant);
            List<ConnectorConfiguration> modifiedConfigs = new ArrayList<>();

            // Remove sensitive data before sending to UI
            for (ConnectorConfiguration config : configurations) {
                ConnectorSpecification connectorSpecification = connectorRegistry
                        .getConnectorSpecification(config.getConnectorType());
                ConnectorConfiguration cleanedConfig = config.getCleanedConfig(connectorSpecification);

                if (pattern == null || pattern.matcher(cleanedConfig.getName()).matches()) {
                    modifiedConfigs.add(cleanedConfig);
                }
            }
            return ResponseEntity.ok(modifiedConfigs);
        } catch (Exception ex) {
            log.error("{} - Error getting connector instances", tenant, ex);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getLocalizedMessage());
        }
    }

    @Operation(summary = "Get connector configuration", description = "Returns the connector configuration for the given identifier. Sensitive properties are masked in the response.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Connector configuration found", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConnectorConfiguration.class))),
            @ApiResponse(responseCode = "404", description = "Connector configuration not found", content = @Content),
            @ApiResponse(responseCode = "500", description = "Internal server error", content = @Content)
    })
    @GetMapping(value = "/connector/instance/{identifier}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ConnectorConfiguration> getConnectionConfiguration(
            @Parameter(description = "The unique identifier of the connector", example = "mqtt-prod-01") @PathVariable String identifier) {
        String tenant = contextService.getContext().getTenant();
        log.debug("{} - Get connector instance: {}", tenant, identifier);

        try {
            List<ConnectorConfiguration> configurations = connectorConfigurationService
                    .getConnectorConfigurations(tenant);
            ConnectorConfiguration modifiedConfig = null;

            // Remove sensitive data before sending to UI
            for (ConnectorConfiguration config : configurations) {
                if (config.getIdentifier().equals(identifier)) {
                    ConnectorSpecification connectorSpecification = connectorRegistry
                            .getConnectorSpecification(config.getConnectorType());
                    ConnectorConfiguration cleanedConfig = config.getCleanedConfig(connectorSpecification);
                    modifiedConfig = cleanedConfig;
                }
            }
            if (modifiedConfig == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Connector instance not found");
            }
            return ResponseEntity.ok(modifiedConfig);
        } catch (ResponseStatusException ex) {
            // Re-throw ResponseStatusException as is
            log.error("{} - Connector instance not found: {}", tenant, identifier);
            throw ex;
        } catch (Exception ex) {
            log.error("{} - Error getting connector instance: {}", tenant, identifier, ex);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getLocalizedMessage());
        }
    }

    @Operation(summary = "Delete connector configuration", description = """
            Deletes the connector configuration for the given identifier.

            **Prerequisites:**
            - The connector must be disabled before it can be deleted
            - HTTP connectors cannot be deleted as they are system-managed

            **Security:** Requires `ROLE_DYNAMIC_MAPPER_ADMIN`
            """)
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Connector configuration deleted successfully", content = @Content),
            @ApiResponse(responseCode = "400", description = "Connector is enabled or cannot be deleted", content = @Content),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content),
            @ApiResponse(responseCode = "404", description = "Connector configuration not found", content = @Content),
            @ApiResponse(responseCode = "500", description = "Internal server error", content = @Content)
    })
    @PreAuthorize("hasRole('ROLE_DYNAMIC_MAPPER_ADMIN')")
    @DeleteMapping(value = "/connector/instance/{identifier}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> deleteConnectionConfiguration(
            @Parameter(description = "The unique identifier of the connector", example = "mqtt-prod-01") @PathVariable String identifier) {
        String tenant = contextService.getContext().getTenant();
        log.info("{} - Delete connection instance {}", tenant, identifier);
        try {
            connectorLifecycleService.deleteConnector(tenant, identifier);
        } catch (ConnectorLifecycleService.ConnectorOperationException ex) {
            if (ex.getReason() == ConnectorLifecycleService.Reason.FAILED) {
                throw Utils.toStatusException(ex);
            }
            // a missing or protected connector is answered with the reason as the body
            return ResponseEntity.status(Utils.statusOf(ex)).body(ex.getMessage());
        }
        return ResponseEntity.status(HttpStatus.OK).body(identifier);
    }

    @Operation(summary = "Test connector configuration", description = """
            Verifies connectivity and authentication for the given (possibly unsaved) connector configuration
            using a throwaway client; nothing is saved, registered or subscribed.

            Sensitive properties sent as "****" are resolved from the stored configuration with the same identifier.

            **Security:** Requires `ROLE_DYNAMIC_MAPPER_ADMIN`
            """)
    @PreAuthorize("hasRole('ROLE_DYNAMIC_MAPPER_ADMIN')")
    @PostMapping(value = "/connector/test", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ConnectionTestResult> testConnectionConfiguration(
            @Valid @RequestBody ConnectorConfiguration configuration) {
        String tenant = contextService.getContext().getTenant();
        try {
            return ResponseEntity.ok(connectorLifecycleService.testConnection(tenant, configuration));
        } catch (ConnectorLifecycleService.ConnectorOperationException ex) {
            throw Utils.toStatusException(ex);
        }
    }

    @Operation(summary = "Update connector configuration", description = """
            Updates the connector configuration for the given identifier.

            **Sensitive Properties:** Properties marked as sensitive (like passwords) can be:
            - Updated by providing new values
            - Left unchanged by sending "****" as the value

            **Security:** Requires `ROLE_DYNAMIC_MAPPER_ADMIN`
            """)
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Connector configuration updated successfully", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConnectorConfiguration.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request or unsupported connector type", content = @Content),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content),
            @ApiResponse(responseCode = "500", description = "Internal server error", content = @Content)
    })
    @PreAuthorize("hasRole('ROLE_DYNAMIC_MAPPER_ADMIN')")
    @PutMapping(value = "/connector/instance/{identifier}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ConnectorConfiguration> updateConnectionConfiguration(
            @Parameter(description = "The unique identifier of the connector", example = "mqtt-prod-01") @PathVariable String identifier,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "Updated connector configuration", required = true, content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConnectorConfiguration.class), examples = @ExampleObject(name = "Updated MQTT Connector", description = "Example update with masked password", value = """
                    {
                      "identifier": "mqtt-prod-01",
                      "connectorType": "MQTT",
                      "name": "Production MQTT Broker",
                      "description": "Updated connection to production MQTT broker",
                      "enabled": true,
                      "properties": {
                        "mqttHost": "new-mqtt.example.com",
                        "mqttPort": 1883,
                        "clientId": "dynamic_mapper_client1",
                        "username": "new_mqtt_user",
                        "password": "****"
                      }
                    }
                    """))) @Valid @RequestBody ConnectorConfiguration configuration) {
        String tenant = contextService.getContext().getTenant();
        log.info("{} - Update connection instance {}", tenant, identifier);
        try {
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(connectorLifecycleService.updateConnectorConfiguration(tenant, identifier, configuration));
        } catch (ConnectorLifecycleService.ConnectorOperationException ex) {
            throw Utils.toStatusException(ex);
        }
    }

    @Operation(summary = "Get service configuration", description = "Retrieves the service configuration for the current tenant including feature flags, cache settings, and operational parameters.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Service configuration retrieved successfully", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ServiceConfiguration.class))),
            @ApiResponse(responseCode = "404", description = "Service configuration not found", content = @Content),
            @ApiResponse(responseCode = "500", description = "Internal server error", content = @Content)
    })
    @GetMapping(value = "/service", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ServiceConfiguration> getServiceConfiguration() {
        String tenant = contextService.getContext().getTenant();
        // log.info("{} - Get service configuration", tenant);

        try {
            final ServiceConfiguration configuration = serviceConfigurationService.getServiceConfiguration(tenant);
            if (configuration == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Service connection not available");
            }
            return new ResponseEntity<ServiceConfiguration>(configuration, HttpStatus.OK);
        } catch (Exception ex) {
            log.error("{} - Error getting service configuration", tenant, ex);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getLocalizedMessage());
        }
    }

    @Operation(summary = "Update service configuration", description = """
            Updates the service configuration for the current tenant.

            **Important:** Changing outbound mapping settings will affect notification subscriptions
            and may trigger connector reconnections.

            **Security:** Requires `ROLE_DYNAMIC_MAPPER_ADMIN`
            """)
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Service configuration updated successfully", content = @Content),
            @ApiResponse(responseCode = "400", description = "Invalid service configuration", content = @Content),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content),
            @ApiResponse(responseCode = "500", description = "Internal server error", content = @Content)
    })
    @PreAuthorize("hasRole('ROLE_DYNAMIC_MAPPER_ADMIN')")
    @PutMapping(value = "/service", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<HttpStatus> updateServiceConfiguration(
            @RequestBody Map<String, Object> updates) {
        String tenant = contextService.getContext().getTenant();
        log.info("{} - Update service configuration with partial updates: {}", tenant, updates);
        try {
            connectorLifecycleService.updateServiceConfiguration(tenant, updates);
        } catch (ConnectorLifecycleService.ConnectorOperationException ex) {
            throw Utils.toStatusException(ex);
        }
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @Operation(summary = "Get code template", description = "Returns the code template for the given ID. Code templates provide reusable JavaScript code for custom processing in mappings.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Code template found", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CodeTemplate.class))),
            @ApiResponse(responseCode = "404", description = "Code template not found", content = @Content),
            @ApiResponse(responseCode = "500", description = "Internal server error", content = @Content)
    })
    @GetMapping(value = "/code/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CodeTemplate> getCodeTemplate(
            @Parameter(description = "The unique ID of the code template", example = "shared") @PathVariable String id) {
        String tenant = contextService.getContext().getTenant();
        log.debug("{} - Get code template: {}", tenant, id);
        try {
            return new ResponseEntity<>(codeTemplateService.get(tenant, id), HttpStatus.OK);
        } catch (CodeTemplateService.CodeTemplateException ex) {
            return codeTemplateFailure(ex);
        }
    }

    @Operation(summary = "Delete code template", description = """
            Deletes the code template for the given ID.

            **Note:** Internal (system) templates cannot be deleted.

            **Security:** Requires `ROLE_DYNAMIC_MAPPER_ADMIN`
            """)
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Code template deleted successfully", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CodeTemplate.class))),
            @ApiResponse(responseCode = "406", description = "Deletion of internal templates is not allowed", content = @Content),
            @ApiResponse(responseCode = "404", description = "Code template not found", content = @Content),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content),
            @ApiResponse(responseCode = "500", description = "Internal server error", content = @Content)
    })
    @PreAuthorize("hasRole('ROLE_DYNAMIC_MAPPER_ADMIN')")
    @DeleteMapping(value = "/code/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CodeTemplate> deleteCodeTemplate(
            @Parameter(description = "The unique ID of the code template", example = "custom-template") @PathVariable String id) {
        String tenant = contextService.getContext().getTenant();
        log.debug("{} - Delete code template: {}", tenant, id);
        try {
            return new ResponseEntity<>(codeTemplateService.delete(tenant, id), HttpStatus.OK);
        } catch (CodeTemplateService.CodeTemplateException ex) {
            return codeTemplateFailure(ex);
        }
    }

    @Operation(summary = "Get all code templates", description = "Returns all code templates for the current tenant including both system and custom templates.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Code templates retrieved successfully", content = @Content(mediaType = "application/json", schema = @Schema(type = "object", description = "Map of template IDs to CodeTemplate objects"))),
            @ApiResponse(responseCode = "500", description = "Internal server error", content = @Content)
    })
    @GetMapping(value = "/code", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, CodeTemplate>> getCodeTemplates() {
        String tenant = contextService.getContext().getTenant();
        log.debug("{} - Get code templates", tenant);
        try {
            return new ResponseEntity<>(codeTemplateService.getAll(tenant), HttpStatus.OK);
        } catch (CodeTemplateService.CodeTemplateException ex) {
            throw toStatusException(ex);
        }
    }

    @Operation(summary = "Update code template", description = """
            Updates the code template for the given ID with new JavaScript code. Creates it when no
            template is stored under that ID.

            **Note:** Read-only templates (e.g. `SYSTEM`) cannot be modified. `SYSTEM` is
            framework-owned and re-loaded from the shipped version on every startup.

            **Security:** Requires `ROLE_DYNAMIC_MAPPER_ADMIN`
            """)
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Code template updated successfully", content = @Content),
            @ApiResponse(responseCode = "400", description = "Path ID and body ID do not match", content = @Content),
            @ApiResponse(responseCode = "406", description = "Modification of read-only templates is not allowed", content = @Content),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content),
            @ApiResponse(responseCode = "500", description = "Internal server error", content = @Content)
    })
    @PreAuthorize("hasRole('ROLE_DYNAMIC_MAPPER_ADMIN')")
    @PutMapping(value = "/code/{id}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<HttpStatus> updateCodeTemplate(
            @Parameter(description = "The unique ID of the code template", example = "custom-template") @PathVariable String id,
            @Valid @RequestBody CodeTemplate codeTemplate) {
        String tenant = contextService.getContext().getTenant();
        try {
            codeTemplateService.update(tenant, id, codeTemplate);
        } catch (CodeTemplateService.CodeTemplateException ex) {
            throw toStatusException(ex);
        }
        return new ResponseEntity<HttpStatus>(HttpStatus.OK);
    }

    @Operation(summary = "Create code template", description = """
            Creates a new code template for the current tenant.

            **Security:** Requires `ROLE_DYNAMIC_MAPPER_ADMIN`
            """)
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Code template created successfully", content = @Content),
            @ApiResponse(responseCode = "409", description = "Template with this ID already exists", content = @Content),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions", content = @Content),
            @ApiResponse(responseCode = "500", description = "Internal server error", content = @Content)
    })
    @PreAuthorize("hasRole('ROLE_DYNAMIC_MAPPER_ADMIN')")
    @PostMapping(value = "/code", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<HttpStatus> createCodeTemplate(
            @Valid @RequestBody CodeTemplate codeTemplate) {
        String tenant = contextService.getContext().getTenant();
        try {
            codeTemplateService.create(tenant, codeTemplate);
        } catch (CodeTemplateService.CodeTemplateException ex) {
            throw toStatusException(ex);
        }
        return new ResponseEntity<HttpStatus>(HttpStatus.CREATED);
    }

    /** A missing template answers with an empty 404; every other failure becomes an error response. */
    private <T> ResponseEntity<T> codeTemplateFailure(CodeTemplateService.CodeTemplateException ex) {
        if (ex.getReason() == CodeTemplateService.Reason.NOT_FOUND) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
        throw toStatusException(ex);
    }

    private ResponseStatusException toStatusException(CodeTemplateService.CodeTemplateException ex) {
        HttpStatus status = switch (ex.getReason()) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case NOT_ALLOWED -> HttpStatus.NOT_ACCEPTABLE;
            case ID_MISMATCH -> HttpStatus.BAD_REQUEST;
            case ALREADY_EXISTS -> HttpStatus.CONFLICT;
            case FAILED -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        return new ResponseStatusException(status, ex.getMessage());
    }

    // TEST ONLY — not exposed in OpenAPI docs
    @Hidden
    // Writes the tenant's ServiceConfiguration, which the permission model reserves for admins.
    @PreAuthorize("hasRole('ROLE_DYNAMIC_MAPPER_ADMIN')")
    @PutMapping(value = "/test/deprecation-notice", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> setAcceptedDeprecationNotice(
            @RequestParam String version) {
        String tenant = contextService.getContext().getTenant();
        log.info("{} - [TEST] Setting acceptedDeprecationNotice to '{}'", tenant, version);
        try {
            ServiceConfiguration existing = serviceConfigurationService.getServiceConfiguration(tenant);
            existing.setAcceptedDeprecationNotice(version);
            serviceConfigurationService.saveServiceConfiguration(tenant, existing);
            serviceRegistry.addServiceConfiguration(tenant, existing);
            return ResponseEntity.ok("acceptedDeprecationNotice set to: " + version);
        } catch (Exception ex) {
            log.error("{} - [TEST] Error setting acceptedDeprecationNotice", tenant, ex);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getLocalizedMessage());
        }
    }
}
