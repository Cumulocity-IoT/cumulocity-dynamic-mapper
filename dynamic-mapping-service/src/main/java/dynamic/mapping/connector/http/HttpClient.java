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

package dynamic.mapping.connector.http;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dynamic.mapping.connector.core.ConnectorPropertyType;
import dynamic.mapping.connector.core.ConnectorSpecification;
import dynamic.mapping.connector.core.callback.ConnectorMessage;
import dynamic.mapping.connector.core.client.AConnectorClient;
import dynamic.mapping.connector.core.client.ConnectorException;
import dynamic.mapping.connector.core.client.ConnectorType;
import dynamic.mapping.model.Direction;
import dynamic.mapping.model.Mapping;
import dynamic.mapping.model.QOS;
import dynamic.mapping.processor.inbound.DispatcherInbound;
import dynamic.mapping.processor.model.ProcessingContext;
import org.apache.commons.lang3.mutable.MutableInt;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import dynamic.mapping.configuration.ConnectorConfiguration;
import dynamic.mapping.connector.core.ConnectorProperty;
import dynamic.mapping.core.ConfigurationRegistry;
import dynamic.mapping.core.ConnectorStatus;
import dynamic.mapping.core.ConnectorStatusEvent;

@Slf4j
public class HttpClient extends AConnectorClient {
    public static final String HTTP_CONNECTOR_PATH = "httpConnector";
    public static final String HTTP_CONNECTOR_IDENTIFIER = "HTTP_CONNECTOR_IDENTIFIER";
    public static final String HTTP_CONNECTOR_ABSOLUTE_PATH = "/httpConnector";
    public static final String PROPERTY_CUTOFF_LEADING_SLASH = "cutOffLeadingSlash";

    public HttpClient() {
        Map<String, ConnectorProperty> configProps = new HashMap<>();
        String httpPath = new StringBuilder().append("/service/dynamic-mapping-service/").append(HTTP_CONNECTOR_PATH)
                .toString();
        configProps.put("path",
                new ConnectorProperty(false, 0, ConnectorPropertyType.STRING_PROPERTY, true, false, httpPath, null));
        configProps.put("supportsWildcardInTopic",
                new ConnectorProperty(false, 1, ConnectorPropertyType.BOOLEAN_PROPERTY, true, false, true, null));
        configProps.put(PROPERTY_CUTOFF_LEADING_SLASH,
                new ConnectorProperty(false, 2, ConnectorPropertyType.BOOLEAN_PROPERTY, false, false, true, null));
        String name = "Generic Http Endpoint";
        String description = "Generic Http Endpoint to receive custom payload in the body.\n" 
                + "The sub path following '.../dynamic-mapping-service/httpConnector/' is used as '<MAPPING_TOPIC>', e.g. a json payload send to 'https://<YOUR_CUMULOCITY_TENANT>/service/dynamic-mapping-service/httpConnector/temp/berlin_01' \n" 
                + "will be resolved to a mapping with mapping topic: 'temp/berlin_01'.\n"
                + "The message must be send in a POST request.\n" 
                + "NOTE: The leading '/' is cut off from the sub path.This can be configured ";
        connectorType = ConnectorType.HTTP;
        connectorSpecification = new ConnectorSpecification(name, description, connectorType, configProps, false,supportedDirections());
    }

    public HttpClient(ConfigurationRegistry configurationRegistry,
            ConnectorConfiguration connectorConfiguration,
            DispatcherInbound dispatcher, String additionalSubscriptionIdTest, String tenant) {
        this();
        this.configurationRegistry = configurationRegistry;
        this.mappingComponent = configurationRegistry.getMappingComponent();
        this.serviceConfigurationComponent = configurationRegistry.getServiceConfigurationComponent();
        this.connectorConfigurationComponent = configurationRegistry.getConnectorConfigurationComponent();
        this.connectorConfiguration = connectorConfiguration;
        // ensure the client knows its identity even if configuration is set to null
        this.connectorName = connectorConfiguration.name;
        this.connectorIdentifier = connectorConfiguration.identifier;
        this.connectorStatus = ConnectorStatusEvent.unknown(connectorConfiguration.name,
                connectorConfiguration.identifier);
        this.c8yAgent = configurationRegistry.getC8yAgent();
        this.virtThreadPool = configurationRegistry.getVirtThreadPool();
        this.objectMapper = configurationRegistry.getObjectMapper();
        this.additionalSubscriptionIdTest = additionalSubscriptionIdTest;
        this.mappingServiceRepresentation = configurationRegistry.getMappingServiceRepresentations().get(tenant);
        this.serviceConfiguration = configurationRegistry.getServiceConfigurations().get(tenant);
        this.dispatcher = dispatcher;
        this.tenant = tenant;
        this.supportedQOS = Arrays.asList();
    }

