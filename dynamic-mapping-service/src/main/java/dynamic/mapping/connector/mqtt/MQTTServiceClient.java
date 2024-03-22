/*
 * Copyright (c) 2022 Software AG, Darmstadt, Germany and/or Software AG USA Inc., Reston, VA, USA,
 * and/or its subsidiaries and/or its affiliates and/or their licensors.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * @authors Christof Strack, Stefan Witschel
 */

package dynamic.mapping.connector.mqtt;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import dynamic.mapping.connector.core.ConnectorPropertyType;
import dynamic.mapping.connector.core.ConnectorSpecification;
import dynamic.mapping.processor.inbound.AsynchronousDispatcherInbound;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import dynamic.mapping.configuration.ConnectorConfiguration;
import dynamic.mapping.connector.core.ConnectorProperty;
import dynamic.mapping.core.ConfigurationRegistry;

@Slf4j
// This is instantiated manually not using Spring Boot anymore.
public class MQTTServiceClient extends MQTTClient {
    public MQTTServiceClient() {
        Map<String, ConnectorProperty> configProps = new HashMap<>();
        configProps.put("mqttHost", new ConnectorProperty(true, 0, ConnectorPropertyType.STRING_PROPERTY, false, "cumulocity"));
        configProps.put("mqttPort", new ConnectorProperty(true, 1, ConnectorPropertyType.NUMERIC_PROPERTY, false, 2883));
        configProps.put("user", new ConnectorProperty(false, 2, ConnectorPropertyType.STRING_PROPERTY, true, null));
        configProps.put("password",
                new ConnectorProperty(false, 3, ConnectorPropertyType.SENSITIVE_STRING_PROPERTY, true, null));
        configProps.put("clientId", new ConnectorProperty(true, 4, ConnectorPropertyType.STRING_PROPERTY, true, null));
        configProps.put("useTLS", new ConnectorProperty(false, 5, ConnectorPropertyType.BOOLEAN_PROPERTY, false, false));
        configProps.put("useSelfSignedCertificate",
                new ConnectorProperty(false, 6, ConnectorPropertyType.BOOLEAN_PROPERTY, false, false));
        configProps.put("fingerprintSelfSignedCertificate",
                new ConnectorProperty(false, 7, ConnectorPropertyType.STRING_PROPERTY, false, false));
        configProps.put("nameCertificate", new ConnectorProperty(false, 8, ConnectorPropertyType.STRING_PROPERTY, false, false));
        configProps.put("supportsWildcardInTopic",
                new ConnectorProperty(false, 8, ConnectorPropertyType.BOOLEAN_PROPERTY, false, false));
        spec = new ConnectorSpecification(connectorType, configProps);
    }

    public MQTTServiceClient(ConfigurationRegistry configurationRegistry,
            ConnectorConfiguration connectorConfiguration,
            AsynchronousDispatcherInbound dispatcher, String additionalSubscriptionIdTest, String tenant) {
        this();
        this.configurationRegistry = configurationRegistry;
        this.mappingComponent = configurationRegistry.getMappingComponent();
        this.serviceConfigurationComponent = configurationRegistry.getServiceConfigurationComponent();
        this.connectorConfigurationComponent = configurationRegistry.getConnectorConfigurationComponent();
        this.connectorConfiguration = connectorConfiguration;
        // ensure the client knows its identity even if configuration is set to null
        this.connectorIdent = connectorConfiguration.ident;
        this.connectorName = connectorConfiguration.name;
        this.c8yAgent = configurationRegistry.getC8yAgent();
        this.cachedThreadPool = configurationRegistry.getCachedThreadPool();
        this.objectMapper = configurationRegistry.getObjectMapper();
        this.additionalSubscriptionIdTest = additionalSubscriptionIdTest;
        this.mappingServiceRepresentation = configurationRegistry.getMappingServiceRepresentations().get(tenant);
        this.serviceConfiguration = configurationRegistry.getServiceConfigurations().get(tenant);
        this.dispatcher = dispatcher;
        this.tenant = tenant;
    }

    public ConnectorType connectorType = ConnectorType.MQTT_SERVICE;

    @Override
    public Boolean supportsWildcardsInTopic() {
        return false;
    }
}