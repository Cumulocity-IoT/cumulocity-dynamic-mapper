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

import java.util.AbstractMap;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import com.cumulocity.microservice.context.credentials.MicroserviceCredentials;

import dynamic.mapping.connector.core.ConnectorPropertyType;
import dynamic.mapping.connector.core.ConnectorSpecification;
import dynamic.mapping.connector.core.client.ConnectorType;
import dynamic.mapping.processor.inbound.AsynchronousDispatcherInbound;
import dynamic.mapping.configuration.ConnectorConfiguration;
import dynamic.mapping.connector.core.ConnectorProperty;
import dynamic.mapping.core.ConfigurationRegistry;
import dynamic.mapping.core.ConnectorStatusEvent;
import dynamic.mapping.model.QOS;

public class MQTTServiceClient extends MQTTClient {
	public MQTTServiceClient() {
		Map<String, ConnectorProperty> configProps = new HashMap<>();
		configProps.put("protocol",
				new ConnectorProperty(true, 0, ConnectorPropertyType.OPTION_PROPERTY, true, true, "mqtt://",
						Map.ofEntries(
								new AbstractMap.SimpleEntry<String, String>("mqtt://", "mqtt://"),
								new AbstractMap.SimpleEntry<String, String>("mqtts://", "mqtts://"),
								new AbstractMap.SimpleEntry<String, String>("ws://", "ws://"),
								new AbstractMap.SimpleEntry<String, String>("wss://", "wss://"))));
		configProps.put("mqttHost",
				new ConnectorProperty(true, 1, ConnectorPropertyType.STRING_PROPERTY, true, true, "cumulocity",
						null));
		configProps.put("mqttPort",
				new ConnectorProperty(true, 2, ConnectorPropertyType.NUMERIC_PROPERTY, true, true, 2883, null));
		configProps.put("user",
				new ConnectorProperty(true, 3, ConnectorPropertyType.STRING_PROPERTY, true, true, null, null));
		configProps.put("password",
				new ConnectorProperty(true, 4, ConnectorPropertyType.SENSITIVE_STRING_PROPERTY, true, true, null,
						null));
		configProps.put("clientId",
				new ConnectorProperty(true, 5, ConnectorPropertyType.ID_STRING_PROPERTY, true, true,
						null, null));
		configProps.put("useSelfSignedCertificate",
				new ConnectorProperty(false, 6, ConnectorPropertyType.BOOLEAN_PROPERTY, true, true, false, null));
		configProps.put("fingerprintSelfSignedCertificate",
				new ConnectorProperty(false, 7, ConnectorPropertyType.STRING_PROPERTY, true, true, false, null));
		configProps.put("nameCertificate",
				new ConnectorProperty(false, 8, ConnectorPropertyType.STRING_PROPERTY, true, true, false, null));
		configProps.put("supportsWildcardInTopic",
				new ConnectorProperty(false, 9, ConnectorPropertyType.BOOLEAN_PROPERTY, true, true, false, null));
		String name = "Cumulocity IoT MQTT Service";
		String description = "Specific connector for connecting to Cumulocity MQTT Service. The MQTT Service does not support wildcards, i.e. '+', '#'. The QOS 'exactly once' is reduced to 'at least once'.";
		connectorType = ConnectorType.CUMULOCITY_MQTT_SERVICE;
		connectorSpecification = new ConnectorSpecification(name, description, connectorType, configProps, false);
	}

	private static Random random = new Random();

	private static String nextId() {
		return "CUMULOCITY_MQTT_SERVICE" + Integer.toString(random.nextInt(Integer.MAX_VALUE - 100000) + 100000, 36);
	}
	private static String getClientId(String ident, String suffix) {
		return "CUMULOCITY_MQTT_SERVICE" + ident + suffix;
	}
	// return random.nextInt(max - min) + min;

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
		this.connectorStatus = ConnectorStatusEvent.unknown(connectorConfiguration.name, connectorConfiguration.ident);
		this.c8yAgent = configurationRegistry.getC8yAgent();
		this.cachedThreadPool = configurationRegistry.getCachedThreadPool();
		this.objectMapper = configurationRegistry.getObjectMapper();
		this.additionalSubscriptionIdTest = additionalSubscriptionIdTest;
		this.mappingServiceRepresentation = configurationRegistry.getMappingServiceRepresentations().get(tenant);
		this.serviceConfiguration = configurationRegistry.getServiceConfigurations().get(tenant);
		this.dispatcher = dispatcher;
		this.tenant = tenant;
		MicroserviceCredentials msc = configurationRegistry.getMicroserviceCredential(tenant);
		String user = String.format("%s/%s", tenant, msc.getUsername());
		getConnectorSpecification().getProperties().put("user",
				new ConnectorProperty(true, 2, ConnectorPropertyType.STRING_PROPERTY, true, true, user, null));
		getConnectorSpecification().getProperties().put("password",
				new ConnectorProperty(true, 3, ConnectorPropertyType.SENSITIVE_STRING_PROPERTY, true, true,
						msc.getPassword(), null));
		getConnectorSpecification().getProperties().put("clientId", new ConnectorProperty(true, 5, ConnectorPropertyType.ID_STRING_PROPERTY, true, true,
				getClientId(this.connectorIdent, this.additionalSubscriptionIdTest), null));
		this.supportedQOS = Arrays.asList(QOS.AT_LEAST_ONCE, QOS.AT_MOST_ONCE);
	}

	@Override
	public Boolean supportsWildcardsInTopic() {
		return false;
	}
}