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

package dynamic.mapper.connector.http;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dynamic.mapper.connector.core.ConnectorPropertyType;
import dynamic.mapper.connector.core.ConnectorSpecification;
import dynamic.mapper.connector.core.callback.ConnectorMessage;
import dynamic.mapper.connector.core.client.AConnectorClient;
import dynamic.mapper.connector.core.client.ConnectorException;
import dynamic.mapper.connector.core.client.ConnectorType;
import dynamic.mapper.model.Direction;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.Qos;
import dynamic.mapper.processor.inbound.CamelDispatcherInbound;
import dynamic.mapper.processor.model.ProcessingContext;
import org.apache.commons.lang3.mutable.MutableInt;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import dynamic.mapper.configuration.ConnectorConfiguration;
import dynamic.mapper.configuration.ConnectorId;
import dynamic.mapper.connector.core.ConnectorProperty;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.core.ConnectorStatus;
import dynamic.mapper.core.ConnectorStatusEvent;

@Slf4j
public class HttpClient extends AConnectorClient {
    public static final String HTTP_CONNECTOR_PATH = "httpConnector";
    public static final String HTTP_CONNECTOR_IDENTIFIER = "HTTP_CONNECTOR_IDENTIFIER";
    public static final String HTTP_CONNECTOR_ABSOLUTE_PATH = "/httpConnector";
    public static final String PROPERTY_CUTOFF_LEADING_SLASH = "cutOffLeadingSlash";

    public HttpClient() {
        Map<String, ConnectorProperty> configProps = new HashMap<>();
        String httpPath = new StringBuilder().append("/service/dynamic-mapper-service/").append(HTTP_CONNECTOR_PATH)
                .toString();
        configProps.put("path",
                new ConnectorProperty(null, false, 0, ConnectorPropertyType.STRING_PROPERTY, true, false, httpPath,
                        null, null));
        configProps.put("supportsWildcardInTopic",
                new ConnectorProperty(null, false, 1, ConnectorPropertyType.BOOLEAN_PROPERTY, true, false, true, null,
                        null));
        configProps.put(PROPERTY_CUTOFF_LEADING_SLASH,
                new ConnectorProperty(null, false, 2, ConnectorPropertyType.BOOLEAN_PROPERTY, false, false, true, null,
                        null));
        String name = "HTTP Endpoint";
        String description = "HTTP Endpoint to receive custom payload in the body.\n"
                + "The sub path following '.../dynamic-mapper-service/httpConnector/' is used as '<MAPPING_TOPIC>', e.g. a json payload send to 'https://<YOUR_CUMULOCITY_TENANT>/service/dynamic-mapper-service/httpConnector/temp/berlin_01' \n"
                + "will be resolved to a mapping with mapping topic: 'temp/berlin_01'.\n"
                + "The message must be send in a POST request.\n"
                + "NOTE: The leading '/' is cut off from the sub path automatically. This can be configured ";
        connectorType = ConnectorType.HTTP;
        connectorSpecification = new ConnectorSpecification(name, description, connectorType, singleton, configProps,
                false,
                supportedDirections());
    }

    public HttpClient(ConfigurationRegistry configurationRegistry,
            ConnectorConfiguration connectorConfiguration,
            CamelDispatcherInbound dispatcher, String additionalSubscriptionIdTest, String tenant) {
        this();
        this.configurationRegistry = configurationRegistry;
        this.mappingService = configurationRegistry.getMappingService();
        this.serviceConfigurationService = configurationRegistry.getServiceConfigurationService();
        this.connectorConfigurationService = configurationRegistry.getConnectorConfigurationService();
        this.connectorConfiguration = connectorConfiguration;
        // ensure the client knows its identity even if configuration is set to null
        this.connectorName = connectorConfiguration.name;
        this.connectorIdentifier = connectorConfiguration.identifier;
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
        this.supportedQOS = Arrays.asList(Qos.AT_LEAST_ONCE);
    }

    protected AConnectorClient.Certificate cert;

    @Getter
    protected List<Qos> supportedQOS;

    public boolean initialize() {
        loadConfiguration();
        log.info("{} - Phase 0: {} initialized, connectorType: {}", tenant,
                getConnectorName(), getConnectorType());
        return true;
    }

    @Override
    public void connect() {
        String path = (String) connectorSpecification.getProperties().get("path").defaultValue;
        log.info("{} - Phase I: {} connecting, isConnected: {}, shouldConnect: {}",
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
                log.info("{} - Phase III: {} connected, http endpoint: {}", tenant, getConnectorName(),
                        path);
                updateConnectorStatusAndSend(ConnectorStatus.CONNECTED, true, true);
                List<Mapping> updatedMappingsInbound = mappingService.rebuildMappingInboundCache(tenant, connectorId);
                initializeSubscriptionsInbound(updatedMappingsInbound, true, true);
                successful = true;
            } catch (Exception e) {
                log.error("{} - Phase III: {} failed to connect to http endpoint {}, {}, {}", tenant,
                        getConnectorName(),
                        path, e.getMessage(), connectionState.booleanValue(), e);
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
            log.info("{} - {} disconnecting, http endpoint: {}", tenant, getConnectorName(),
                    path);

            countSubscriptionsPerTopicInbound.entrySet().forEach(entry -> {
                // only unsubscribe if still active subscriptions exist
                // String topic = entry.getKey();
                MutableInt activeSubs = entry.getValue();
                if (activeSubs.intValue() > 0 && isConnected()) {
                    // do we have to do anything here?
                }
            });

            updateConnectorStatusAndSend(ConnectorStatus.DISCONNECTED, true, true);
            List<Mapping> updatedMappingsInbound = mappingService.rebuildMappingInboundCache(tenant, connectorId);
            initializeSubscriptionsInbound(updatedMappingsInbound, true, true);
            log.info("{} - {} disconnected, http endpoint: {}", tenant, getConnectorName(),
                    path);
        }
    }

    @Override
    public String getConnectorIdentifier() {
        return connectorIdentifier;
    }

    @Override
    public void subscribe(String topic, Qos qos) throws ConnectorException {
        log.debug("{} - Subscribing on topic: [{}] for connector: {}", tenant, topic, connectorName);
        sendSubscriptionEvents(topic, "Subscribing");
    }

    public void unsubscribe(String topic) throws Exception {
        log.debug("{} - Unsubscribing from topic: [{}]", tenant, topic);
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
    public void connectorSpecificHousekeeping(String tenant) {
    }

    @Override
    public List<Direction> supportedDirections() {
        return new ArrayList<>(Arrays.asList(Direction.INBOUND));
    }

}