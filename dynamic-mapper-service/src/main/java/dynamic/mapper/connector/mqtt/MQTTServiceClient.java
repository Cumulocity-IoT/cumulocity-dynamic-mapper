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

import com.cumulocity.microservice.context.credentials.MicroserviceCredentials;
import dynamic.mapper.configuration.ConnectorConfiguration;
import dynamic.mapper.configuration.ConnectorId;
import dynamic.mapper.connector.core.ConnectorProperty;
import dynamic.mapper.connector.core.ConnectorPropertyType;
import dynamic.mapper.connector.core.ConnectorSpecification;
import dynamic.mapper.connector.core.client.AConnectorClient;
import dynamic.mapper.connector.core.client.ConnectorType;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.core.ConnectorStatusEvent;
import dynamic.mapper.model.Direction;
import dynamic.mapper.model.Qos;
import dynamic.mapper.processor.inbound.CamelDispatcherInbound;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.util.*;

/**
 * MQTT Service Client for Cumulocity IoT MQTT broker with tenant isolation.
 * Extends MQTT3Client with predefined configuration for C8Y MQTT service.
 */
@Slf4j
public class MQTTServiceClient extends MQTT3Client {

    /**
     * Default constructor - initializes connector specification
     */
    public MQTTServiceClient() {
        super();
        this.connectorType = ConnectorType.CUMULOCITY_MQTT_SERVICE;
        this.singleton = true;
        this.connectorSpecification = createCumulocityMqttServiceSpecification();
    }

    /**
     * Full constructor with dependencies
     */
    public MQTTServiceClient(ConfigurationRegistry configurationRegistry,
                            ConnectorConfiguration connectorConfiguration,
                            CamelDispatcherInbound dispatcher,
                            String additionalSubscriptionIdTest,
                            String tenant) {
        this();
        
        // Initialize from parent
        this.configurationRegistry = configurationRegistry;
        this.mappingService = configurationRegistry.getMappingService();
        this.serviceConfigurationService = configurationRegistry.getServiceConfigurationService();
        this.connectorConfigurationService = configurationRegistry.getConnectorConfigurationService();
        this.c8yAgent = configurationRegistry.getC8yAgent();
        this.virtualThreadPool = configurationRegistry.getVirtualThreadPool();
        this.objectMapper = configurationRegistry.getObjectMapper();
        
        // Set configuration
        this.connectorConfiguration = connectorConfiguration;
        this.connectorIdentifier = connectorConfiguration.getIdentifier();
        this.connectorName = connectorConfiguration.getName();
        this.connectorId = new ConnectorId(
                connectorConfiguration.getName(),
                connectorConfiguration.getIdentifier(),
                connectorType);
        this.connectorStatus = ConnectorStatusEvent.unknown(
                connectorConfiguration.getName(),
                connectorConfiguration.getIdentifier());
        
        this.additionalSubscriptionIdTest = additionalSubscriptionIdTest;
        this.serviceConfiguration = configurationRegistry.getServiceConfiguration(tenant);
        this.dispatcher = dispatcher;
        this.tenant = tenant;
        
        // Configure C8Y MQTT Service specific settings
        configureCumulocityMqttService(configurationRegistry, tenant);
        
        // Override supported QoS - C8Y MQTT Service doesn't support EXACTLY_ONCE
        setSupportedQOS(Arrays.asList(Qos.AT_MOST_ONCE, Qos.AT_LEAST_ONCE));
        
        // Initialize managers
        initializeManagers();
        
        log.info("{} - MQTTServiceClient initialized for Cumulocity MQTT Service", tenant);
    }

    /**
     * Configure Cumulocity MQTT Service specific properties
     */
    private void configureCumulocityMqttService(ConfigurationRegistry configurationRegistry, String tenant) {
        try {
            // Get microservice credentials
            MicroserviceCredentials msc = configurationRegistry.getMicroserviceCredential(tenant);
            String user = String.format("%s/%s", tenant, msc.getUsername());
            String password = msc.getPassword();
            String clientId = getClientId(this.connectorIdentifier, this.additionalSubscriptionIdTest);
            
            // Parse MQTT service URL
            URI uri = new URI(configurationRegistry.getMqttServiceUrl());
            String protocol = uri.getScheme() + "://";
            String mqttHost = uri.getHost();
            int mqttPort = uri.getPort();
            
            // Update specification with predefined values
            Map<String, ConnectorProperty> props = getConnectorSpecification().getProperties();
            
            props.put("protocol",
                    new ConnectorProperty(null, true, 0, ConnectorPropertyType.STRING_PROPERTY, 
                            true, true, protocol, null, null));
            
            props.put("mqttHost",
                    new ConnectorProperty(null, true, 1, ConnectorPropertyType.STRING_PROPERTY, 
                            true, true, mqttHost, null, null));
            
            props.put("mqttPort",
                    new ConnectorProperty(null, true, 2, ConnectorPropertyType.NUMERIC_PROPERTY, 
                            true, true, mqttPort, null, null));
            
            props.put("user",
                    new ConnectorProperty(null, true, 3, ConnectorPropertyType.STRING_PROPERTY, 
                            true, true, user, null, null));
            
            props.put("password",
                    new ConnectorProperty(null, true, 4, ConnectorPropertyType.SENSITIVE_STRING_PROPERTY, 
                            true, true, password, null, null));
            
            props.put("clientId",
                    new ConnectorProperty(null, true, 5, ConnectorPropertyType.ID_STRING_PROPERTY, 
                            true, true, clientId, null, null));
            
            log.info("{} - Configured Cumulocity MQTT Service: {}:{}", tenant, mqttHost, mqttPort);
            
        } catch (Exception e) {
            log.error("{} - Error configuring Cumulocity MQTT Service, using defaults: {}", 
                    tenant, e.getMessage(), e);
        }
    }

