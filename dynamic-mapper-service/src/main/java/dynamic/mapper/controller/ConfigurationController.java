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

package dynamic.mapper.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.regex.Pattern;

import jakarta.validation.Valid;
import dynamic.mapper.configuration.CodeTemplate;
import dynamic.mapper.configuration.ConnectorConfiguration;
import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.connector.core.ConnectorSpecification;
import dynamic.mapper.connector.core.client.ConnectorType;
import dynamic.mapper.connector.core.registry.ConnectorRegistry;
import dynamic.mapper.core.*;

import org.springframework.beans.factory.annotation.Autowired;
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
import com.fasterxml.jackson.core.JsonProcessingException;

import lombok.extern.slf4j.Slf4j;
import dynamic.mapper.model.Feature;
import dynamic.mapper.service.ConnectorConfigurationService;
import dynamic.mapper.service.MappingService;
import dynamic.mapper.service.ServiceConfigurationService;
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

    @Autowired
    ConnectorRegistry connectorRegistry;

    @Autowired
    MappingService mappingService;

    @Autowired
    ConnectorConfigurationService connectorConfigurationService;

    @Autowired
    ServiceConfigurationService serviceConfigurationService;

    @Autowired
    BootstrapService bootstrapService;

    @Autowired
    C8YAgent c8YAgent;

    @Autowired
    private ContextService<UserCredentials> contextService;

    @Autowired
    private ConfigurationRegistry configurationRegistry;

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
        if (configuration.getConnectorType().equals(ConnectorType.HTTP)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Can't create a HttpConnector!");
        }
        // Remove sensitive data before printing to log
        ConnectorSpecification connectorSpecification = connectorRegistry
                .getConnectorSpecification(configuration.getConnectorType());
        ConnectorConfiguration clonedConfig = configuration.getCleanedConfig(connectorSpecification);
        log.info("{} - Post Connector configuration: {}", tenant, clonedConfig.toString());
        try {
            connectorConfigurationService.saveConnectorConfiguration(configuration);
            return ResponseEntity.status(HttpStatus.CREATED).build();
        } catch (Exception ex) {
            log.error("{} - Error creating connector instance", tenant, ex);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getLocalizedMessage());
        }
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
            @ApiResponse(responseCode = "500", description = "Internal server error", content = @Content)
    })
    @PreAuthorize("hasRole('ROLE_DYNAMIC_MAPPER_ADMIN')")
    @DeleteMapping(value = "/connector/instance/{identifier}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> deleteConnectionConfiguration(
            @Parameter(description = "The unique identifier of the connector", example = "mqtt-prod-01") @PathVariable String identifier) {
        String tenant = contextService.getContext().getTenant();
        log.info("{} - Delete connection instance {}", tenant, identifier);
        try {
            ConnectorConfiguration configuration = connectorConfigurationService.getConnectorConfiguration(identifier,
                    tenant);
            if (configuration.getEnabled())
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body("Can't delete an enabled connector! Disable connector first.");
            if (configuration.getConnectorType().equals(ConnectorType.HTTP)) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Can't delete a HttpConnector!");
            }
            // make sure the connector is disconnected before it is deleted.
            bootstrapService.disableConnector(tenant, identifier);
            connectorConfigurationService.deleteConnectorConfiguration(identifier);
            mappingService.removeConnectorFromDeploymentMap(tenant, identifier);
            connectorRegistry.removeClientFromStatusMap(tenant, identifier);
            bootstrapService.shutdownAndRemoveConnector(tenant, identifier);
        } catch (Exception ex) {
            log.error("{} - Error deleting connector instance: {}", tenant, identifier, ex);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getLocalizedMessage());
        }
        return ResponseEntity.status(HttpStatus.OK).body(identifier);
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
        // make sure we are using the correct identifier
        configuration.setIdentifier(identifier);
        // Remove sensitive data before printing to log
        ConnectorSpecification connectorSpecification = connectorRegistry
                .getConnectorSpecification(configuration.getConnectorType());
        ConnectorConfiguration clonedConfig = configuration.getCleanedConfig(connectorSpecification);
        log.info("{} - Post Connector configuration: {}", tenant, clonedConfig.toString());
        try {
            // check if password filed was touched, e.g. != "****", then use password from
            // new payload, otherwise copy password from previously saved configuration
            ConnectorConfiguration originalConfiguration = connectorConfigurationService
                    .getConnectorConfiguration(configuration.getIdentifier(), tenant);

            for (String property : configuration.getProperties().keySet()) {
                if (connectorSpecification.isPropertySensitive(property)
                        && configuration.getProperties().get(property).equals("****")) {
                    // retrieve the existing value
                    log.info(
                            "{} - Copy property {} from existing configuration, since it was not touched and is sensitive.",
                            tenant, property);
                    configuration.getProperties().put(property,
                            originalConfiguration.getProperties().get(property));
                }
            }
            connectorConfigurationService.saveConnectorConfiguration(configuration);
        } catch (Exception ex) {
            log.error("{} - Error updating connector instance: {}", tenant, identifier, ex);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getLocalizedMessage());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(configuration);
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
            @Valid @RequestBody ServiceConfiguration serviceConfiguration) {
        String tenant = contextService.getContext().getTenant();
        ServiceConfiguration currentServiceConfiguration = configurationRegistry.getServiceConfiguration(tenant);

        log.info("{} - Update service configuration: {}", tenant, serviceConfiguration.toString());
        // existing code templates
        ServiceConfiguration mergeServiceConfiguration = serviceConfigurationService.getServiceConfiguration(tenant);
        Map<String, CodeTemplate> codeTemplates = mergeServiceConfiguration.getCodeTemplates();

        try {
            serviceConfiguration.setCodeTemplates(codeTemplates);
            serviceConfigurationService.saveServiceConfiguration(tenant, serviceConfiguration);
            if (!serviceConfiguration.getOutboundMappingEnabled()
                    && configurationRegistry.getNotificationSubscriber().getDeviceConnectionStatus(tenant) != null
                    && configurationRegistry.getNotificationSubscriber().getDeviceConnectionStatus(tenant) == 200) {
                configurationRegistry.getNotificationSubscriber().disconnect(tenant);
            } else if (configurationRegistry.getNotificationSubscriber().getDeviceConnectionStatus(tenant) == null
                    || configurationRegistry.getNotificationSubscriber().getDeviceConnectionStatus(tenant) != null
                            && configurationRegistry.getNotificationSubscriber()
                                    .getDeviceConnectionStatus(tenant) != 200) {

                // Test if OutboundMapping is switched on
                if (serviceConfiguration.getOutboundMappingEnabled()
                        && !currentServiceConfiguration.getOutboundMappingEnabled()) {
                    List<ConnectorConfiguration> connectorConfigurationList = connectorConfigurationService
                            .getConnectorConfigurations(tenant);
                    for (ConnectorConfiguration connectorConfiguration : connectorConfigurationList) {
                        if (bootstrapService.initializeConnectorByConfiguration(connectorConfiguration,
                                serviceConfiguration,
                                tenant) != null) {
                            Future<?> future = bootstrapService
                                    .initializeConnectorByConfiguration(connectorConfiguration, serviceConfiguration,
                                            tenant);
                            if (future != null) {
                                // You could handle the future asynchronously if needed
                                // For example, you could submit a task to a thread pool to handle completion
                            }
                        }
                        // Optionally add error handling in a separate thread if needed
                    }
                    configurationRegistry.getNotificationSubscriber().initializeDeviceClient(tenant);
                    configurationRegistry.getNotificationSubscriber().initializeManagementClient(tenant);
                }
            }

            configurationRegistry.addServiceConfiguration(tenant, serviceConfiguration);
            return ResponseEntity.status(HttpStatus.CREATED).build();
        } catch (Exception ex) {
            log.error("{} - Error updating service configuration", tenant, ex);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getLocalizedMessage());
        }
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
        ServiceConfiguration serviceConfiguration = serviceConfigurationService.getServiceConfiguration(tenant);
        log.debug("{} - Get code template: {}", tenant, id);

        Map<String, CodeTemplate> codeTemplates = serviceConfiguration.getCodeTemplates();
        if (codeTemplates == null || codeTemplates.isEmpty()) {
            // Initialize code templates from properties if not already set
            serviceConfigurationService.initCodeTemplates(serviceConfiguration, false);
            codeTemplates = serviceConfiguration.getCodeTemplates();

            try {
                serviceConfigurationService.saveServiceConfiguration(tenant, serviceConfiguration);
                configurationRegistry.addServiceConfiguration(tenant, serviceConfiguration);
            } catch (JsonProcessingException ex) {
                log.error("{} - Error saving service configuration with code templates: {}", tenant, ex);
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getLocalizedMessage());
            }
        }

        CodeTemplate result = codeTemplates.get(id);
        if (result == null) {
            // Template not found - return 404 Not Found
            log.warn("{} - Code template with ID [{}] not found", tenant, id);
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        } else {
            // Template exists - return it with 200 OK
            return new ResponseEntity<>(result, HttpStatus.OK);
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
        ServiceConfiguration serviceConfiguration = serviceConfigurationService.getServiceConfiguration(tenant);
        log.debug("{} - Delete code template: {}", tenant, id);

        Map<String, CodeTemplate> codeTemplates = serviceConfiguration.getCodeTemplates();
        if (codeTemplates == null || codeTemplates.isEmpty()) {
            // Initialize code templates from properties if not already set
            serviceConfigurationService.initCodeTemplates(serviceConfiguration, false);
            codeTemplates = serviceConfiguration.getCodeTemplates();

            try {
                serviceConfigurationService.saveServiceConfiguration(tenant, serviceConfiguration);
                configurationRegistry.addServiceConfiguration(tenant, serviceConfiguration);
            } catch (JsonProcessingException ex) {
                log.error("{} - Error saving service configuration with code templates: {}", tenant, ex);
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getLocalizedMessage());
            }
        }
        CodeTemplate result;
        try {
            result = codeTemplates.get(id);
            if (result.internal) {
                throw new ResponseStatusException(HttpStatus.NOT_ACCEPTABLE,
                        "Deletion of internal templates not allowed");
            }

            result = codeTemplates.remove(id);
            serviceConfigurationService.saveServiceConfiguration(tenant, serviceConfiguration);
        } catch (Exception ex) {
            log.error("{} - Error updating code template [{}]", tenant, id, ex);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getLocalizedMessage());
        }
        configurationRegistry.addServiceConfiguration(tenant, serviceConfiguration);
        if (result == null) {
            // Template not found - return 404 Not Found
            log.warn("{} - Code template with ID [{}] not found", tenant, id);
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        } else {
            // Template exists - return it with 200 OK
            return new ResponseEntity<>(result, HttpStatus.OK);
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
        ServiceConfiguration serviceConfiguration = serviceConfigurationService.getServiceConfiguration(tenant);
        log.debug("{} - Get code templates", tenant);

        Map<String, CodeTemplate> codeTemplates = getCodeTemplates(tenant, serviceConfiguration);
        return new ResponseEntity<>(codeTemplates, HttpStatus.OK);
    }

    @Operation(summary = "Update code template", description = """
            Updates the code template for the given ID with new JavaScript code.

            **Security:** Requires `ROLE_DYNAMIC_MAPPER_ADMIN`
            """)
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Code template updated successfully", content = @Content),
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
            ServiceConfiguration serviceConfiguration = serviceConfigurationService.getServiceConfiguration(tenant);
            Map<String, CodeTemplate> codeTemplates = serviceConfiguration.getCodeTemplates();
            serviceConfigurationService.rectifyHeaderInCodeTemplate(codeTemplate, false);
            codeTemplates.put(id, codeTemplate);
            serviceConfigurationService.saveServiceConfiguration(tenant, serviceConfiguration);
            configurationRegistry.addServiceConfiguration(tenant, serviceConfiguration);
            log.debug("{} - Updated code template", tenant);
        } catch (Exception ex) {
            log.error("{} - Error updating code template [{}]", tenant, id, ex);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getLocalizedMessage());
        }
        return new ResponseEntity<HttpStatus>(HttpStatus.CREATED);
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
            ServiceConfiguration serviceConfiguration = serviceConfigurationService.getServiceConfiguration(tenant);
            Map<String, CodeTemplate> codeTemplates = serviceConfiguration.getCodeTemplates();
            if (codeTemplates.containsKey(codeTemplate.id)) {
                throw new Exception(String.format("Template with id %s already exists", codeTemplate.id));
            }
            serviceConfigurationService.rectifyHeaderInCodeTemplate(codeTemplate, true);
            codeTemplates.put(codeTemplate.id, codeTemplate);
            serviceConfigurationService.saveServiceConfiguration(tenant, serviceConfiguration);
            configurationRegistry.addServiceConfiguration(tenant, serviceConfiguration);
            log.debug("{} - Create code template", tenant);
        } catch (JsonProcessingException ex) {
            log.error("{} - Error creating code template", tenant, ex);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getLocalizedMessage());
        } catch (Exception ex) {
            log.error("{} - Error creating code template", tenant, ex);
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getLocalizedMessage());
        }
        return new ResponseEntity<HttpStatus>(HttpStatus.CREATED);
    }

    private Map<String, CodeTemplate> getCodeTemplates(String tenant, ServiceConfiguration serviceConfiguration) {
        Map<String, CodeTemplate> codeTemplates = serviceConfiguration.getCodeTemplates();
        if (codeTemplates == null || codeTemplates.isEmpty()) {
            // Initialize code templates from properties if not already set
            serviceConfigurationService.initCodeTemplates(serviceConfiguration, false);
            codeTemplates = serviceConfiguration.getCodeTemplates();

            try {
                serviceConfigurationService.saveServiceConfiguration(tenant, serviceConfiguration);
                configurationRegistry.addServiceConfiguration(tenant, serviceConfiguration);
            } catch (JsonProcessingException ex) {
                log.error("{} - Error saving service configuration with code templates", tenant, ex);
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getLocalizedMessage());
            }
        }
        return codeTemplates;
    }
}