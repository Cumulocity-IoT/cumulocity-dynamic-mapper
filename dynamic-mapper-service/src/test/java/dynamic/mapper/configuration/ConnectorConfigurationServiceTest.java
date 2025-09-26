/*
 * Copyright (c) 2025 Cumulocity GmbH.
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

package dynamic.mapper.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;
import com.cumulocity.model.option.OptionPK;
import com.cumulocity.rest.representation.tenant.OptionRepresentation;
import com.cumulocity.sdk.client.option.TenantOptionApi;
import com.fasterxml.jackson.databind.ObjectMapper;

import dynamic.mapper.connector.core.client.ConnectorType;
import dynamic.mapper.service.ConnectorConfigurationService;

@ExtendWith(MockitoExtension.class)
class ConnectorConfigurationServiceTest {

    @Mock
    private TenantOptionApi tenantOptionApi;

    @Mock
    private MicroserviceSubscriptionsService subscriptionsService;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private ConnectorConfigurationService configurationService;

    private static final String TEST_TENANT = "test-tenant";
    private static final String TEST_IDENTIFIER = "test-identifier";
    private static final String OPTION_CATEGORY = "dynMappingService";

    @BeforeEach
    void setUp() throws Exception {
        // Create the component with the mocked dependencies
        configurationService = new ConnectorConfigurationService(tenantOptionApi);
        
        // Use reflection to set the private subscriptionsService field
        Field subscriptionsServiceField = ConnectorConfigurationService.class.getDeclaredField("subscriptionsService");
        subscriptionsServiceField.setAccessible(true);
        subscriptionsServiceField.set(configurationService, subscriptionsService);
        
        // Set ObjectMapper
        configurationService.setObjectMapper(objectMapper);
    }

    @Test
    void testSaveConnectorConfiguration() throws Exception {
        // Arrange
        ConnectorConfiguration configuration = new ConnectorConfiguration();
        configuration.setIdentifier(TEST_IDENTIFIER);
        String jsonConfig = "{\"identifier\":\"test-identifier\"}";
        
        when(objectMapper.writeValueAsString(configuration)).thenReturn(jsonConfig);

        // Act
        configurationService.saveConnectorConfiguration(configuration);

        // Assert
        verify(tenantOptionApi).save(any(OptionRepresentation.class));
    }

    @Test
    void testGetConnectorConfiguration() throws Exception {
        // Arrange
        ConnectorConfiguration expectedConfig = new ConnectorConfiguration();
        expectedConfig.setConnectorType(ConnectorType.MQTT);
        expectedConfig.setIdentifier(TEST_IDENTIFIER);
        
        OptionRepresentation optionRepresentation = new OptionRepresentation();
        optionRepresentation.setValue("{\"identifier\":\"test-identifier\"}");

        when(tenantOptionApi.getOption(any(OptionPK.class))).thenReturn(optionRepresentation);
        when(objectMapper.readValue(anyString(), eq(ConnectorConfiguration.class))).thenReturn(expectedConfig);
        when(subscriptionsService.callForTenant(eq(TEST_TENANT), any())).thenAnswer(invocation -> {
            return ((java.util.concurrent.Callable<?>) invocation.getArgument(1)).call();
        });

        // Act
        ConnectorConfiguration result = configurationService.getConnectorConfiguration(TEST_IDENTIFIER, TEST_TENANT);

        // Assert
        assertNotNull(result);
        assertEquals(TEST_IDENTIFIER, result.getIdentifier());
    }

    @Test
    void testGetConnectorConfigurations() throws Exception {
        // Arrange
        List<OptionRepresentation> options = Arrays.asList(
            createOptionRepresentation("connection.configuration.1", "{\"identifier\":\"1\"}"),
            createOptionRepresentation("connection.configuration.2", "{\"identifier\":\"2\"}")
        );

        ConnectorConfiguration config1 = new ConnectorConfiguration();
        config1.setIdentifier("1");
        config1.setConnectorType(ConnectorType.MQTT);
        ConnectorConfiguration config2 = new ConnectorConfiguration();
        config2.setIdentifier("2");
        config2.setConnectorType(ConnectorType.MQTT);


        when(tenantOptionApi.getAllOptionsForCategory(OPTION_CATEGORY)).thenReturn(options);
        when(objectMapper.readValue(contains("1"), eq(ConnectorConfiguration.class))).thenReturn(config1);
        when(objectMapper.readValue(contains("2"), eq(ConnectorConfiguration.class))).thenReturn(config2);

        doAnswer(invocation -> {
            Runnable runnable = invocation.getArgument(1);
            runnable.run();
            return null;
        }).when(subscriptionsService).runForTenant(eq(TEST_TENANT), any(Runnable.class));

        // Act
        List<ConnectorConfiguration> results = configurationService.getConnectorConfigurations(TEST_TENANT);

        // Assert
        assertNotNull(results);
        assertEquals(2, results.size());
    }

    @Test
    void testDeleteConnectorConfiguration() throws Exception {
        // Act
        configurationService.deleteConnectorConfiguration(TEST_IDENTIFIER);

        // Assert
        verify(tenantOptionApi).delete(any(OptionPK.class));
    }

    @Test
    void testEnableConnection() throws Exception {
        // Arrange
        OptionRepresentation optionRepresentation = new OptionRepresentation();
        optionRepresentation.setValue("{\"identifier\":\"test-identifier\",\"enabled\":false}");
        
        ConnectorConfiguration config = new ConnectorConfiguration();
        config.setIdentifier(TEST_IDENTIFIER);
        config.setEnabled(false);

        when(tenantOptionApi.getOption(any(OptionPK.class))).thenReturn(optionRepresentation);
        when(objectMapper.readValue(anyString(), eq(ConnectorConfiguration.class))).thenReturn(config);
        when(objectMapper.writeValueAsString(any(ConnectorConfiguration.class)))
            .thenReturn("{\"identifier\":\"test-identifier\",\"enabled\":true}");
        when(subscriptionsService.getTenant()).thenReturn(TEST_TENANT);

        // Act
        ConnectorConfiguration result = configurationService.enableConnection(TEST_IDENTIFIER, true);

        // Assert
        assertNotNull(result);
        assertTrue(result.isEnabled());
        verify(tenantOptionApi).save(any(OptionRepresentation.class));
    }

    private OptionRepresentation createOptionRepresentation(String key, String value) {
        OptionRepresentation option = new OptionRepresentation();
        option.setKey(key);
        option.setValue(value);
        return option;
    }
}
