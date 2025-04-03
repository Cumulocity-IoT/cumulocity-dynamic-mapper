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

package dynamic.mapping.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import jakarta.validation.Valid;
import dynamic.mapping.configuration.CodeTemplate;
import dynamic.mapping.configuration.ConnectorConfiguration;
import dynamic.mapping.configuration.ConnectorConfigurationComponent;
import dynamic.mapping.configuration.ServiceConfiguration;
import dynamic.mapping.configuration.ServiceConfigurationComponent;

import dynamic.mapping.connector.core.ConnectorSpecification;
import dynamic.mapping.connector.core.client.ConnectorType;
import dynamic.mapping.connector.core.registry.ConnectorRegistry;
import dynamic.mapping.core.*;

import org.graalvm.polyglot.Context;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
import com.cumulocity.microservice.security.service.RoleService;
import com.fasterxml.jackson.core.JsonProcessingException;

import lombok.extern.slf4j.Slf4j;
import dynamic.mapping.model.Feature;
import dynamic.mapping.model.Mapping;

@Slf4j
@RequestMapping("/configuration")
@RestController
public class ConfigurationController {

    @Autowired
    ConnectorRegistry connectorRegistry;

    @Autowired
    MappingComponent mappingComponent;

    @Autowired
    ConnectorConfigurationComponent connectorConfigurationComponent;

    @Autowired
    ServiceConfigurationComponent serviceConfigurationComponent;

    @Autowired
    BootstrapService bootstrapService;

    @Autowired
    C8YAgent c8YAgent;

    @Autowired
    private RoleService roleService;

    @Autowired
    private ContextService<UserCredentials> contextService;

    @Autowired
    private ConfigurationRegistry configurationRegistry;

    @Value("${APP.externalExtensionsEnabled}")
    private boolean externalExtensionsEnabled;

    @Value("${APP.userRolesEnabled}")
    private Boolean userRolesEnabled;

    @Value("${APP.mappingAdminRole}")
    private String mappingAdminRole;

    @Value("${APP.mappingCreateRole}")
    private String mappingCreateRole;

