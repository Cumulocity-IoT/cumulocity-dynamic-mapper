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

package dynamic.mapper.connector.mqtt;

import java.net.URI;
import java.util.AbstractMap;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import com.cumulocity.microservice.context.credentials.MicroserviceCredentials;

import dynamic.mapper.connector.core.ConnectorPropertyType;
import dynamic.mapper.connector.core.ConnectorSpecification;
import dynamic.mapper.connector.core.client.AConnectorClient;
import dynamic.mapper.connector.core.client.ConnectorType;
import dynamic.mapper.processor.inbound.CamelDispatcherInbound;
import lombok.extern.slf4j.Slf4j;
import dynamic.mapper.configuration.ConnectorConfiguration;
import dynamic.mapper.configuration.ConnectorId;
import dynamic.mapper.connector.core.ConnectorProperty;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.core.ConnectorStatusEvent;
import dynamic.mapper.model.Qos;

@Slf4j
public class MQTTServiceClient extends MQTT3Client {
    public MQTTServiceClient() {
        Map<String, ConnectorProperty> configProps = new HashMap<>();
        configProps.put("version",
                new ConnectorProperty(null, true, 0, ConnectorPropertyType.OPTION_PROPERTY, true, false,
                        MQTT_VERSION_3_1_1,
                        Map.ofEntries(
                                new AbstractMap.SimpleEntry<String, String>(MQTT_VERSION_3_1_1, MQTT_VERSION_3_1_1),
                                new AbstractMap.SimpleEntry<String, String>(MQTT_VERSION_5_0, MQTT_VERSION_5_0)),
                        null));
        configProps.put("protocol",
                new ConnectorProperty(null, true, 1, ConnectorPropertyType.OPTION_PROPERTY, true, true,
                        AConnectorClient.MQTT_PROTOCOL_MQTT,
                        Map.ofEntries(
                                new AbstractMap.SimpleEntry<String, String>(AConnectorClient.MQTT_PROTOCOL_MQTT,
                                        AConnectorClient.MQTT_PROTOCOL_MQTT),
                                new AbstractMap.SimpleEntry<String, String>(AConnectorClient.MQTT_PROTOCOL_MQTTS,
                                        AConnectorClient.MQTT_PROTOCOL_MQTTS),
                                new AbstractMap.SimpleEntry<String, String>(AConnectorClient.MQTT_PROTOCOL_WS,
                                        AConnectorClient.MQTT_PROTOCOL_WS),
                                new AbstractMap.SimpleEntry<String, String>(AConnectorClient.MQTT_PROTOCOL_WSS,
                                        AConnectorClient.MQTT_PROTOCOL_WSS)),
                        null));
        configProps.put("mqttHost",
                new ConnectorProperty(null, true, 2, ConnectorPropertyType.STRING_PROPERTY, true, true,
                        "c8y-mqtt-service",
                        null, null));
        configProps.put("mqttPort",
                new ConnectorProperty(null, true, 3, ConnectorPropertyType.NUMERIC_PROPERTY, true, true, 2883, null,
                        null));
        configProps.put("user",
                new ConnectorProperty(null, true, 4, ConnectorPropertyType.STRING_PROPERTY, true, true, null, null,
                        null));
        configProps.put("password",
                new ConnectorProperty(null, true, 5, ConnectorPropertyType.SENSITIVE_STRING_PROPERTY, true, true, null,
                        null, null));
        configProps.put("clientId",
                new ConnectorProperty(null, true, 6, ConnectorPropertyType.ID_STRING_PROPERTY, true, true,
                        null, null, null));
        configProps.put("useSelfSignedCertificate",
                new ConnectorProperty(null, false, 7, ConnectorPropertyType.BOOLEAN_PROPERTY, true, true, false, null,
                        null));
        configProps.put("fingerprintSelfSignedCertificate",
                new ConnectorProperty(null, false, 8, ConnectorPropertyType.STRING_PROPERTY, true, true, false, null,
                        null));
        configProps.put("nameCertificate",
                new ConnectorProperty(null, false, 9, ConnectorPropertyType.STRING_PROPERTY, true, true, false, null,
                        null));
        configProps.put("supportsWildcardInTopic",
                new ConnectorProperty(null, false, 10, ConnectorPropertyType.BOOLEAN_PROPERTY, true, true, false, null,
                        null));
        configProps.put("cleanSession",
                new ConnectorProperty(null, false, 11, ConnectorPropertyType.BOOLEAN_PROPERTY, true, false, true, null,
                        null));
        String name = "Cumulocity MQTT Service - (tenant isolation)";
        String description = "Connector for connecting to Cumulocity MQTT Service. The MQTT Service does not support wildcards, i.e. '+', '#'. The QoS 'exactly once' is reduced to 'at least once'.";
        connectorType = ConnectorType.CUMULOCITY_MQTT_SERVICE;
        singleton = true;
        connectorSpecification = new ConnectorSpecification(name, description, connectorType, singleton, configProps,
                false,
                supportedDirections());
    }

