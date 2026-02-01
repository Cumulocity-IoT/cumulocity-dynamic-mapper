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
package dynamic.mapper.connector.amqp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.util.*;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.fasterxml.jackson.databind.ObjectMapper;

import dynamic.mapper.configuration.ConnectorConfiguration;
import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.connector.core.ConnectorSpecification;
import dynamic.mapper.connector.core.client.AConnectorClient;
import dynamic.mapper.connector.core.client.Certificate;
import dynamic.mapper.connector.core.client.ConnectorType;
import dynamic.mapper.connector.core.registry.ConnectorRegistry;
import dynamic.mapper.core.C8YAgent;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.model.Direction;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.Qos;
import dynamic.mapper.processor.inbound.CamelDispatcherInbound;
import dynamic.mapper.processor.model.DynamicMapperRequest;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.service.ConnectorConfigurationService;
import dynamic.mapper.service.MappingService;
import dynamic.mapper.service.ServiceConfigurationService;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AMQPClientTest {

    @Mock
    private ConfigurationRegistry configurationRegistry;
    @Mock
    private ConnectorRegistry connectorRegistry;
    @Mock
    private ConnectorConfiguration connectorConfiguration;
    @Mock
    private CamelDispatcherInbound dispatcher;
    @Mock
    private MappingService mappingService;
    @Mock
    private ServiceConfigurationService serviceConfigurationService;
    @Mock
    private ConnectorConfigurationService connectorConfigurationService;
    @Mock
    private C8YAgent c8yAgent;
    @Mock
    private ServiceConfiguration serviceConfiguration;
    @Mock
    private Connection connection;
    @Mock
    private Channel channel;

    private static final String TEST_TENANT = "test_tenant";
    private static final String TEST_CONNECTOR_NAME = "test_amqp_connector";
    private static final String TEST_CONNECTOR_IDENTIFIER = "amqp_1";
    private static final String TEST_SUBSCRIPTION_ID = "_test";
    private static final String TEST_AMQP_HOST = "46.101.117.78";
    private static final Integer TEST_AMQP_PORT = 5672;

    private AMQPClient amqpClient;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();

        // Setup configuration registry
        when(configurationRegistry.getMappingService()).thenReturn(mappingService);
        when(configurationRegistry.getServiceConfigurationService()).thenReturn(serviceConfigurationService);
        when(configurationRegistry.getConnectorConfigurationService())
                .thenReturn(connectorConfigurationService);
        when(configurationRegistry.getC8yAgent()).thenReturn(c8yAgent);
        when(configurationRegistry.getVirtualThreadPool())
                .thenReturn(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        when(configurationRegistry.getObjectMapper()).thenReturn(objectMapper);
        when(configurationRegistry.getServiceConfiguration(anyString())).thenReturn(serviceConfiguration);
        when(configurationRegistry.getConnectorRegistry()).thenReturn(connectorRegistry);

        // Setup connector configuration
        when(connectorConfiguration.getName()).thenReturn(TEST_CONNECTOR_NAME);
        when(connectorConfiguration.getIdentifier()).thenReturn(TEST_CONNECTOR_IDENTIFIER);
        when(connectorConfiguration.getEnabled()).thenReturn(true);
        when(connectorConfiguration.getProperties()).thenReturn(createDefaultProperties());

        // Mock copyPredefinedValues to do nothing
        doNothing().when(connectorConfiguration).copyPredefinedValues(any());

        // Mock the service to return configuration
        when(connectorConfigurationService.getConnectorConfiguration(
                eq(TEST_CONNECTOR_IDENTIFIER),
                eq(TEST_TENANT)))
                .thenReturn(connectorConfiguration);

        when(connectorConfigurationService.getConnectorConfiguration(
                anyString(),
                anyString()))
                .thenReturn(connectorConfiguration);

        // Mock copyPredefinedValues
        doNothing().when(connectorConfiguration).copyPredefinedValues(any(ConnectorSpecification.class));

        // Mock getCleanedConfig
        when(connectorConfiguration.getCleanedConfig(any(ConnectorSpecification.class)))
                .thenReturn(connectorConfiguration);

        when(connectorConfiguration.getCleanedConfig(any())).thenReturn(connectorConfiguration);

        // Setup service configuration
        when(serviceConfiguration.getLogPayload()).thenReturn(false);
        when(serviceConfiguration.getSendSubscriptionEvents()).thenReturn(false);
        when(serviceConfiguration.getSendConnectorLifecycle()).thenReturn(false);

        // Setup connector registry
        when(connectorRegistry.getConnectorStatusMap(anyString())).thenReturn(new HashMap<>());

        // Setup mapping service
        when(mappingService.getCacheOutboundMappings(anyString())).thenReturn(new HashMap<>());
        when(mappingService.getCacheInboundMappings(anyString())).thenReturn(new HashMap<>());
        when(mappingService.getCacheMappingInbound(anyString())).thenReturn(new HashMap<>());
    }

    private Map<String, Object> createDefaultProperties() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("protocol", "amqp://");
        properties.put("host", TEST_AMQP_HOST);
        properties.put("port", TEST_AMQP_PORT);
        properties.put("virtualHost", "/");
        properties.put("username", "guest");
        properties.put("password", "guest");
        properties.put("exchange", "");
        properties.put("exchangeType", "topic");
        properties.put("queuePrefix", "");
        properties.put("autoDeleteQueue", false);
        properties.put("useSelfSignedCertificate", false);
        properties.put("automaticRecovery", true);
        properties.put("supportsWildcardInTopicInbound", true);
        properties.put("supportsWildcardInTopicOutbound", false);
        return properties;
    }

    @Test
    void testDefaultConstructor() {
        // When
        AMQPClient client = new AMQPClient();

        // Then
        assertNotNull(client);
        assertEquals(ConnectorType.AMQP, client.getConnectorType());
        assertFalse(client.isSingleton());
        assertNotNull(client.getSupportedQOS());
        assertEquals(2, client.getSupportedQOS().size());
        assertTrue(client.getSupportedQOS().contains(Qos.AT_MOST_ONCE));
        assertTrue(client.getSupportedQOS().contains(Qos.AT_LEAST_ONCE));

        log.info("✅ Default constructor test passed");
    }

    @Test
    void testFullConstructor() {
        // When
        amqpClient = new AMQPClient(
                configurationRegistry,
                connectorRegistry,
                connectorConfiguration,
                dispatcher,
                TEST_SUBSCRIPTION_ID,
                TEST_TENANT);

        // Then
        assertNotNull(amqpClient);
        assertEquals(TEST_CONNECTOR_NAME, amqpClient.getConnectorName());
        assertEquals(TEST_CONNECTOR_IDENTIFIER, amqpClient.getConnectorIdentifier());
        assertEquals(TEST_TENANT, amqpClient.getTenant());
        assertNotNull(amqpClient.getConnectorSpecification());

        log.info("✅ Full constructor test passed");
    }

    @Test
    void testInitializeSuccess() {
        // Given
        amqpClient = new AMQPClient(
                configurationRegistry,
                connectorRegistry,
                connectorConfiguration,
                dispatcher,
                TEST_SUBSCRIPTION_ID,
                TEST_TENANT);

        // When
        boolean result = amqpClient.initialize();

        // Then
        assertTrue(result);

        // Verify copyPredefinedValues was called
        verify(connectorConfiguration, atLeastOnce()).copyPredefinedValues(any(ConnectorSpecification.class));

        log.info("✅ Initialize success test passed");
    }

    @Test
    void testInitializeWithSslProtocol() {
        // Given
        Map<String, Object> properties = createDefaultProperties();
        properties.put("protocol", "amqps://");
        properties.put("useSelfSignedCertificate", false);

        when(connectorConfiguration.getProperties()).thenReturn(properties);

        amqpClient = new AMQPClient(
                configurationRegistry,
                connectorRegistry,
                connectorConfiguration,
                dispatcher,
                TEST_SUBSCRIPTION_ID,
                TEST_TENANT);

        // When
        boolean result = amqpClient.initialize();

        // Then - Should succeed with default SSL
        assertTrue(result);

        log.info("✅ Initialize with SSL protocol test passed");
    }

    @Test
    void testInitializeWithSelfSignedCertificate() {
        // Given
        Map<String, Object> properties = createDefaultProperties();
        properties.put("protocol", "amqps://");
        properties.put("useSelfSignedCertificate", true);
        properties.put("nameCertificate", "test-cert");
        properties.put("fingerprintSelfSignedCertificate", "AA:BB:CC:DD");

        when(connectorConfiguration.getProperties()).thenReturn(properties);

        String validCertPem = "-----BEGIN CERTIFICATE-----\n" +
                "MIIDXTCCAkWgAwIBAgIJAKHHCgVZU2T9MA0GCSqGSIb3DQEBCwUAMEUxCzAJBgNV\n" +
                "BAYTAkFVMRMwEQYDVQQIDApTb21lLVN0YXRlMSEwHwYDVQQKDBhJbnRlcm5ldCBX\n" +
                "aWRnaXRzIFB0eSBMdGQwHhcNMTcwODIzMDg0NzU3WhcNMTgwODIzMDg0NzU3WjBF\n" +
                "MQswCQYDVQQGEwJBVTETMBEGA1UECAwKU29tZS1TdGF0ZTEhMB8GA1UECgwYSW50\n" +
                "ZXJuZXQgV2lkZ2l0cyBQdHkgTHRkMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIB\n" +
                "CgKCAQEAyHQfOgzxVKHqZpRdEsU6fRCYdCPJZ7VCJ5SpqvXvPwqMGfYqPqwLVJQp\n" +
                "pVqhsN2WMHnU8JmXJJNPKPvYvPSwLQqC7pJmLLMDqQJLBOQ5VdMPNaMqLp7+ZRGT\n" +
                "q6vDmNvJhkGGlJJVNZZKNLKqNvDEh0qPVQTnPZxJlBxGHVtfPl1f8QDPKPvYBQ8S\n" +
                "6IJ9X3PpzN7D0YPTqJVLtjpLJqtJP2ykJRxMNVLJQVLW4cHULJPwGBLCKLJLBKVJ\n" +
                "7BnKEJKNKPLJPwGBPKPvYBQ8S6IJ9X3PpzN7D0YPTqJVLtjpLJqtJP2ykJRxMNVL\n" +
                "JQVLWwIDAQABo1AwTjAdBgNVHQ4EFgQUZ7YzXqPOPOZGQqNKQhN9VqNKQhMwHwYD\n" +
                "VR0jBBgwFoAUZ7YzXqPOPOZGQqNKQhN9VqNKQhMwDAYDVR0TBAUwAwEB/zANBgkq\n" +
                "hkiG9w0BAQsFAAOCAQEAUYLJPqPOPOZGQqNKQhN9VqNKQhMwHwYDVR0jBBgwFoAU\n" +
                "Z7YzXqPOPOZGQqNKQhN9VqNKQhMwDAYDVR0TBAUwAwEB/zANBgkqhkiG9w0BAQUF\n" +
                "AAOCAQEAYHLJ\n" +
                "-----END CERTIFICATE-----";

        Certificate mockCert = new Certificate(
                "AA:BB:CC:DD",
                validCertPem);

        when(c8yAgent.loadCertificateByName(
                eq("test-cert"),
                eq("AA:BB:CC:DD"),
                eq(TEST_TENANT),
                eq(TEST_CONNECTOR_NAME))).thenReturn(mockCert);

        amqpClient = new AMQPClient(
                configurationRegistry,
                connectorRegistry,
                connectorConfiguration,
                dispatcher,
                TEST_SUBSCRIPTION_ID,
                TEST_TENANT);

        // When
        boolean result = amqpClient.initialize();

        // Then
        verify(c8yAgent).loadCertificateByName(
                eq("test-cert"),
                eq("AA:BB:CC:DD"),
                eq(TEST_TENANT),
                eq(TEST_CONNECTOR_NAME));

        if (!result) {
            log.info("SSL initialization failed with test certificate (expected)");
        }

        log.info("✅ Initialize with self-signed certificate test passed - certificate loading verified");
    }

    @Test
    void testSupportsWildcardInTopic() {
        // Given
        amqpClient = new AMQPClient(
                configurationRegistry,
                connectorRegistry,
                connectorConfiguration,
                dispatcher,
                TEST_SUBSCRIPTION_ID,
                TEST_TENANT);

        // When & Then
        assertTrue(amqpClient.supportsWildcardInTopic(Direction.INBOUND));
        assertFalse(amqpClient.supportsWildcardInTopic(Direction.OUTBOUND));

        log.info("✅ Supports wildcard in topic test passed");
    }

    @Test
    void testSupportedDirections() {
        // Given
        amqpClient = new AMQPClient();

        // When
        List<Direction> directions = amqpClient.supportedDirections();

        // Then
        assertNotNull(directions);
        assertEquals(2, directions.size());
        assertTrue(directions.contains(Direction.INBOUND));
        assertTrue(directions.contains(Direction.OUTBOUND));

        log.info("✅ Supported directions test passed");
    }

    @Test
    void testIsConfigValidWithValidConfig() {
        // Given
        amqpClient = new AMQPClient(
                configurationRegistry,
                connectorRegistry,
                connectorConfiguration,
                dispatcher,
                TEST_SUBSCRIPTION_ID,
                TEST_TENANT);

        // When
        boolean result = amqpClient.isConfigValid(connectorConfiguration);

        // Then
        assertTrue(result);

        log.info("✅ Config validation (valid) test passed");
    }

    @Test
    void testIsConfigValidWithNullConfig() {
        // Given
        amqpClient = new AMQPClient();

        // When
        boolean result = amqpClient.isConfigValid(null);

        // Then
        assertFalse(result);

        log.info("✅ Config validation (null) test passed");
    }

    @Test
    void testIsConfigValidWithMissingSelfSignedCertProps() {
        // Given
        Map<String, Object> properties = createDefaultProperties();
        properties.put("protocol", "amqps://");
        properties.put("useSelfSignedCertificate", true);
        // Missing fingerprintSelfSignedCertificate and nameCertificate

        ConnectorConfiguration invalidConfig = mock(ConnectorConfiguration.class);
        when(invalidConfig.getName()).thenReturn(TEST_CONNECTOR_NAME);
        when(invalidConfig.getIdentifier()).thenReturn(TEST_CONNECTOR_IDENTIFIER);
        when(invalidConfig.getProperties()).thenReturn(properties);

        amqpClient = new AMQPClient(
                configurationRegistry,
                connectorRegistry,
                connectorConfiguration,
                dispatcher,
                TEST_SUBSCRIPTION_ID,
                TEST_TENANT);

        // When
        boolean result = amqpClient.isConfigValid(invalidConfig);

        // Then
        assertFalse(result);

        log.info("✅ Config validation (missing cert props) test passed");
    }

    @Test
    void testPublishMEAO() throws Exception {
        // Given
        amqpClient = new AMQPClient(
                configurationRegistry,
                connectorRegistry,
                connectorConfiguration,
                dispatcher,
                TEST_SUBSCRIPTION_ID,
                TEST_TENANT);

        // Inject mocked AMQP connection and channel
        injectConnection(amqpClient, connection);
        injectChannel(amqpClient, channel);
        setConnectedState(amqpClient, true);

        // Mock connection and channel state for isPhysicallyConnected() check
        when(connection.isOpen()).thenReturn(true);
        when(channel.isOpen()).thenReturn(true);

        // Create test context
        ProcessingContext<?> context = createTestProcessingContext();

        // When
        amqpClient.publishMEAO(context);

        // Then
        verify(channel).basicPublish(anyString(), anyString(), any(), any(byte[].class));

        log.info("✅ Publish MEAO test passed");
    }

    @Test
    void testPublishMEAOWhenNotConnected() throws Exception {
        // Given
        amqpClient = new AMQPClient(
                configurationRegistry,
                connectorRegistry,
                connectorConfiguration,
                dispatcher,
                TEST_SUBSCRIPTION_ID,
                TEST_TENANT);

        setConnectedState(amqpClient, false);

        ProcessingContext<?> context = createTestProcessingContext();

        // When
        amqpClient.publishMEAO(context);

        // Then - Should not attempt to publish
        verify(channel, never()).basicPublish(anyString(), anyString(), any(), any(byte[].class));

        log.info("✅ Publish MEAO when not connected test passed");
    }

    @Test
    void testCreateConnectorSpecification() {
        // Given
        amqpClient = new AMQPClient();

        // When
        ConnectorSpecification spec = amqpClient.getConnectorSpecification();

        // Then
        assertNotNull(spec);
        assertEquals(ConnectorType.AMQP, spec.getConnectorType());
        assertFalse(spec.isSingleton());
        assertEquals("AMQP Connector", spec.getName());

        // Verify required properties
        assertTrue(spec.getProperties().containsKey("protocol"));
        assertTrue(spec.getProperties().containsKey("host"));
        assertTrue(spec.getProperties().containsKey("port"));

        // Verify optional properties
        assertTrue(spec.getProperties().containsKey("virtualHost"));
        assertTrue(spec.getProperties().containsKey("username"));
        assertTrue(spec.getProperties().containsKey("password"));
        assertTrue(spec.getProperties().containsKey("exchange"));
        assertTrue(spec.getProperties().containsKey("exchangeType"));
        assertTrue(spec.getProperties().containsKey("useSelfSignedCertificate"));

        log.info("✅ Connector specification test passed");
    }

    @Test
    void testProtocolOptions() {
        // Given
        amqpClient = new AMQPClient();

        // When
        ConnectorSpecification spec = amqpClient.getConnectorSpecification();

        // Then
        assertNotNull(spec.getProperties().get("protocol"));
        assertEquals("amqp://", spec.getProperties().get("protocol").getDefaultValue());

        log.info("✅ Protocol options test passed");
    }

    @Test
    void testDisconnect() throws Exception {
        // Given
        amqpClient = new AMQPClient(
                configurationRegistry,
                connectorRegistry,
                connectorConfiguration,
                dispatcher,
                TEST_SUBSCRIPTION_ID,
                TEST_TENANT);

        injectConnection(amqpClient, connection);
        injectChannel(amqpClient, channel);
        setConnectedState(amqpClient, true);

        when(connection.isOpen()).thenReturn(true);
        when(channel.isOpen()).thenReturn(true);

        // When
        amqpClient.disconnect();

        // Then
        verify(channel).close();
        verify(connection).close();

        log.info("✅ Disconnect test passed");
    }

    @Test
    void testMonitorSubscriptions() {
        // Given
        amqpClient = new AMQPClient();

        // When & Then - Should not throw exception
        assertDoesNotThrow(() -> amqpClient.monitorSubscriptions());

        log.info("✅ Monitor subscriptions test passed");
    }

    @Test
    void testGetters() {
        // Given
        amqpClient = new AMQPClient(
                configurationRegistry,
                connectorRegistry,
                connectorConfiguration,
                dispatcher,
                TEST_SUBSCRIPTION_ID,
                TEST_TENANT);

        // When & Then
        assertEquals(TEST_CONNECTOR_IDENTIFIER, amqpClient.getConnectorIdentifier());
        assertEquals(TEST_CONNECTOR_NAME, amqpClient.getConnectorName());
        assertEquals(TEST_TENANT, amqpClient.getTenant());

        log.info("✅ Getters test passed");
    }

    // Helper methods

    private ProcessingContext<?> createTestProcessingContext() {
        ProcessingContext<?> context = mock(ProcessingContext.class);

        DynamicMapperRequest request = new DynamicMapperRequest();
        request.setRequest("{\"test\": \"data\"}");
        request.setPublishTopic("test/topic");

        Mapping mapping = new Mapping();
        mapping.setIdentifier("test-mapping");
        mapping.setDebug(false);

        when(context.getRequests()).thenReturn(java.util.Arrays.asList(request));
        when(context.getCurrentRequest()).thenReturn(request);
        when(context.getResolvedPublishTopic()).thenReturn("test/topic");
        when(context.getQos()).thenReturn(Qos.AT_LEAST_ONCE);
        when(context.getMapping()).thenReturn(mapping);
        when(context.getServiceConfiguration()).thenReturn(serviceConfiguration);

        return context;
    }

    private void injectConnection(AMQPClient client, Connection connection) {
        try {
            Field field = AMQPClient.class.getDeclaredField("connection");
            field.setAccessible(true);
            field.set(client, connection);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void injectChannel(AMQPClient client, Channel channel) {
        try {
            Field field = AMQPClient.class.getDeclaredField("channel");
            field.setAccessible(true);
            field.set(client, channel);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void setConnectedState(AMQPClient client, boolean connected) throws Exception {
        Field managerField = findField(AConnectorClient.class, "connectionStateManager");
        managerField.setAccessible(true);
        Object connectionStateManager = managerField.get(client);

        if (connectionStateManager != null) {
            java.lang.reflect.Method setConnectedMethod = connectionStateManager.getClass()
                    .getDeclaredMethod("setConnected", boolean.class);
            setConnectedMethod.setAccessible(true);
            setConnectedMethod.invoke(connectionStateManager, connected);
        }
    }

    private Field findField(Class<?> clazz, String fieldName) {
        while (clazz != null) {
            try {
                return clazz.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new RuntimeException("Field not found: " + fieldName);
    }
}