    @GetMapping(value = "/feature", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Feature> getFeatures() {
        String tenant = contextService.getContext().getTenant();
        ServiceConfiguration serviceConfiguration = serviceConfigurationComponent.getServiceConfiguration(tenant);
        log.debug("Tenant {} - Get Feature status", tenant);
        Feature feature = new Feature();
        feature.setOutputMappingEnabled(serviceConfiguration.isOutboundMappingEnabled());
        feature.setExternalExtensionsEnabled(externalExtensionsEnabled);
        feature.setUserHasMappingCreateRole(userHasMappingCreateRole());
        feature.setUserHasMappingAdminRole(userHasMappingAdminRole());
        return new ResponseEntity<Feature>(feature, HttpStatus.OK);
    }

    @GetMapping(value = "/connector/specifications", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<ConnectorSpecification>> getConnectorSpecifications() {
        String tenant = contextService.getContext().getTenant();
        List<ConnectorSpecification> connectorConfigurations = new ArrayList<>();
        log.info("Tenant {} - Getting connection properties...", tenant);
        Map<ConnectorType, ConnectorSpecification> spec = connectorRegistry
                .getConnectorSpecifications();
        // Iterate over all connectors
        for (ConnectorType connectorType : spec.keySet()) {
            connectorConfigurations.add(spec.get(connectorType));
        }
        return ResponseEntity.ok(connectorConfigurations);
    }

    @PostMapping(value = "/connector/instance", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<HttpStatus> createConnectorConfiguration(
            @Valid @RequestBody ConnectorConfiguration configuration) {
        String tenant = contextService.getContext().getTenant();
        if (configuration.connectorType.equals(ConnectorType.HTTP)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Can't create a HttpConnector!");
        }
        // FIXME This isn't working - use @PreAuthorize instead
        if (!userHasMappingAdminRole()) {
            log.error("Tenant {} - Insufficient Permission, user does not have required permission to access this API",
                    tenant);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Insufficient Permission, user does not have required permission to access this API");
        }
        // Remove sensitive data before printing to log
        ConnectorSpecification connectorSpecification = connectorRegistry
                .getConnectorSpecification(configuration.connectorType);
        ConnectorConfiguration clonedConfig = configuration.getCleanedConfig(connectorSpecification);
        log.info("Tenant {} - Post Connector configuration: {}", tenant, clonedConfig.toString());
        try {
            // if (configuration.connectorType.equals(ConnectorType.INTERNAL_WEB_HOOK)) {
            // UserCredentials contextCredentials = contextService.getContext();
            // String user = (String) contextCredentials.getUsername();
            // String password = (String) contextCredentials.getPassword();
            // configuration.getProperties().put("user", user);
            // configuration.getProperties().put("password", password);
            // }
            connectorConfigurationComponent.saveConnectorConfiguration(configuration);
            return ResponseEntity.status(HttpStatus.CREATED).build();
        } catch (Exception ex) {
            log.error("Tenant {} - Error getting mqtt broker configuration: ", tenant, ex);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getLocalizedMessage());
        }
    }

    @GetMapping(value = "/connector/instance", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<ConnectorConfiguration>> getConnectionConfigurations(
            @RequestParam(required = false) String name) {
        String tenant = contextService.getContext().getTenant();
        log.debug("Tenant {} - Get connection details", tenant);

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

            List<ConnectorConfiguration> configurations = connectorConfigurationComponent
                    .getConnectorConfigurations(tenant);
            List<ConnectorConfiguration> modifiedConfigs = new ArrayList<>();

            // Remove sensitive data before sending to UI
            for (ConnectorConfiguration config : configurations) {
                ConnectorSpecification connectorSpecification = connectorRegistry
                        .getConnectorSpecification(config.connectorType);
                ConnectorConfiguration cleanedConfig = config.getCleanedConfig(connectorSpecification);

                if (pattern == null || pattern.matcher(cleanedConfig.getName()).matches()) {
                    modifiedConfigs.add(cleanedConfig);
                }
            }
            return ResponseEntity.ok(modifiedConfigs);
        } catch (Exception ex) {
            log.error("Tenant {} - Error on loading configuration {}", tenant, ex);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getLocalizedMessage());
        }
    }

    @GetMapping(value = "/connector/instance/{identifier}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ConnectorConfiguration> getConnectionConfiguration(@PathVariable String identifier) {
        String tenant = contextService.getContext().getTenant();
        log.debug("Tenant {} - Get connection details: {}", tenant, identifier);

        try {
            List<ConnectorConfiguration> configurations = connectorConfigurationComponent
                    .getConnectorConfigurations(tenant);
            ConnectorConfiguration modifiedConfig = null;

            // Remove sensitive data before sending to UI
            for (ConnectorConfiguration config : configurations) {
                if (config.getIdentifier().equals(identifier)) {
                    ConnectorSpecification connectorSpecification = connectorRegistry
                            .getConnectorSpecification(config.connectorType);
                    ConnectorConfiguration cleanedConfig = config.getCleanedConfig(connectorSpecification);
                    modifiedConfig = cleanedConfig;
                }
            }
            if (modifiedConfig == null) {
                ResponseEntity.notFound();
            }
            return ResponseEntity.ok(modifiedConfig);
        } catch (Exception ex) {
            log.error("Tenant {} - Error getting configuration: {},  {}", tenant, identifier, ex);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getLocalizedMessage());
        }
    }

