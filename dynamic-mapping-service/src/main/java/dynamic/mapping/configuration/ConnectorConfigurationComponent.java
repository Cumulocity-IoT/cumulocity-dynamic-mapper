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
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class ConnectorConfigurationComponent {
	private static final String OPTION_CATEGORY_CONFIGURATION = "dynamic.mapper.service";
	private static final String OPTION_KEY_CONNECTIOR_PREFIX = "credentials.connection.configuration";

	private final TenantOptionApi tenantOptionApi;

	@Autowired
	private MicroserviceSubscriptionsService subscriptionsService;

	private ObjectMapper objectMapper;

	@Autowired
	public void setObjectMapper(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	@Autowired
	public ConnectorConfigurationComponent(TenantOptionApi tenantOptionApi) {
		this.tenantOptionApi = tenantOptionApi;
	}

	public String getConnectorOptionKey(String ident) {
		return OPTION_KEY_CONNECTIOR_PREFIX + "." + ident;
	}

	public void saveConnectorConfiguration(final ConnectorConfiguration configuration)
			throws JsonProcessingException {
		if (configuration == null) {
			return;
		}
		String ident = configuration.getIdent();
		final String configurationJson = objectMapper.writeValueAsString(configuration);
		final OptionRepresentation optionRepresentation = OptionRepresentation
				.asOptionRepresentation(OPTION_CATEGORY_CONFIGURATION, getConnectorOptionKey(ident), configurationJson);
		tenantOptionApi.save(optionRepresentation);
	}

	public void deleteConnectorConfiguration(final String ident)
			throws JsonProcessingException {
		if (ident == null) {
			return;
		}
		final OptionPK option = new OptionPK();
		option.setCategory(OPTION_CATEGORY_CONFIGURATION);
		option.setKey(getConnectorOptionKey(ident));
		tenantOptionApi.delete(option);
	}

	public ConnectorConfiguration getConnectorConfiguration(String ident, String tenant) {
		final OptionPK option = new OptionPK();
		option.setCategory(OPTION_CATEGORY_CONFIGURATION);
		option.setKey(getConnectorOptionKey(ident));
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
				rt = null;
			} catch (JsonMappingException e) {
				e.printStackTrace();
			} catch (JsonProcessingException e) {
				e.printStackTrace();
			}
			return rt;
		});
		return result;
	}

	public List<ConnectorConfiguration> getConnectorConfigurations(String tenant) {
		final List<ConnectorConfiguration> connectorConfigurations = new ArrayList<>();
		subscriptionsService.runForTenant(tenant, () -> {
			try {
				final List<OptionRepresentation> optionRepresentationList = tenantOptionApi
						.getAllOptionsForCategory(OPTION_CATEGORY_CONFIGURATION);
				for (OptionRepresentation optionRepresentation : optionRepresentationList) {
					// Just Connector Config --> Ignoring Service Configuration
					String optionKey = OPTION_KEY_CONNECTIOR_PREFIX.replace("credentials.", "");
					if (optionRepresentation.getKey().startsWith(optionKey)) {
						final ConnectorConfiguration configuration = objectMapper.readValue(
								optionRepresentation.getValue(),
								ConnectorConfiguration.class);
						connectorConfigurations.add(configuration);
						log.debug("Tenant {} - Connection configuration found: {}:", tenant,
								configuration.getConnectorType());
					}
				}
			} catch (SDKException exception) {
				log.warn("Tenant {} - No configuration found, returning empty element!", tenant);
			} catch (JsonMappingException e) {
				e.printStackTrace();
			} catch (JsonProcessingException e) {
				e.printStackTrace();
			}
		});
		return connectorConfigurations;
	}

	public void deleteConnectorConfigurations(String tenant) {
		List<ConnectorConfiguration> configs = getConnectorConfigurations(tenant);
		for (ConnectorConfiguration config : configs) {
			OptionPK optionPK = new OptionPK(OPTION_CATEGORY_CONFIGURATION,
					getConnectorOptionKey(config.getIdent()));
			tenantOptionApi.delete(optionPK);
		}
	}

	public ConnectorConfiguration enableConnection(String connectorIdent, boolean enabled) {
		final OptionPK option = new OptionPK(OPTION_CATEGORY_CONFIGURATION, getConnectorOptionKey(connectorIdent));
		String tenant = subscriptionsService.getTenant();
		try {
			final OptionRepresentation optionRepresentation = tenantOptionApi.getOption(option);
			final ConnectorConfiguration configuration = objectMapper.readValue(optionRepresentation.getValue(),
					ConnectorConfiguration.class);

			configuration.enabled = enabled;
			log.debug("Tenant {} - Setting connection: {}:", tenant, configuration.enabled);
			final String configurationJson = objectMapper.writeValueAsString(configuration);
			optionRepresentation.setCategory(OPTION_CATEGORY_CONFIGURATION);
			optionRepresentation.setKey(getConnectorOptionKey(connectorIdent));
			optionRepresentation.setValue(configurationJson);
			tenantOptionApi.save(optionRepresentation);
			return configuration;
		} catch (SDKException exception) {
			log.warn("Tenant {} - No configuration found, returning empty element!", tenant);
			// exception.printStackTrace();
		} catch (JsonMappingException e) {
			e.printStackTrace();
		} catch (JsonProcessingException e) {
			e.printStackTrace();
		}
		return null;
	}
}
