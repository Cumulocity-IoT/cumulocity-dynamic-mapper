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

package dynamic.mapper.connector.pulsar;

import com.cumulocity.microservice.context.credentials.MicroserviceCredentials;
import dynamic.mapper.configuration.ConnectorConfiguration;
import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.connector.core.ConnectorSpecification;
import dynamic.mapper.connector.core.client.ConnectorType;
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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pulsar.client.api.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MQTTServicePulsarClientTest {

    private static final String TEST_TENANT = "test_tenant";
    private static final String TEST_CONNECTOR_NAME = "test_mqtt_service_connector";
    private static final String TEST_CONNECTOR_IDENTIFIER = "mqtt_service_1";
    private static final String TEST_SUBSCRIPTION_ID = "_test";
    private static final String TEST_SERVICE_URL = "pulsar://localhost:6650";
    private static final String TEST_USERNAME = "test_user";
    private static final String TEST_PASSWORD = "test_password";

    @Mock
    private ConfigurationRegistry configurationRegistry;
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
    private MicroserviceCredentials microserviceCredentials;
    @Mock
    private PulsarClient pulsarClient;
    @Mock
    private Consumer<byte[]> platformConsumer;
    @Mock
    private Producer<byte[]> deviceProducer;
    @Mock
    private ConsumerBuilder<byte[]> consumerBuilder;
    @Mock
    private ProducerBuilder<byte[]> producerBuilder;
    @Mock
    private TypedMessageBuilder<byte[]> messageBuilder;

    private ExecutorService virtualThreadPool;
    private ObjectMapper objectMapper;
    private MQTTServicePulsarClient mqttServicePulsarClient;

    @BeforeEach
    void setUp() {
        virtualThreadPool = Executors.newFixedThreadPool(2);
        objectMapper = new ObjectMapper();

        // Setup configuration registry mocks
        lenient().when(configurationRegistry.getMappingService()).thenReturn(mappingService);
        lenient().when(configurationRegistry.getServiceConfigurationService()).thenReturn(serviceConfigurationService);
        lenient().when(configurationRegistry.getConnectorConfigurationService())
                .thenReturn(connectorConfigurationService);
        lenient().when(configurationRegistry.getC8yAgent()).thenReturn(c8yAgent);
        lenient().when(configurationRegistry.getVirtualThreadPool()).thenReturn(virtualThreadPool);
        lenient().when(configurationRegistry.getObjectMapper()).thenReturn(objectMapper);
        lenient().when(configurationRegistry.getServiceConfiguration(anyString())).thenReturn(serviceConfiguration);
        lenient().when(configurationRegistry.getMqttServicePulsarUrl()).thenReturn(TEST_SERVICE_URL);
        lenient().when(configurationRegistry.getMicroserviceCredential(anyString()))
                .thenReturn(microserviceCredentials);

        // Setup credentials
        lenient().when(microserviceCredentials.getUsername()).thenReturn(TEST_USERNAME);
        lenient().when(microserviceCredentials.getPassword()).thenReturn(TEST_PASSWORD);

        // Setup connector configuration
        lenient().when(connectorConfiguration.getName()).thenReturn(TEST_CONNECTOR_NAME);
        lenient().when(connectorConfiguration.getIdentifier()).thenReturn(TEST_CONNECTOR_IDENTIFIER);
        lenient().when(connectorConfiguration.isEnabled()).thenReturn(true);

        Map<String, Object> properties = new HashMap<>();
        properties.put("serviceUrl", TEST_SERVICE_URL);
        properties.put("enableTls", false);
        properties.put("authenticationMethod", "basic");
        properties.put("authenticationParams",
                String.format("{\"userId\":\"%s/%s\",\"password\":\"%s\"}",
                        TEST_TENANT, TEST_USERNAME, TEST_PASSWORD));
        properties.put("pulsarTenant", TEST_TENANT);
        properties.put("pulsarNamespace", MQTTServicePulsarClient.PULSAR_NAMESPACE);
        properties.put("connectionTimeoutSeconds", 30);
        properties.put("operationTimeoutSeconds", 30);
        properties.put("keepAliveIntervalSeconds", 30);
        lenient().when(connectorConfiguration.getProperties()).thenReturn(properties);

        // Setup service configuration
        lenient().when(serviceConfiguration.isLogPayload()).thenReturn(false);
        lenient().when(serviceConfiguration.isSendSubscriptionEvents()).thenReturn(false);

        // Setup mapping service
        lenient().when(mappingService.rebuildMappingInboundCache(anyString(), any())).thenReturn(new ArrayList<>());
        lenient().when(mappingService.rebuildMappingOutboundCache(anyString(), any())).thenReturn(new ArrayList<>());
    }

    @Test
    void testConstructor() {
        MQTTServicePulsarClient client = new MQTTServicePulsarClient();

        assertNotNull(client);
        assertEquals(ConnectorType.CUMULOCITY_MQTT_SERVICE_PULSAR, client.getConnectorType());
        assertTrue(client.isSingleton());
        assertNotNull(client.getSupportedQOS());
        assertEquals(2, client.getSupportedQOS().size());
        assertTrue(client.getSupportedQOS().contains(Qos.AT_MOST_ONCE));
        assertTrue(client.getSupportedQOS().contains(Qos.AT_LEAST_ONCE));
    }

    @Test
    void testFullConstructor() {
        mqttServicePulsarClient = new MQTTServicePulsarClient(
                configurationRegistry,
                connectorConfiguration,
                dispatcher,
                TEST_SUBSCRIPTION_ID,
                TEST_TENANT);

        assertNotNull(mqttServicePulsarClient);
        assertEquals(TEST_CONNECTOR_NAME, mqttServicePulsarClient.getConnectorName());
        assertEquals(TEST_CONNECTOR_IDENTIFIER, mqttServicePulsarClient.getConnectorIdentifier());
        assertEquals(TEST_TENANT, mqttServicePulsarClient.getTenant());
        assertNotNull(mqttServicePulsarClient.getConnectorSpecification());
    }

    // Helper methods

    private void setupMocksForConnect() throws PulsarClientException {
        // Consumer builder chain - needs to handle varargs topic(String...)
        when(pulsarClient.newConsumer()).thenReturn(consumerBuilder);
        when(consumerBuilder.topic(any(String[].class))).thenReturn(consumerBuilder);
        when(consumerBuilder.topic(anyString())).thenReturn(consumerBuilder);
        when(consumerBuilder.subscriptionName(anyString())).thenReturn(consumerBuilder);
        when(consumerBuilder.autoUpdatePartitions(anyBoolean())).thenReturn(consumerBuilder);
        when(consumerBuilder.messageListener(any())).thenReturn(consumerBuilder);
        when(consumerBuilder.subscribe()).thenReturn(platformConsumer);

        // Producer builder chain
        when(pulsarClient.newProducer()).thenReturn(producerBuilder);
        when(producerBuilder.topic(anyString())).thenReturn(producerBuilder);
        when(producerBuilder.create()).thenReturn(deviceProducer);

        when(pulsarClient.isClosed()).thenReturn(false);
    }

    private void setupMocksForPublish() throws PulsarClientException {
        when(deviceProducer.newMessage()).thenReturn(messageBuilder);
        when(messageBuilder.value(any(byte[].class))).thenReturn(messageBuilder);
        when(messageBuilder.property(anyString(), anyString())).thenReturn(messageBuilder);
        when(messageBuilder.key(anyString())).thenReturn(messageBuilder);
        when(messageBuilder.send()).thenReturn(null);
    }

    private ProcessingContext<?> createTestProcessingContext(Qos qos) {
        ProcessingContext<?> context = mock(ProcessingContext.class);
        DynamicMapperRequest request = new DynamicMapperRequest();
        request.setRequest("{\"test\": \"data\"}");

        Mapping mapping = new Mapping();
        mapping.setIdentifier("test-mapping");
        mapping.setName("Test Mapping");
        mapping.setQos(qos);
        mapping.setDebug(false);

        when(context.getCurrentRequest()).thenReturn(request);
        when(context.getResolvedPublishTopic()).thenReturn("test/topic");
        when(context.getMapping()).thenReturn(mapping);

        return context;
    }

    private void injectPulsarClient(MQTTServicePulsarClient client, PulsarClient pulsarClient) {
        try {
            Field field = PulsarConnectorClient.class.getDeclaredField("pulsarClient");
            field.setAccessible(true);
            field.set(client, pulsarClient);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void injectConsumerAndProducer(MQTTServicePulsarClient client,
            Consumer<byte[]> consumer,
            Producer<byte[]> producer) {
        try {
            Field consumerField = MQTTServicePulsarClient.class
                    .getDeclaredField("platformConsumer");
            consumerField.setAccessible(true);
            consumerField.set(client, consumer);

            Field producerField = MQTTServicePulsarClient.class
                    .getDeclaredField("deviceProducer");
            producerField.setAccessible(true);
            producerField.set(client, producer);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void injectTopicNames(MQTTServicePulsarClient client) {
        try {
            String namespace = MQTTServicePulsarClient.PULSAR_NAMESPACE;
            String towardsPlatformTopic = String.format("persistent://%s/%s/%s",
                    TEST_TENANT, namespace, MQTTServicePulsarClient.PULSAR_TOWARDS_PLATFORM_TOPIC);
            String towardsDeviceTopic = String.format("persistent://%s/%s/%s",
                    TEST_TENANT, namespace, MQTTServicePulsarClient.PULSAR_TOWARDS_DEVICE_TOPIC);

            Field towardsPlatformField = MQTTServicePulsarClient.class
                    .getDeclaredField("towardsPlatformTopic");
            towardsPlatformField.setAccessible(true);
            towardsPlatformField.set(client, towardsPlatformTopic);

            Field towardsDeviceField = MQTTServicePulsarClient.class
                    .getDeclaredField("towardsDeviceTopic");
            towardsDeviceField.setAccessible(true);
            towardsDeviceField.set(client, towardsDeviceTopic);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Field getConnectionStateManagerField(Object obj) throws NoSuchFieldException {
        // Try to get from AConnectorClient
        try {
            return obj.getClass().getSuperclass().getSuperclass().getDeclaredField("connectionStateManager");
        } catch (NoSuchFieldException e) {
            // If not found, might be in a different location
            throw e;
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T invokePrivateMethod(Object obj, String methodName, Class<?>[] paramTypes, Object... args)
            throws Exception {
        Method method = findMethod(obj.getClass(), methodName, paramTypes);
        method.setAccessible(true);
        return (T) method.invoke(obj, args);
    }

    @SuppressWarnings("unchecked")
    private <T> T invokePrivateStaticMethod(Class<?> clazz, String methodName, Class<?>[] paramTypes, Object... args)
            throws Exception {
        Method method = findMethod(clazz, methodName, paramTypes);
        method.setAccessible(true);
        return (T) method.invoke(null, args);
    }

    private Method findMethod(Class<?> clazz, String methodName, Class<?>[] paramTypes) throws NoSuchMethodException {
        try {
            return clazz.getDeclaredMethod(methodName, paramTypes);
        } catch (NoSuchMethodException e) {
            // Try superclass
            Class<?> superClass = clazz.getSuperclass();
            if (superClass != null) {
                return findMethod(superClass, methodName, paramTypes);
            }
            throw e;
        }
    }

    @Test
    void testConnectWhenDisabled() {
        when(connectorConfiguration.isEnabled()).thenReturn(false);

        mqttServicePulsarClient = spy(new MQTTServicePulsarClient(
                configurationRegistry,
                connectorConfiguration,
                dispatcher,
                TEST_SUBSCRIPTION_ID,
                TEST_TENANT));

        when(mqttServicePulsarClient.shouldConnect()).thenReturn(false);

        mqttServicePulsarClient.connect();

        verify(pulsarClient, never()).newConsumer();
    }

    @Test
    void testDisconnect() throws Exception {
        mqttServicePulsarClient = new MQTTServicePulsarClient(
                configurationRegistry,
                connectorConfiguration,
                dispatcher,
                TEST_SUBSCRIPTION_ID,
                TEST_TENANT);

        injectPulsarClient(mqttServicePulsarClient, pulsarClient);
        injectConsumerAndProducer(mqttServicePulsarClient, platformConsumer, deviceProducer);

        // Mock that we are connected
        Field connectionStateManagerField = getConnectionStateManagerField(mqttServicePulsarClient);
        connectionStateManagerField.setAccessible(true);
        Object connectionStateManager = connectionStateManagerField.get(mqttServicePulsarClient);

        Method setConnectedMethod = connectionStateManager.getClass().getDeclaredMethod("setConnected", boolean.class);
        setConnectedMethod.setAccessible(true);
        setConnectedMethod.invoke(connectionStateManager, true);

        when(pulsarClient.isClosed()).thenReturn(false);

        mqttServicePulsarClient.disconnect();

        verify(platformConsumer).close();
        verify(deviceProducer).close();
        verify(pulsarClient).close();
    }

    @Test
    void testDisconnectWhenNotConnected() throws PulsarClientException {
        mqttServicePulsarClient = spy(new MQTTServicePulsarClient(
                configurationRegistry,
                connectorConfiguration,
                dispatcher,
                TEST_SUBSCRIPTION_ID,
                TEST_TENANT));

        when(mqttServicePulsarClient.isConnected()).thenReturn(false);

        mqttServicePulsarClient.disconnect();

        verify(pulsarClient, never()).close();
    }

    @Test
    void testPublishMEAO_Success() throws PulsarClientException {
        setupMocksForPublish();

        mqttServicePulsarClient = new MQTTServicePulsarClient(
                configurationRegistry,
                connectorConfiguration,
                dispatcher,
                TEST_SUBSCRIPTION_ID,
                TEST_TENANT);

        injectPulsarClient(mqttServicePulsarClient, pulsarClient);
        injectConsumerAndProducer(mqttServicePulsarClient, platformConsumer, deviceProducer);
        injectTopicNames(mqttServicePulsarClient); // Add this line

        ProcessingContext<?> context = createTestProcessingContext(Qos.AT_LEAST_ONCE);

        when(deviceProducer.isConnected()).thenReturn(true);
        when(pulsarClient.isClosed()).thenReturn(false);

        mqttServicePulsarClient.publishMEAO(context);

        verify(deviceProducer).newMessage();
        verify(messageBuilder).value(any(byte[].class));
        verify(messageBuilder).property(eq(MQTTServicePulsarClient.PULSAR_PROPERTY_TOPIC), anyString());
        verify(messageBuilder).send();
    }

    @Test
    void testPublishMEAO_AtMostOnce() throws PulsarClientException {
        setupMocksForPublish();

        mqttServicePulsarClient = new MQTTServicePulsarClient(
                configurationRegistry,
                connectorConfiguration,
                dispatcher,
                TEST_SUBSCRIPTION_ID,
                TEST_TENANT);

        injectPulsarClient(mqttServicePulsarClient, pulsarClient);
        injectConsumerAndProducer(mqttServicePulsarClient, platformConsumer, deviceProducer);
        injectTopicNames(mqttServicePulsarClient); // Add this line

        ProcessingContext<?> context = createTestProcessingContext(Qos.AT_MOST_ONCE);

        when(deviceProducer.isConnected()).thenReturn(true);
        when(pulsarClient.isClosed()).thenReturn(false);
        when(messageBuilder.sendAsync()).thenReturn(CompletableFuture.completedFuture(null));

        mqttServicePulsarClient.publishMEAO(context);

        // The implementation always calls send(), not sendAsync() for AT_MOST_ONCE
        verify(deviceProducer).newMessage();
        verify(messageBuilder).value(any(byte[].class));
        verify(messageBuilder).property(eq(MQTTServicePulsarClient.PULSAR_PROPERTY_TOPIC), anyString());
        verify(messageBuilder).send();
    }

    @Test
    void testPublishMEAO_ClientClosed() {
        mqttServicePulsarClient = spy(new MQTTServicePulsarClient(
                configurationRegistry,
                connectorConfiguration,
                dispatcher,
                TEST_SUBSCRIPTION_ID,
                TEST_TENANT));

        injectPulsarClient(mqttServicePulsarClient, pulsarClient);

        ProcessingContext<?> context = createTestProcessingContext(Qos.AT_LEAST_ONCE);

        when(pulsarClient.isClosed()).thenReturn(true);
        doReturn(null).when(mqttServicePulsarClient).reconnect();

        mqttServicePulsarClient.publishMEAO(context);

        verify(mqttServicePulsarClient).reconnect();
    }

    @Test
    void testSupportsWildcardInTopic() {
        mqttServicePulsarClient = new MQTTServicePulsarClient();

        assertFalse(mqttServicePulsarClient.supportsWildcardInTopic(Direction.INBOUND));
        assertFalse(mqttServicePulsarClient.supportsWildcardInTopic(Direction.OUTBOUND));
    }

    @Test
    void testSupportedDirections() {
        mqttServicePulsarClient = new MQTTServicePulsarClient();

        List<Direction> directions = mqttServicePulsarClient.supportedDirections();

        assertNotNull(directions);
        assertEquals(2, directions.size());
        assertTrue(directions.contains(Direction.INBOUND));
        assertTrue(directions.contains(Direction.OUTBOUND));
    }

    @Test
    void testIsConnected() {
        mqttServicePulsarClient = new MQTTServicePulsarClient(
                configurationRegistry,
                connectorConfiguration,
                dispatcher,
                TEST_SUBSCRIPTION_ID,
                TEST_TENANT);

        injectPulsarClient(mqttServicePulsarClient, pulsarClient);

        when(pulsarClient.isClosed()).thenReturn(false);

        assertFalse(mqttServicePulsarClient.isConnected());
    }

    @Test
    void testClose() {
        mqttServicePulsarClient = spy(new MQTTServicePulsarClient(
                configurationRegistry,
                connectorConfiguration,
                dispatcher,
                TEST_SUBSCRIPTION_ID,
                TEST_TENANT));

        doNothing().when(mqttServicePulsarClient).disconnect();

        mqttServicePulsarClient.close();

        verify(mqttServicePulsarClient).disconnect();
    }

    @Test
    void testAdjustServiceUrlForTls() throws Exception {
        mqttServicePulsarClient = new MQTTServicePulsarClient();

        // Test enabling TLS
        String result = invokePrivateMethod(
                mqttServicePulsarClient,
                "adjustServiceUrlForTls",
                new Class<?>[] { String.class, Boolean.class },
                "pulsar://localhost:6650",
                true);
        assertEquals("pulsar+ssl://localhost:6650", result);

        // Test disabling TLS
        result = invokePrivateMethod(
                mqttServicePulsarClient,
                "adjustServiceUrlForTls",
                new Class<?>[] { String.class, Boolean.class },
                "pulsar+ssl://localhost:6650",
                false);
        assertEquals("pulsar://localhost:6650", result);

        // Test no change when already correct
        result = invokePrivateMethod(
                mqttServicePulsarClient,
                "adjustServiceUrlForTls",
                new Class<?>[] { String.class, Boolean.class },
                "pulsar://localhost:6650",
                false);
        assertEquals("pulsar://localhost:6650", result);
    }

    @Test
    void testConfigureAuthentication_Token() throws Exception {
        mqttServicePulsarClient = new MQTTServicePulsarClient();

        ClientBuilder clientBuilder = mock(ClientBuilder.class);
        when(clientBuilder.authentication(any(Authentication.class))).thenReturn(clientBuilder);

        invokePrivateMethod(
                mqttServicePulsarClient,
                "configureAuthentication",
                new Class<?>[] { ClientBuilder.class, String.class, String.class },
                clientBuilder,
                "token",
                "test-token");

        verify(clientBuilder).authentication(any(Authentication.class));
    }

    @Test
    void testConfigureAuthentication_Basic() throws Exception {
        mqttServicePulsarClient = new MQTTServicePulsarClient(
                configurationRegistry,
                connectorConfiguration,
                dispatcher,
                TEST_SUBSCRIPTION_ID,
                TEST_TENANT);

        ClientBuilder clientBuilder = mock(ClientBuilder.class);
        when(clientBuilder.authentication(any(Authentication.class))).thenReturn(clientBuilder);

        String authParams = String.format(
                "{\"userId\":\"%s/%s\",\"password\":\"%s\"}",
                TEST_TENANT, TEST_USERNAME, TEST_PASSWORD);

        invokePrivateMethod(
                mqttServicePulsarClient,
                "configureAuthentication",
                new Class<?>[] { ClientBuilder.class, String.class, String.class },
                clientBuilder,
                "basic",
                authParams);

        verify(clientBuilder).authentication(any(Authentication.class));
    }

    @Test
    void testConfigureAuthentication_None() throws Exception {
        mqttServicePulsarClient = new MQTTServicePulsarClient();

        ClientBuilder clientBuilder = mock(ClientBuilder.class);

        invokePrivateMethod(
                mqttServicePulsarClient,
                "configureAuthentication",
                new Class<?>[] { ClientBuilder.class, String.class, String.class },
                clientBuilder,
                "none",
                "");

        verify(clientBuilder, never()).authentication(any());
    }

    @Test
    void testConnectorSpecificHousekeeping() throws Exception {
        mqttServicePulsarClient = new MQTTServicePulsarClient(
                configurationRegistry,
                connectorConfiguration,
                dispatcher,
                TEST_SUBSCRIPTION_ID,
                TEST_TENANT);

        injectConsumerAndProducer(mqttServicePulsarClient, platformConsumer, deviceProducer);

        when(platformConsumer.isConnected()).thenReturn(false);
        when(deviceProducer.isConnected()).thenReturn(false);

        assertDoesNotThrow(() -> invokePrivateMethod(
                mqttServicePulsarClient,
                "connectorSpecificHousekeeping",
                new Class<?>[] { String.class },
                TEST_TENANT));
    }

    @Test
    void testGetSubscriptionName() throws Exception {
        String result = invokePrivateStaticMethod(
                MQTTServicePulsarClient.class,
                "getSubscriptionName",
                new Class<?>[] { String.class, String.class },
                TEST_CONNECTOR_IDENTIFIER,
                TEST_SUBSCRIPTION_ID);

        assertTrue(result.contains(TEST_CONNECTOR_IDENTIFIER));
        assertTrue(result.contains(TEST_SUBSCRIPTION_ID));
        assertTrue(result.startsWith("CUMULOCITY_MQTT_SERVICE_PULSAR_"));
    }

    @Test
    void testCreateConnectorSpecification() {
        mqttServicePulsarClient = new MQTTServicePulsarClient();

        ConnectorSpecification spec = mqttServicePulsarClient.getConnectorSpecification();

        assertNotNull(spec);
        assertEquals(ConnectorType.CUMULOCITY_MQTT_SERVICE_PULSAR, spec.getConnectorType());
        assertTrue(spec.isSingleton());
        assertNotNull(spec.getProperties());
        assertTrue(spec.getProperties().containsKey("serviceUrl"));
        assertTrue(spec.getProperties().containsKey("enableTls"));
        assertTrue(spec.getProperties().containsKey("authenticationMethod"));
    }

}