    @DeleteMapping(value = "/connector/instance/{identifier}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> deleteConnectionConfiguration(@PathVariable String identifier) {
        String tenant = contextService.getContext().getTenant();
        log.info("Tenant {} - Delete connection instance {}", tenant, identifier);
        // FIXME This isn't working - use @PreAuthorize instead
        if (!userHasMappingAdminRole()) {
            log.error("Tenant {} - Insufficient Permission, user does not have required permission to access this API",
                    tenant);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Insufficient Permission, user does not have required permission to access this API");
        }
        try {
            ConnectorConfiguration configuration = connectorConfigurationComponent.getConnectorConfiguration(identifier,
                    tenant);
            if (configuration.enabled)
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body("Can't delete an enabled connector! Disable connector first.");
            if (configuration.connectorType.equals(ConnectorType.HTTP)) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Can't delete a HttpConnector!");
            }
            // make sure the connector is disconnected before it is deleted.
            // if (connectorRegistry.getClientForTenant(tenant, identifier) != null &&
            // connectorRegistry.getClientForTenant(tenant, identifier).isConnected())
            bootstrapService.disableConnector(tenant, identifier);
            connectorConfigurationComponent.deleteConnectorConfiguration(identifier);
            mappingComponent.removeConnectorFromDeploymentMap(tenant, identifier);
            connectorRegistry.removeClientFromStatusMap(tenant, identifier);
            bootstrapService.shutdownAndRemoveConnector(tenant, identifier);
        } catch (Exception ex) {
            log.error("Tenant {} - Error getting mqtt broker configuration {}", tenant, ex);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getLocalizedMessage());
        }
        return ResponseEntity.status(HttpStatus.OK).body(identifier);
    }

    @PutMapping(value = "/connector/instance/{identifier}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ConnectorConfiguration> updateConnectionConfiguration(@PathVariable String identifier,
            @Valid @RequestBody ConnectorConfiguration configuration) {
        String tenant = contextService.getContext().getTenant();
        log.info("Tenant {} - Update connection instance {}", tenant, identifier);
        // make sure we are using the correct identifier
        configuration.identifier = identifier;
        // FIXME This isn't working - use @PreAuthorize instead
        if (!userHasMappingAdminRole()) {
            log.error("Tenant {} - Insufficient Permission, user does not have required permission to access this API",
                    tenant);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Insufficient Permission, user does not have required permission to access this API");
        }
        // Remove sensitive data before printing to log
        ConnectorSpecification connectorSpecification = connectorRegistry
                .getConnectorSpecification(configuration.connectorType);
        // if (connectorSpecification.connectorType.equals(ConnectorType.HTTP)) {
        // throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Can't change a
        // HttpConnector!");
        // }
        ConnectorConfiguration clonedConfig = configuration.getCleanedConfig(connectorSpecification);
        log.info("Tenant {} - Post Connector configuration: {}", tenant, clonedConfig.toString());
        try {
            // check if password filed was touched, e.g. != "****", then use password from
            // new payload, otherwise copy password from previously saved configuration
            ConnectorConfiguration originalConfiguration = connectorConfigurationComponent
                    .getConnectorConfiguration(configuration.identifier, tenant);

            for (String property : configuration.getProperties().keySet()) {
                if (connectorSpecification.isPropertySensitive(property)
                        && configuration.getProperties().get(property).equals("****")) {
                    // retrieve the existing value
                    log.info(
                            "Tenant {} - Copy property {} from existing configuration, since it was not touched and is sensitive.",
                            property);
                    configuration.getProperties().put(property,
                            originalConfiguration.getProperties().get(property));
                }
            }
            connectorConfigurationComponent.saveConnectorConfiguration(configuration);
            // AConnectorClient client = connectorRegistry.getClientForTenant(tenant,
            // configuration.getIdent());
            // client.reconnect();
        } catch (Exception ex) {
            log.error("Tenant {} - Error getting mqtt broker configuration {}", tenant, ex);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getLocalizedMessage());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(configuration);
    }

    @GetMapping(value = "/service", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ServiceConfiguration> getServiceConfiguration() {
        String tenant = contextService.getContext().getTenant();
        log.info("Tenant {} - Get connection details", tenant);

        try {
            final ServiceConfiguration configuration = serviceConfigurationComponent.getServiceConfiguration(tenant);
            if (configuration == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Service connection not available");
            }
            // don't modify original copy
            return new ResponseEntity<ServiceConfiguration>(configuration, HttpStatus.OK);
        } catch (Exception ex) {
            log.error("Tenant {} - Error on loading configuration {}", tenant, ex);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getLocalizedMessage());
        }
    }

    @PutMapping(value = "/service", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<HttpStatus> configureConnectionToBroker(
            @Valid @RequestBody ServiceConfiguration configuration) {
        String tenant = contextService.getContext().getTenant();
        // don't modify original copy
        log.info("Tenant {} - Post service configuration: {}", tenant, configuration.toString());
        ServiceConfiguration mergeServiceConfiguration = serviceConfigurationComponent.getServiceConfiguration(tenant);
        Map<String, CodeTemplate> codeTemplates = mergeServiceConfiguration.getCodeTemplates();
        // FIXME This isn't working - use @PreAuthorize instead
        if (!userHasMappingAdminRole()) {
            log.error("Tenant {} - Insufficient Permission, user does not have required permission to access this API",
                    tenant);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Insufficient Permission, user does not have required permission to access this API");
        }

        try {
            configuration.setCodeTemplates(codeTemplates);
            serviceConfigurationComponent.saveServiceConfiguration(tenant, configuration);
            if (!configuration.isOutboundMappingEnabled()
                    && configurationRegistry.getNotificationSubscriber().getDeviceConnectionStatus(tenant) == 200) {
                configurationRegistry.getNotificationSubscriber().disconnect(tenant);
            } else if (configurationRegistry.getNotificationSubscriber().getDeviceConnectionStatus(tenant) == null
                    || configurationRegistry.getNotificationSubscriber().getDeviceConnectionStatus(tenant) == null
                            && configurationRegistry.getNotificationSubscriber()
                                    .getDeviceConnectionStatus(tenant) != 200) {
                List<ConnectorConfiguration> connectorConfigurationList = connectorConfigurationComponent
                        .getConnectorConfigurations(tenant);
                for (ConnectorConfiguration connectorConfiguration : connectorConfigurationList) {
                    bootstrapService.initializeConnectorByConfiguration(connectorConfiguration, configuration, tenant).get();
                }
                configurationRegistry.getNotificationSubscriber().initDeviceClient();
            }

            configurationRegistry.getServiceConfigurations().put(tenant, configuration);
            return ResponseEntity.status(HttpStatus.CREATED).build();
        } catch (Exception ex) {
            log.error("Tenant {} - Error getting mqtt broker configuration {}", tenant, ex);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getLocalizedMessage());
        }
    }

    @GetMapping(value = "/code/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CodeTemplate> getCodeTemplate(@PathVariable String id) {
        String tenant = contextService.getContext().getTenant();
        ServiceConfiguration serviceConfiguration = serviceConfigurationComponent.getServiceConfiguration(tenant);
        log.debug("Tenant {} - Get code template", tenant);

        Map<String, CodeTemplate> codeTemplates = serviceConfiguration.getCodeTemplates();
        if (codeTemplates == null || codeTemplates.isEmpty()) {
            // Initialize code templates from properties if not already set
            serviceConfigurationComponent.initCodeTemplates(serviceConfiguration);
            codeTemplates = serviceConfiguration.getCodeTemplates();

            try {
                serviceConfigurationComponent.saveServiceConfiguration(tenant, serviceConfiguration);
                configurationRegistry.getServiceConfigurations().put(tenant, serviceConfiguration);
            } catch (JsonProcessingException ex) {
                log.error("Tenant {} - Error saving service configuration with code templates: {}", tenant, ex);
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getLocalizedMessage());
            }
        }

        CodeTemplate result = codeTemplates.get(id);
        if (result == null) {
            // Template not found - return 404 Not Found
            log.warn("Tenant {} - Code template with ID '{}' not found", tenant, id);
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        } else {
            // Template exists - return it with 200 OK
            return new ResponseEntity<>(result, HttpStatus.OK);
        }
    }

    @DeleteMapping(value = "/code/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CodeTemplate> deleteCodeTemplate(@PathVariable String id) {
        String tenant = contextService.getContext().getTenant();
        ServiceConfiguration serviceConfiguration = serviceConfigurationComponent.getServiceConfiguration(tenant);
        log.debug("Tenant {} - Delete code template", tenant);

        Map<String, CodeTemplate> codeTemplates = serviceConfiguration.getCodeTemplates();
        if (codeTemplates == null || codeTemplates.isEmpty()) {
            // Initialize code templates from properties if not already set
            serviceConfigurationComponent.initCodeTemplates(serviceConfiguration);
            codeTemplates = serviceConfiguration.getCodeTemplates();

            try {
                serviceConfigurationComponent.saveServiceConfiguration(tenant, serviceConfiguration);
                configurationRegistry.getServiceConfigurations().put(tenant, serviceConfiguration);
            } catch (JsonProcessingException ex) {
                log.error("Tenant {} - Error saving service configuration with code templates: {}", tenant, ex);
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
            serviceConfigurationComponent.saveServiceConfiguration(tenant, serviceConfiguration);
        } catch (Exception ex) {
            log.error("Tenant {} - Error updating code template {}", tenant, ex);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getLocalizedMessage());
        }
        configurationRegistry.getServiceConfigurations().put(tenant, serviceConfiguration);
        if (result == null) {
            // Template not found - return 404 Not Found
            log.warn("Tenant {} - Code template with ID '{}' not found", tenant, id);
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        } else {
            // Template exists - return it with 200 OK
            return new ResponseEntity<>(result, HttpStatus.OK);
        }
    }

    @GetMapping(value = "/code", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, CodeTemplate>> getCodeTemplates() {
        String tenant = contextService.getContext().getTenant();
        ServiceConfiguration serviceConfiguration = serviceConfigurationComponent.getServiceConfiguration(tenant);
        log.debug("Tenant {} - Get code templates", tenant);

        Map<String, CodeTemplate> codeTemplates = serviceConfiguration.getCodeTemplates();
        if (codeTemplates == null || codeTemplates.isEmpty()) {
            // Initialize code templates from properties if not already set
            serviceConfigurationComponent.initCodeTemplates(serviceConfiguration);
            codeTemplates = serviceConfiguration.getCodeTemplates();

            try {
                serviceConfigurationComponent.saveServiceConfiguration(tenant, serviceConfiguration);
                configurationRegistry.getServiceConfigurations().put(tenant, serviceConfiguration);
            } catch (JsonProcessingException ex) {
                log.error("Tenant {} - Error saving service configuration with code templates: {}", tenant, ex);
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getLocalizedMessage());
            }
        }
        return new ResponseEntity<>(codeTemplates, HttpStatus.OK);
    }

    @PutMapping(value = "/code/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<HttpStatus> updateCodeTemplate(
            @PathVariable String id, @Valid @RequestBody CodeTemplate codeTemplate) {
        String tenant = contextService.getContext().getTenant();
        Context graalsContext = null;
        try {
            ServiceConfiguration serviceConfiguration = serviceConfigurationComponent.getServiceConfiguration(tenant);
            Map<String, CodeTemplate> codeTemplates = serviceConfiguration.getCodeTemplates();
            codeTemplates.put(id, codeTemplate);
            serviceConfigurationComponent.saveServiceConfiguration(tenant, serviceConfiguration);
            configurationRegistry.getServiceConfigurations().put(tenant, serviceConfiguration);
            log.debug("Tenant {} - Updated code template", tenant);
        } catch (Exception ex) {
            log.error("Tenant {} - Error updating code template {}", tenant, ex);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getLocalizedMessage());
        } finally {
            if (graalsContext != null) {
                graalsContext.close();
            }
        }
        return new ResponseEntity<HttpStatus>(HttpStatus.CREATED);
    }

    @PostMapping(value = "/code", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<HttpStatus> createCodeTemplate(
            @Valid @RequestBody CodeTemplate codeTemplate) {
        String tenant = contextService.getContext().getTenant();
        Context graalsContext = null;
        try {
            ServiceConfiguration serviceConfiguration = serviceConfigurationComponent.getServiceConfiguration(tenant);
            Map<String, CodeTemplate> codeTemplates = serviceConfiguration.getCodeTemplates();
            if (codeTemplates.containsKey(codeTemplate.id)) {
                throw new Exception(String.format("Template with id %s already exists", codeTemplate.id));
            }
            codeTemplates.put(codeTemplate.id, codeTemplate);
            serviceConfigurationComponent.saveServiceConfiguration(tenant, serviceConfiguration);
            configurationRegistry.getServiceConfigurations().put(tenant, serviceConfiguration);
            log.debug("Tenant {} - Create code template", tenant);
        } catch (JsonProcessingException ex) {
            log.error("Tenant {} - Error updating code template {}", tenant, ex);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getLocalizedMessage());
        } catch (Exception ex) {
            log.error("Tenant {} - Error updating code template {}", tenant, ex);
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getLocalizedMessage());
        } finally {
            if (graalsContext != null) {
                graalsContext.close();
            }
        }
        return new ResponseEntity<HttpStatus>(HttpStatus.CREATED);
    }

    public void cleanupNonServiceMembers(Context context) {
        org.graalvm.polyglot.Value bindings = context.getBindings("js");

        // Get all member keys
        Set<String> members = bindings.getMemberKeys();

        // Remove members that don't start with "service"
        members.stream()
                .filter(member -> !member.startsWith(Mapping.EXTRACT_FROM_SOURCE))
                .forEach(member -> {
                    log.debug("Removing member: {}", member);
                    bindings.removeMember(member);
                });
    }

    private boolean userHasMappingAdminRole() {
        return !userRolesEnabled || (userRolesEnabled && roleService.getUserRoles().contains(mappingAdminRole));
    }

    private boolean userHasMappingCreateRole() {
        return !userRolesEnabled || userHasMappingAdminRole()
                || (userRolesEnabled && roleService.getUserRoles().contains(mappingCreateRole));
    }

}