    protected AConnectorClient.Certificate cert;

    @Getter
    protected List<QOS> supportedQOS;

    public boolean initialize() {
        loadConfiguration();

        log.info("Tenant {} - Connector {} - Initialization of connector {} was successful!", tenant,
                getConnectorType(),
                getConnectorName());
        return true;
    }

    @Override
    public void connect() {
        String path = (String) connectorSpecification.getProperties().get("path").defaultValue;
        log.info("Tenant {} - Trying to connect to {} - phase I: (isConnected:shouldConnect) ({}:{})",
                tenant, getConnectorName(), isConnected(),
                shouldConnect());
        if (isConnected())
            disconnect();

        if (shouldConnect())
            updateConnectorStatusAndSend(ConnectorStatus.CONNECTING, true, shouldConnect());

        // stay in the loop until successful
        boolean successful = false;
        while (!successful) {
            loadConfiguration();
            try {
                connectionState.setTrue();
                log.info("Tenant {} - Connected to http endpoint {}", tenant,
                        path);
                updateConnectorStatusAndSend(ConnectorStatus.CONNECTED, true, true);
                List<Mapping> updatedMappingsInbound = mappingComponent.rebuildMappingInboundCache(tenant);
                updateActiveSubscriptionsInbound(updatedMappingsInbound, true);
                successful = true;
            } catch (Exception e) {
                log.error("Tenant {} - Connected to http endpoint {}, {}, {}", tenant,
                        path, e.getMessage(), connectionState.booleanValue());
                updateConnectorStatusToFailed(e);
                sendConnectorLifecycle();
            }
        }
    }

    @Override
    public void close() {
    }

    @Override
    public boolean isConfigValid(ConnectorConfiguration configuration) {
        return true;
    }

    @Override
    public boolean isConnected() {
        return connectionState.booleanValue();
    }

    @Override
    public void disconnect() {
        if (isConnected()) {
            String path = (String) connectorSpecification.getProperties().get("path").defaultValue;
            updateConnectorStatusAndSend(ConnectorStatus.DISCONNECTING, true, true);
            log.info("Tenant {} - Disconnecting from http endpoint {}", tenant,
                    path);

            activeSubscriptions.entrySet().forEach(entry -> {
                // only unsubscribe if still active subscriptions exist
                String topic = entry.getKey();
                MutableInt activeSubs = entry.getValue();
                if (activeSubs.intValue() > 0 && isConnected()) {
                    // do we have to do anything here?
                }
            });

            updateConnectorStatusAndSend(ConnectorStatus.DISCONNECTED, true, true);
            List<Mapping> updatedMappingsInbound = mappingComponent.rebuildMappingInboundCache(tenant);
            updateActiveSubscriptionsInbound(updatedMappingsInbound, true);
            log.info("Tenant {} - Disconnected from http endpoint II: {}", tenant,
                    path);
        }
    }

    @Override
    public String getConnectorIdent() {
        return connectorIdentifier;
    }

    @Override
    public void subscribe(String topic, QOS qos) throws ConnectorException {
        log.debug("Tenant {} - Subscribing on topic: {} for connector {}", tenant, topic, connectorName);
        sendSubscriptionEvents(topic, "Subscribing");
    }

    public void unsubscribe(String topic) throws Exception {
        log.debug("Tenant {} - Unsubscribing from topic: {}", tenant, topic);
        sendSubscriptionEvents(topic, "Unsubscribing");
    }

    public void publishMEAO(ProcessingContext<?> context) {
        // do nothing
    }

    @Override
    public String getConnectorName() {
        return connectorName;
    }

    @Override
    public Boolean supportsWildcardsInTopic() {
        return Boolean.parseBoolean(connectorConfiguration.getProperties().get("supportsWildcardInTopic").toString());
    }

    @Override
    public void monitorSubscriptions() {
        // nothing to do
    }

    public void onMessage(ConnectorMessage message) {
        dispatcher.onMessage(message);
    }

    @Override
    public List<Direction>  supportedDirections() {
        return new ArrayList<>( Arrays.asList(Direction.INBOUND));
    }

}