    private static String getClientId(String identifier, String suffix) {
        return "CUMULOCITY_MQTT_SERVICE" + identifier + suffix;
    }

    public MQTTServiceClient(ConfigurationRegistry configurationRegistry,
            ConnectorConfiguration connectorConfiguration,
            CamelDispatcherInbound dispatcher, String additionalSubscriptionIdTest, String tenant) {
        this();
        this.configurationRegistry = configurationRegistry;
        this.mappingService = configurationRegistry.getMappingService();
        this.serviceConfigurationService = configurationRegistry.getServiceConfigurationService();
        this.connectorConfigurationService = configurationRegistry.getConnectorConfigurationService();
        this.connectorConfiguration = connectorConfiguration;
        // ensure the client knows its identity even if configuration is set to null
        this.connectorIdentifier = connectorConfiguration.identifier;
        this.connectorName = connectorConfiguration.name;
        this.connectorId = new ConnectorId(connectorConfiguration.name, connectorConfiguration.identifier,
                connectorType);
        this.connectorStatus = ConnectorStatusEvent.unknown(connectorConfiguration.name,
                connectorConfiguration.identifier);
        this.c8yAgent = configurationRegistry.getC8yAgent();
        this.virtualThreadPool = configurationRegistry.getVirtualThreadPool();
        this.objectMapper = configurationRegistry.getObjectMapper();
        this.additionalSubscriptionIdTest = additionalSubscriptionIdTest;
        this.serviceConfiguration = configurationRegistry.getServiceConfiguration(tenant);
        this.dispatcher = dispatcher;
        this.tenant = tenant;
        MicroserviceCredentials msc = configurationRegistry.getMicroserviceCredential(tenant);
        String user = String.format("%s/%s", tenant, msc.getUsername());
        getConnectorSpecification().getProperties().put("user",
                new ConnectorProperty(null, true, 2, ConnectorPropertyType.STRING_PROPERTY, true, true, user, null,
                        null));
        getConnectorSpecification().getProperties().put("password",
                new ConnectorProperty(null, true, 3, ConnectorPropertyType.SENSITIVE_STRING_PROPERTY, true, true,
                        msc.getPassword(), null, null));
        getConnectorSpecification().getProperties().put("clientId",
                new ConnectorProperty(null, true, 5, ConnectorPropertyType.ID_STRING_PROPERTY, true, true,
                        getClientId(this.connectorIdentifier, this.additionalSubscriptionIdTest), null, null));
        this.supportedQOS = Arrays.asList(Qos.AT_LEAST_ONCE, Qos.AT_MOST_ONCE);

        try {
            URI uri = new URI(configurationRegistry.getMqttServiceUrl());
            String protocol = uri.getScheme();
            getConnectorSpecification().getProperties().put("protocol",
                    new ConnectorProperty(null, true, 0, ConnectorPropertyType.STRING_PROPERTY, true, true,
                            protocol, null, null));
            String mqttHost = uri.getHost();
            getConnectorSpecification().getProperties().put("mqttHost",
                    new ConnectorProperty(null, true, 1, ConnectorPropertyType.STRING_PROPERTY, true, true,
                            mqttHost, null, null));
            int mqttPort = uri.getPort();
            getConnectorSpecification().getProperties().put("mqttPort",
                    new ConnectorProperty(null, true, 2, ConnectorPropertyType.NUMERIC_PROPERTY, true, true,
                            mqttPort, null, null));
        } catch (Exception e) {
            log.error("{} - Connector {} - Can't parse mqttServiceUrl, using default. ", tenant,
                    getConnectorName(), e);
        }
    }

    @Override
    public Boolean supportsWildcardsInTopic() {
        return false;
    }
}