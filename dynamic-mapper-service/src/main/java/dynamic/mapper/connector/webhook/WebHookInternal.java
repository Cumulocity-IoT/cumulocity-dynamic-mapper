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

package dynamic.mapper.connector.webhook;

import dynamic.mapper.configuration.ConnectorConfiguration;
import dynamic.mapper.connector.core.ConnectorProperty;
import dynamic.mapper.connector.core.ConnectorPropertyType;
import dynamic.mapper.connector.core.ConnectorSpecification;
import dynamic.mapper.connector.core.client.ConnectorType;
import dynamic.mapper.connector.core.registry.ConnectorRegistry;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.model.Direction;
import dynamic.mapper.processor.inbound.CamelDispatcherInbound;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * WebHookInternal Connector Client.
 * Specialized webhook connector for internal Cumulocity REST API communication.
 * Automatically configured with microservice credentials and internal endpoints.
 * Supports POST, PUT, PATCH, and DELETE methods for Cumulocity API operations.
 */
@Slf4j
public class WebHookInternal extends WebHook {

    /**
     * Default constructor
     */
    public WebHookInternal() {
        super();
        this.connectorType = ConnectorType.WEB_HOOK_INTERNAL;
        this.connectorSpecification = createConnectorSpecification();
    }

    /**
     * Full constructor with dependencies
     */
    public WebHookInternal(ConfigurationRegistry configurationRegistry,
            ConnectorRegistry connectorRegistry,
            ConnectorConfiguration connectorConfiguration,
            CamelDispatcherInbound dispatcher,
            String additionalSubscriptionIdTest,
            String tenant) {
        super(configurationRegistry, connectorRegistry, connectorConfiguration,
                dispatcher, additionalSubscriptionIdTest, tenant);
        this.connectorType = ConnectorType.WEB_HOOK_INTERNAL;
        // Update the specification name and description without replacing the entire specification
        // The parent constructor already configured all properties via configureCumulocityInternal()
        updateConnectorSpecificationMetadata();
    }

    /**
     * Update the connector specification metadata (name and description)
     * without replacing the entire specification that was configured by the parent
     */
    private void updateConnectorSpecificationMetadata() {
        if (this.connectorSpecification != null) {
            // Use reflection or create a new specification with the existing properties
            String name = "Cumulocity API";
            String description = "Internal connector to use the Cumulocity REST API. " +
                    "Automatically configured with microservice credentials and internal endpoints. " +
                    "This connector enables direct communication with Cumulocity platform services " +
                    "for creating, updating, and deleting managed objects, events, alarms, and measurements. " +
                    "Supports POST, PUT, PATCH, and DELETE methods.";

            // Create new specification with updated metadata but keeping existing properties
            this.connectorSpecification = new ConnectorSpecification(
                    name,
                    description,
                    ConnectorType.WEB_HOOK_INTERNAL,
                    this.connectorSpecification.isSingleton(),
                    this.connectorSpecification.getProperties(),
                    this.connectorSpecification.isSupportsMessageContext(),
                    this.connectorSpecification.getSupportedDirections());
        }
    }

    /**
     * Create WebHookInternal connector specification
     * This connector is pre-configured for internal Cumulocity communication
     */
    private ConnectorSpecification createConnectorSpecification() {
        Map<String, ConnectorProperty> configProps = new LinkedHashMap<>();

        // Hidden property that is always true for internal connector
        configProps.put("cumulocityInternal",
                new ConnectorProperty(
                        "This connector automatically connects to the Cumulocity instance the mapper is deployed to.",
                        false, 0, ConnectorPropertyType.BOOLEAN_PROPERTY, true, true, true, null, null));

        configProps.put("supportsWildcardInTopicInbound",
                new ConnectorProperty(null, false, 1, ConnectorPropertyType.BOOLEAN_PROPERTY,
                        true, true, true, null, null));

        configProps.put("supportsWildcardInTopicOutbound",
                new ConnectorProperty(null, false, 2, ConnectorPropertyType.BOOLEAN_PROPERTY,
                        true, true, true, null, null));

        String name = "Cumulocity API";
        String description = "Internal connector to use the Cumulocity REST API. " +
                "Automatically configured with microservice credentials and internal endpoints. " +
                "This connector enables direct communication with Cumulocity platform services " +
                "for creating, updating, and deleting managed objects, events, alarms, and measurements. " +
                "Supports POST, PUT, PATCH, and DELETE methods.";

        return new ConnectorSpecification(
                name,
                description,
                ConnectorType.WEB_HOOK_INTERNAL,
                false,
                configProps,
                true,
                supportedDirections());
    }

    @Override
    public java.util.List<Direction> supportedDirections() {
        return Collections.singletonList(Direction.OUTBOUND);
    }
}