    /**
     * Create connector specification for Cumulocity MQTT Service
     */
    private ConnectorSpecification createCumulocityMqttServiceSpecification() {
        Map<String, ConnectorProperty> configProps = new LinkedHashMap<>();
        
        configProps.put("version",
                new ConnectorProperty(null, true, 0, ConnectorPropertyType.OPTION_PROPERTY, 
                        true, false, MQTT_VERSION_3_1_1,
                        Map.of(MQTT_VERSION_3_1_1, MQTT_VERSION_3_1_1, 
                               MQTT_VERSION_5_0, MQTT_VERSION_5_0),
                        null));
        
        configProps.put("protocol",
                new ConnectorProperty(null, true, 1, ConnectorPropertyType.OPTION_PROPERTY, 
                        true, true, MQTT_PROTOCOL_MQTT,
                        Map.of(
                                MQTT_PROTOCOL_MQTT, MQTT_PROTOCOL_MQTT,
                                MQTT_PROTOCOL_MQTTS, MQTT_PROTOCOL_MQTTS,
                                MQTT_PROTOCOL_WS, MQTT_PROTOCOL_WS,
                                MQTT_PROTOCOL_WSS, MQTT_PROTOCOL_WSS),
                        null));
        
        configProps.put("mqttHost",
                new ConnectorProperty(null, true, 2, ConnectorPropertyType.STRING_PROPERTY, 
                        true, true, "c8y-mqtt-service", null, null));
        
        configProps.put("mqttPort",
                new ConnectorProperty(null, true, 3, ConnectorPropertyType.NUMERIC_PROPERTY, 
                        true, true, 2883, null, null));
        
        configProps.put("user",
                new ConnectorProperty(null, true, 4, ConnectorPropertyType.STRING_PROPERTY, 
                        true, true, null, null, null));
        
        configProps.put("password",
                new ConnectorProperty(null, true, 5, ConnectorPropertyType.SENSITIVE_STRING_PROPERTY, 
                        true, true, null, null, null));
        
        configProps.put("clientId",
                new ConnectorProperty(null, true, 6, ConnectorPropertyType.ID_STRING_PROPERTY, 
                        true, true, null, null, null));
        
        configProps.put("useSelfSignedCertificate",
                new ConnectorProperty(null, false, 7, ConnectorPropertyType.BOOLEAN_PROPERTY, 
                        true, true, false, null, null));
        
        configProps.put("fingerprintSelfSignedCertificate",
                new ConnectorProperty(null, false, 8, ConnectorPropertyType.STRING_PROPERTY, 
                        true, true, null, null, null));
        
        configProps.put("nameCertificate",
                new ConnectorProperty(null, false, 9, ConnectorPropertyType.STRING_PROPERTY, 
                        true, true, null, null, null));
        
        configProps.put("supportsWildcardInTopicInbound",
                new ConnectorProperty(null, false, 10, ConnectorPropertyType.BOOLEAN_PROPERTY, 
                        true, false, false, null, null));
        
        configProps.put("supportsWildcardInTopicOutbound",
                new ConnectorProperty(null, false, 11, ConnectorPropertyType.BOOLEAN_PROPERTY, 
                        true, false, false, null, null));
        
        configProps.put("cleanSession",
                new ConnectorProperty(null, false, 12, ConnectorPropertyType.BOOLEAN_PROPERTY, 
                        true, false, true, null, null));
        
        String name = "Cumulocity MQTT Service - (Tenant Isolation)";
        String description = "Connector for connecting to Cumulocity MQTT Service. " +
                "The MQTT Service does not support wildcards, i.e. '+', '#'. " +
                "The QoS 'exactly once' is reduced to 'at least once'.";
        
        return new ConnectorSpecification(
                name,
                description,
                ConnectorType.CUMULOCITY_MQTT_SERVICE,
                true, // singleton
                configProps,
                false,
                supportedDirections());
    }

    /**
     * Generate client ID for Cumulocity MQTT Service
     */
    private static String getClientId(String identifier, String suffix) {
        return "CUMULOCITY_MQTT_SERVICE_" + identifier + (suffix != null ? suffix : "");
    }

    @Override
    public Boolean supportsWildcardInTopic(Direction direction) {
        // Cumulocity MQTT Service does not support wildcards
        if (direction == Direction.INBOUND) {
            return Boolean.parseBoolean(
                    connectorConfiguration.getProperties()
                            .getOrDefault("supportsWildcardInTopicInbound", "false").toString());
        } else {
            return Boolean.parseBoolean(
                    connectorConfiguration.getProperties()
                            .getOrDefault("supportsWildcardInTopicOutbound", "false").toString());
        }
    }

    @Override
    public String getConnectorIdentifier() {
        return connectorIdentifier;
    }

    @Override
    public String getConnectorName() {
        return connectorName;
    }

    @Override
    public List<Direction> supportedDirections() {
        return Arrays.asList(Direction.INBOUND, Direction.OUTBOUND);
    }

    @Override
    protected void connectorSpecificHousekeeping(String tenant) {
        // Call parent housekeeping
        super.connectorSpecificHousekeeping(tenant);
        
        // Add any Cumulocity MQTT Service specific housekeeping here
        // Currently, standard MQTT3 housekeeping is sufficient
    }

    @Override
    public boolean initialize() {
        // Use parent initialization
        boolean success = super.initialize();
        
        if (success) {
            log.info("{} - Cumulocity MQTT Service connector initialized successfully", tenant);
        } else {
            log.error("{} - Cumulocity MQTT Service connector initialization failed", tenant);
        }
        
        return success;
    }
}