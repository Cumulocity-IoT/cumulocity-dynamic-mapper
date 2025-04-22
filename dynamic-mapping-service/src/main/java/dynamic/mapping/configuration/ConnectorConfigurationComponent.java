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

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;
import com.cumulocity.model.option.OptionPK;
import com.cumulocity.rest.representation.tenant.OptionRepresentation;
import com.cumulocity.sdk.client.SDKException;
import com.cumulocity.sdk.client.option.TenantOptionApi;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import dynamic.mapping.util.Utils;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class ConnectorConfigurationComponent {
    private static final String OPTION_KEY_CONNECTOR_PREFIX = "credentials.connection.configuration";

    private final TenantOptionApi tenantOptionApi;

    @Autowired
    private MicroserviceSubscriptionsService subscriptionsService;

    private ObjectMapper objectMapper;

    @Autowired
    public void setObjectMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ConnectorConfigurationComponent(TenantOptionApi tenantOptionApi) {
        this.tenantOptionApi = tenantOptionApi;
    }

    public String getConnectorOptionKey(String identifier) {
        return OPTION_KEY_CONNECTOR_PREFIX + "." + identifier;
    }

    public void saveConnectorConfiguration(final ConnectorConfiguration configuration)
            throws JsonProcessingException {
        if (configuration == null) {
            return;
        }
        String identifier = configuration.getIdentifier();
        final String configurationJson = objectMapper.writeValueAsString(configuration);
        final OptionRepresentation optionRepresentation = OptionRepresentation
                .asOptionRepresentation(Utils.OPTION_CATEGORY_CONFIGURATION, getConnectorOptionKey(identifier),
                        configurationJson);
        tenantOptionApi.save(optionRepresentation);

    }

    public void deleteConnectorConfiguration(final String identifier)
            throws JsonProcessingException {
        if (identifier == null) {
            return;
        }
        final OptionPK option = new OptionPK();
        option.setCategory(Utils.OPTION_CATEGORY_CONFIGURATION);
        option.setKey(getConnectorOptionKey(identifier));
        tenantOptionApi.delete(option);
    }

    public ConnectorConfiguration getConnectorConfiguration(String identifier, String tenant) {
        final OptionPK option = new OptionPK();
        option.setCategory(Utils.OPTION_CATEGORY_CONFIGURATION);
        option.setKey(getConnectorOptionKey(identifier));
        ConnectorConfiguration result = subscriptionsService.callForTenant(tenant, () -> {
            ConnectorConfiguration rt = null;
            try {
                final OptionRepresentation optionRepresentation = tenantOptionApi.getOption(option);
                final ConnectorConfiguration configuration = objectMapper.readValue(
                        optionRepresentation.getValue(),
                        ConnectorConfiguration.class);
                log.debug("Tenant {} - Returning connection configuration found: {}:", tenant,
                        configuration.getConnectorType());
                rt = configuration;
            } catch (SDKException exception) {
                log.warn("Tenant {} - No configuration found, returning empty element!", tenant);
            } catch (Exception e) {
                String exceptionMsg = e.getCause() == null ? e.getMessage() : e.getCause().getMessage();
                String msg = String.format("Failed to convert configurator object %s. Error: %s",
                        identifier,
                        exceptionMsg);
                log.error("Tenant {} - Failed to convert configurator object {}", tenant, identifier, e);
            }
            return rt;
        });
        return result;
    }

    public List<ConnectorConfiguration> getConnectorConfigurations(String tenant) {
        final List<ConnectorConfiguration> connectorConfigurations = new ArrayList<>();
        subscriptionsService.runForTenant(tenant, () -> {
            final List<OptionRepresentation> optionRepresentationList = tenantOptionApi
                    .getAllOptionsForCategory(Utils.OPTION_CATEGORY_CONFIGURATION);
            for (OptionRepresentation optionRepresentation : optionRepresentationList) {
                try {
                    // Just Connector Config --> Ignoring Service Configuration
                    String optionKey = OPTION_KEY_CONNECTOR_PREFIX.replace("credentials.", "");
                    if (optionRepresentation.getKey().startsWith(optionKey)) {
                        final ConnectorConfiguration configuration = objectMapper.readValue(
                                optionRepresentation.getValue(),
                                ConnectorConfiguration.class);
                        connectorConfigurations.add(configuration);
                        log.debug("Tenant {} - Connection configuration found: {}:", tenant,
                                configuration.getConnectorType());
                    }
                } catch (SDKException exception) {
                    log.warn("Tenant {} - No configuration found, returning empty element!", tenant);
                } catch (Exception e) {
                    String exceptionMsg = e.getCause() == null ? e.getMessage() : e.getCause().getMessage();
                    String msg = String.format("Failed to convert configurator object %s. Error: %s",
                            optionRepresentation.getKey(),
                            exceptionMsg);
                    log.warn(msg);
                }
            }
        });
        return connectorConfigurations;
    }

    public void deleteConnectorConfigurations(String tenant) {
        List<ConnectorConfiguration> configs = getConnectorConfigurations(tenant);
        for (ConnectorConfiguration config : configs) {
            OptionPK optionPK = new OptionPK(Utils.OPTION_CATEGORY_CONFIGURATION,
                    getConnectorOptionKey(config.getIdentifier()));
            tenantOptionApi.delete(optionPK);
        }
    }

    public ConnectorConfiguration enableConnection(String identifier, boolean enabled) {
        final OptionPK option = new OptionPK(Utils.OPTION_CATEGORY_CONFIGURATION, getConnectorOptionKey(identifier));
        String tenant = subscriptionsService.getTenant();
        try {
            final OptionRepresentation optionRepresentation = tenantOptionApi.getOption(option);
            final ConnectorConfiguration configuration = objectMapper.readValue(optionRepresentation.getValue(),
                    ConnectorConfiguration.class);

            configuration.enabled = enabled;
            log.debug("Tenant {} - Setting connection: {}:", tenant, configuration.enabled);
            final String configurationJson = objectMapper.writeValueAsString(configuration);
            optionRepresentation.setCategory(Utils.OPTION_CATEGORY_CONFIGURATION);
            optionRepresentation.setKey(getConnectorOptionKey(identifier));
            optionRepresentation.setValue(configurationJson);
            tenantOptionApi.save(optionRepresentation);
            return configuration;
        } catch (SDKException exception) {
            log.warn("Tenant {} - No configuration found, returning empty element!", tenant);
            // exception.printStackTrace();
        } catch (Exception e) {
            String exceptionMsg = e.getCause() == null ? e.getMessage() : e.getCause().getMessage();
            String msg = String.format("Failed to convert configurator object %s. Error: %s",
                    identifier,
                    exceptionMsg);
            log.warn(msg);
        }
        return null;
    }
}
