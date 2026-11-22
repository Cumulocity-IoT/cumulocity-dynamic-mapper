/*
 * Copyright (c) 2022-2026 Cumulocity GmbH.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package dynamic.mapper.connector.pulsar;

import dynamic.mapper.configuration.ConnectorConfiguration;
import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.connector.core.callback.ConnectorMessage;
import dynamic.mapper.connector.core.callback.GenericMessageCallback;
import dynamic.mapper.core.ServiceRegistry;
import dynamic.mapper.model.DynamicMapperRequest;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.Qos;
import dynamic.mapper.processor.runtime.ProcessingContext;
import dynamic.mapper.processor.runtime.ProcessingResultWrapper;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.ProducerBuilder;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
class PulsarConnectorClientTest {

    @Test
    void sparkplugHostPropertiesAreNotAvailableForApachePulsar() {
        var properties = new PulsarConnectorClient().getConnectorSpecification().getProperties();

        assertFalse(properties.containsKey("isSparkplugHost"));
        assertFalse(properties.containsKey("sparkplugHostId"));
    }

    @Test
    void configValidWithInlinePemCertificate() {
        ConnectorConfiguration configuration = mock(ConnectorConfiguration.class);
        when(configuration.getProperties()).thenReturn(Map.of(
                "serviceUrl", "pulsar+ssl://localhost:6651",
                "enableTls", true,
                "trustCustomCertificate", true,
                "certificateChainInPemFormat",
                "-----BEGIN CERTIFICATE-----\nMIIB...\n-----END CERTIFICATE-----",
                "connectionTimeoutSeconds", 30,
                "operationTimeoutSeconds", 30,
                "keepAliveIntervalSeconds", 30));

        assertTrue(new PulsarConnectorClient().isConfigValid(configuration));
    }

    @Test
    void configValidWithNamedCertificateReference() {
        ConnectorConfiguration configuration = mock(ConnectorConfiguration.class);
        when(configuration.getProperties()).thenReturn(Map.of(
                "serviceUrl", "pulsar+ssl://localhost:6651",
                "enableTls", true,
                "trustCustomCertificate", true,
                "nameCertificate", "my-ca",
                "trustedCertificateFingerprint", "AA:BB:CC",
                "connectionTimeoutSeconds", 30,
                "operationTimeoutSeconds", 30,
                "keepAliveIntervalSeconds", 30));

        assertTrue(new PulsarConnectorClient().isConfigValid(configuration));
    }

    @Test
    void configInvalidWhenSelfSignedCertificateHasNoCertificateSource() {
        ConnectorConfiguration configuration = mock(ConnectorConfiguration.class);
        when(configuration.getProperties()).thenReturn(Map.of(
                "serviceUrl", "pulsar+ssl://localhost:6651",
                "enableTls", true,
                "trustCustomCertificate", true,
                "connectionTimeoutSeconds", 30,
                "operationTimeoutSeconds", 30,
                "keepAliveIntervalSeconds", 30));

        assertFalse(new PulsarConnectorClient().isConfigValid(configuration));
    }

    @ParameterizedTest
    @CsvSource({
            "pulsar://localhost:6650, false, false",
            "pulsar+ssl://localhost:6651, false, true",
            "pulsar://localhost:6650, true, true",
            "https://localhost:8443, false, true"
    })
    void sslRequiredWhenTlsEnabledOrServiceUrlUsesTlsScheme(String serviceUrl, boolean enableTls, boolean expected) {
        ConnectorConfiguration configuration = mock(ConnectorConfiguration.class);
        when(configuration.getProperties()).thenReturn(Map.of(
                "serviceUrl", serviceUrl,
                "enableTls", enableTls));

        TestPulsarConnectorClient client = new TestPulsarConnectorClient(mock(PulsarClient.class), configuration);

        assertEquals(expected, client.isSslRequired());
    }

    @Test
    void connectionTestReportsUnresolvableHostInsteadOfTimingOut() {
        ConnectorConfiguration configuration = mock(ConnectorConfiguration.class);
        // .invalid is reserved (RFC 2606) and never resolves
        when(configuration.getProperties()).thenReturn(Map.of(
                "serviceUrl", "pulsar+ssl://broker.example.invalid:6651",
                "enableTls", true));

        TestPulsarConnectorClient client = new TestPulsarConnectorClient(mock(PulsarClient.class), configuration);

        var result = client.testConnection();

        assertFalse(result.success());
        assertTrue(result.message().contains("broker.example.invalid"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "persistent://public/default/measurements",
            "persistent://public/default/measurements-partition-1",
            "non-persistent://public/default/measurements"
    })
    void inboundUsesMessageTopicWithoutMqttServiceProperties(String topic) {
        ServiceRegistry registry = mock(ServiceRegistry.class);
        GenericMessageCallback dispatcher = mock(GenericMessageCallback.class);
        Consumer<byte[]> consumer = mock();
        Message<byte[]> message = mock();
        byte[] payload = "{\"temperature\":25}".getBytes(StandardCharsets.UTF_8);

        when(registry.getServiceConfiguration("tenant")).thenReturn(new ServiceConfiguration());
        when(registry.getVirtualThreadPool()).thenReturn(mock(ExecutorService.class));
        when(message.getTopicName()).thenReturn(topic);
        when(message.getData()).thenReturn(payload);
        when(message.getMessageId()).thenReturn(MessageId.earliest);
        when(message.getProducerName()).thenReturn("pulsar-producer");
        doReturn(ProcessingResultWrapper.builder().build()).when(dispatcher).onMessage(any());

        new PulsarCallback("tenant", registry, dispatcher, "pulsar", "Pulsar")
                .received(consumer, message);

        ArgumentCaptor<ConnectorMessage> captured = ArgumentCaptor.forClass(ConnectorMessage.class);
        verify(dispatcher).onMessage(captured.capture());
        assertEquals(topic, captured.getValue().getTopic());
        assertEquals("pulsar-producer", captured.getValue().getClientId());
        assertArrayEquals(payload, captured.getValue().getPayload());
        verify(consumer, never()).getTopic();
        verify(message, never()).getProperty(anyString());
    }

    @ParameterizedTest
    @CsvSource({
            // subscription, pulsar topic name, expected topic handed to the dispatcher
            "device/temperature, persistent://public/default/device-temperature, device/temperature",
            "sensors/+/data, persistent://public/default/sensors-device1-data, sensors/device1/data",
            "sensors/#, persistent://public/default/sensors-a-b-partition-2, sensors/a/b",
            "sensors/+/data, persistent://public/default/other-topic, other-topic"
    })
    void subscriptionListenerDispatchesUsingTopicOfMessage(String subscription, String pulsarTopic,
            String expected) {
        ServiceRegistry registry = mock(ServiceRegistry.class);
        GenericMessageCallback dispatcher = mock(GenericMessageCallback.class);
        Consumer<byte[]> consumer = mock();
        Message<byte[]> message = mock();
        when(registry.getServiceConfiguration("tenant")).thenReturn(new ServiceConfiguration());
        when(registry.getVirtualThreadPool()).thenReturn(mock(ExecutorService.class));
        when(message.getTopicName()).thenReturn(pulsarTopic);
        when(message.getData()).thenReturn("{}".getBytes(StandardCharsets.UTF_8));
        when(message.getMessageId()).thenReturn(MessageId.earliest);
        doReturn(ProcessingResultWrapper.builder().build()).when(dispatcher).onMessage(any());

        PulsarCallback callback = new PulsarCallback("tenant", registry, dispatcher, "pulsar", "Pulsar");
        new PulsarConnectorClient.QoSAwarePulsarCallback(callback, Qos.AT_LEAST_ONCE, subscription)
                .received(consumer, message);

        ArgumentCaptor<ConnectorMessage> captured = ArgumentCaptor.forClass(ConnectorMessage.class);
        verify(dispatcher).onMessage(captured.capture());
        assertEquals(expected, captured.getValue().getTopic());
    }

    @ParameterizedTest
    @CsvSource({
            "sensors/+/data, persistent://public/default/sensors-device1-data, true",
            "sensors/+/data, persistent://public/default/sensors-data, false",
            "sensors/+/data, persistent://public/default/sensors-a-b-data, false",
            "sensors/+/+/data, persistent://public/default/sensors-a-b-data, true",
            "sensors/#, persistent://public/default/sensors, true",
            "sensors/#, persistent://public/default/sensors-a-b, true",
            "sensors/#, persistent://public/default/other-a, false",
            "#, persistent://public/default/anything-at-all, true",
            "v1.0/+, persistent://public/default/v1.0-x, true",
            "v1.0/+, persistent://public/default/v1x0-x, false"
    })
    void wildcardFilterIsTranslatedToMatchingPulsarRegex(String filter, String pulsarTopic, boolean matches) {
        String regex = PulsarConnectorClient.buildPulsarTopicRegex(filter, "public", "default");
        assertEquals(matches, java.util.regex.Pattern.compile(regex).matcher(pulsarTopic).matches(), regex);
    }

    @ParameterizedTest
    @EnumSource(Qos.class)
    void outboundPublishesToBrokerTopicsWithoutMqttServiceProperties(Qos qos) throws Exception {
        PulsarClient pulsarClient = mock(PulsarClient.class);
        ProducerBuilder<byte[]> producerBuilder = mock();
        Producer<byte[]> producer = mock();
        ConnectorConfiguration configuration = mock(ConnectorConfiguration.class);
        when(configuration.getProperties()).thenReturn(Map.of(
                "pulsarTenant", "public", "pulsarNamespace", "default"));
        when(pulsarClient.newProducer()).thenReturn(producerBuilder);
        when(producerBuilder.topic(anyString())).thenReturn(producerBuilder);
        when(producerBuilder.sendTimeout(anyInt(), any())).thenReturn(producerBuilder);
        when(producerBuilder.create()).thenReturn(producer);
        when(producer.isConnected()).thenReturn(true);
        if (qos == Qos.AT_MOST_ONCE) {
            when(producer.sendAsync(any(byte[].class))).thenReturn(
                    CompletableFuture.completedFuture(MessageId.earliest));
        }

        String payload = "{\"command\":\"start\"}";
        List<DynamicMapperRequest> requests = List.of(
                DynamicMapperRequest.builder().publishTopic("persistent://public/default/commands")
                        .request(payload).build(),
                DynamicMapperRequest.builder().publishTopic("non-persistent://public/default/commands")
                        .request(payload).build(),
                DynamicMapperRequest.builder().request(payload).build());
        ProcessingContext<Object> context = ProcessingContext.builder()
                .mapping(Mapping.builder().name("outbound").debug(false).build())
                .qos(qos)
                .resolvedPublishTopic("device/commands")
                .requests(requests)
                .build();

        new TestPulsarConnectorClient(pulsarClient, configuration).publishMEAO(context);

        verify(producerBuilder).topic("persistent://public/default/commands");
        verify(producerBuilder).topic("non-persistent://public/default/commands");
        verify(producerBuilder).topic("persistent://public/default/device-commands");
        byte[] expectedPayload = payload.getBytes(StandardCharsets.UTF_8);
        if (qos == Qos.AT_MOST_ONCE) {
            verify(producer, times(3)).sendAsync(aryEq(expectedPayload));
            verify(producer, never()).send(any(byte[].class));
        } else {
            verify(producer, times(3)).send(aryEq(expectedPayload));
            verify(producer, never()).sendAsync(any(byte[].class));
        }
        verify(producer, never()).newMessage();
        assertTrue(context.getErrors().isEmpty());
        requests.forEach(request -> assertNull(request.getError()));
    }

    @ParameterizedTest
    @EnumSource(Qos.class)
    @SuppressWarnings("unchecked")
    void outboundSetsMessageKeyFromContext(Qos qos) throws Exception {
        PulsarClient pulsarClient = mock(PulsarClient.class);
        ProducerBuilder<byte[]> producerBuilder = mock();
        Producer<byte[]> producer = mock();
        org.apache.pulsar.client.api.TypedMessageBuilder<byte[]> message = mock(
                org.apache.pulsar.client.api.TypedMessageBuilder.class, RETURNS_SELF);
        ConnectorConfiguration configuration = mock(ConnectorConfiguration.class);
        when(configuration.getProperties()).thenReturn(Map.of(
                "pulsarTenant", "public", "pulsarNamespace", "default"));
        when(pulsarClient.newProducer()).thenReturn(producerBuilder);
        when(producerBuilder.topic(anyString())).thenReturn(producerBuilder);
        when(producerBuilder.sendTimeout(anyInt(), any())).thenReturn(producerBuilder);
        when(producerBuilder.create()).thenReturn(producer);
        when(producer.isConnected()).thenReturn(true);
        when(producer.newMessage()).thenReturn(message);
        when(message.sendAsync()).thenReturn(CompletableFuture.completedFuture(MessageId.earliest));

        String payload = "{\"command\":\"start\"}";
        ProcessingContext<Object> context = ProcessingContext.builder()
                .mapping(Mapping.builder().name("outbound").debug(false).build())
                .qos(qos)
                .resolvedPublishTopic("device/commands")
                .requests(List.of(DynamicMapperRequest.builder().request(payload).build()))
                .build();
        context.setKey("device-1");

        new TestPulsarConnectorClient(pulsarClient, configuration).publishMEAO(context);

        verify(message).key("device-1");
        verify(message).value(aryEq(payload.getBytes(StandardCharsets.UTF_8)));
        if (qos == Qos.AT_MOST_ONCE) {
            verify(message).sendAsync();
        } else {
            verify(message).send();
        }
        verify(producer, never()).send(any(byte[].class));
        assertTrue(context.getErrors().isEmpty());
    }

    @Test
    void outboundSendsABinaryPayloadAsBytesInsteadOfTheRequestText() throws Exception {
        PulsarClient pulsarClient = mock(PulsarClient.class);
        ProducerBuilder<byte[]> producerBuilder = mock();
        Producer<byte[]> producer = mock();
        ConnectorConfiguration configuration = mock(ConnectorConfiguration.class);
        when(configuration.getProperties()).thenReturn(Map.of(
                "pulsarTenant", "public", "pulsarNamespace", "default"));
        when(pulsarClient.newProducer()).thenReturn(producerBuilder);
        when(producerBuilder.topic(anyString())).thenReturn(producerBuilder);
        when(producerBuilder.sendTimeout(anyInt(), any())).thenReturn(producerBuilder);
        when(producerBuilder.create()).thenReturn(producer);
        when(producer.isConnected()).thenReturn(true);

        byte[] bytes = { 0, 1, 2, (byte) 0xFF };
        // the request keeps the Base64 text for display; the bytes are what must be sent
        ProcessingContext<Object> context = ProcessingContext.builder()
                .mapping(Mapping.builder().name("outbound").debug(false).build())
                .qos(Qos.AT_LEAST_ONCE)
                .resolvedPublishTopic("device/fw")
                .requests(List.of(DynamicMapperRequest.builder().request("AAEC/w==").binaryPayload(bytes).build()))
                .build();

        new TestPulsarConnectorClient(pulsarClient, configuration).publishMEAO(context);

        verify(producer).send(aryEq(bytes));
        assertTrue(context.getErrors().isEmpty());
    }

    @Test
    void subscriptionNameIsPrefixedWithTopicByDefault() {
        ConnectorConfiguration configuration = mock(ConnectorConfiguration.class);
        when(configuration.getProperties()).thenReturn(Map.of("subscriptionName", "my-sub"));

        String name = new TestPulsarConnectorClient(mock(PulsarClient.class), configuration)
                .buildSubscriptionName("devices/+/data");

        assertEquals("my-sub-devices-wildcard-data", name);
    }

    @Test
    void subscriptionNameIsUsedAsIsWhenEnabled() {
        ConnectorConfiguration configuration = mock(ConnectorConfiguration.class);
        when(configuration.getProperties()).thenReturn(Map.of(
                "subscriptionName", "example-subscription",
                "useSubscriptionNameAsIs", true));

        String name = new TestPulsarConnectorClient(mock(PulsarClient.class), configuration)
                .buildSubscriptionName("PERSISTENT://example-tenant/example-namespace/COMMON");

        assertEquals("example-subscription", name);
    }

    @Test
    void combinedTenantAndNamespaceIsSplit() {
        ConnectorConfiguration configuration = mock(ConnectorConfiguration.class);
        when(configuration.getProperties()).thenReturn(Map.of(
                "pulsarTenant", "ignored", "pulsarNamespace", "example-tenant/example-namespace"));

        assertArrayEquals(new String[] { "persistent", "example-tenant", "example-namespace" },
                new TestPulsarConnectorClient(mock(PulsarClient.class), configuration).resolveTopicLocation());
    }

    @Test
    void nonPersistentDomainIsHonored() {
        ConnectorConfiguration configuration = mock(ConnectorConfiguration.class);
        when(configuration.getProperties()).thenReturn(Map.of(
                "pulsarTenant", "t", "pulsarNamespace", "n", "pulsarTopicDomain", "non-persistent"));

        assertArrayEquals(new String[] { "non-persistent", "t", "n" },
                new TestPulsarConnectorClient(mock(PulsarClient.class), configuration).resolveTopicLocation());
        assertEquals("non-persistent://t/n/a-b", PulsarConnectorClient.buildPulsarTopicRegex("a/b", "t", "n", "non-persistent"));
    }

    private ProcessingContext<Object> publishWithSendFailure(Exception failure) throws Exception {
        PulsarClient pulsarClient = mock(PulsarClient.class);
        ProducerBuilder<byte[]> producerBuilder = mock();
        Producer<byte[]> producer = mock();
        ConnectorConfiguration configuration = mock(ConnectorConfiguration.class);
        when(configuration.getProperties()).thenReturn(Map.of(
                "pulsarTenant", "public", "pulsarNamespace", "default"));
        when(pulsarClient.newProducer()).thenReturn(producerBuilder);
        when(producerBuilder.topic(anyString())).thenReturn(producerBuilder);
        when(producerBuilder.sendTimeout(anyInt(), any())).thenReturn(producerBuilder);
        when(producerBuilder.create()).thenReturn(producer);
        when(producer.isConnected()).thenReturn(true);
        when(producer.send(any(byte[].class))).thenThrow(failure);

        ProcessingContext<Object> context = ProcessingContext.builder()
                .mapping(Mapping.builder().name("outbound").debug(false).build())
                .qos(Qos.AT_LEAST_ONCE)
                .resolvedPublishTopic("device/commands")
                .requests(List.of(DynamicMapperRequest.builder().request("{}").build()))
                .build();
        new TestPulsarConnectorClient(pulsarClient, configuration).publishMEAO(context);
        return context;
    }

    @Test
    void aSendTimeoutIsRetriedNotAcknowledged() throws Exception {
        ProcessingContext<Object> context = publishWithSendFailure(
                new PulsarClientException.TimeoutException("send timed out"));

        assertTrue(context.getRequests().get(0).getError()
                instanceof dynamic.mapper.processor.ConnectorNotConnectedException);
        assertEquals(503, ((dynamic.mapper.processor.ProcessingException) context.getErrors().get(0))
                .getHttpStatusCode());
    }

    @Test
    void aProblemOfTheMessageIsNotRetried() throws Exception {
        ProcessingContext<Object> context = publishWithSendFailure(
                new PulsarClientException.InvalidMessageException("bad message"));

        assertFalse(context.getRequests().get(0).getError()
                instanceof dynamic.mapper.processor.ConnectorNotConnectedException);
        assertEquals(0, ((dynamic.mapper.processor.ProcessingException) context.getErrors().get(0))
                .getHttpStatusCode());
    }

    private static class TestPulsarConnectorClient extends PulsarConnectorClient {
        /** The connector state is not wired in these tests: stands in for "connected, broker reachable". */
        volatile boolean ready = true;

        TestPulsarConnectorClient(PulsarClient client, ConnectorConfiguration configuration) {
            pulsarClient = client;
            connectorConfiguration = configuration;
            serviceConfiguration = new ServiceConfiguration();
            tenant = "tenant";
        }

        @Override
        protected boolean isReadyToPublish() {
            return ready;
        }

        /** The connector state is not wired here either, so the real subscribe() would return without doing anything. */
        final List<String> subscribed = new java.util.ArrayList<>();
        volatile boolean subscribeFails;

        @Override
        @SuppressWarnings("unchecked")
        protected void subscribe(String topic, Qos qos) throws dynamic.mapper.connector.core.client.ConnectorException {
            if (subscribeFails) {
                throw new dynamic.mapper.connector.core.client.ConnectorException("broker down");
            }
            subscribed.add(topic + "@" + qos);
            consumers.put(topic, mock(Consumer.class));
        }
    }

    // ---- outage behavior: one producer per topic, hold while the broker is down

    private ConnectorConfiguration publicDefault() {
        ConnectorConfiguration configuration = mock(ConnectorConfiguration.class);
        when(configuration.getProperties()).thenReturn(Map.of(
                "pulsarTenant", "public", "pulsarNamespace", "default"));
        return configuration;
    }

    private ProcessingContext<Object> contextFor(String topic) {
        return ProcessingContext.builder()
                .mapping(Mapping.builder().name("outbound").debug(false).build())
                .qos(Qos.AT_LEAST_ONCE)
                .resolvedPublishTopic(topic)
                .requests(List.of(DynamicMapperRequest.builder().request("{}").build()))
                .build();
    }

    @SuppressWarnings("unchecked")
    private ProducerBuilder<byte[]> producerBuilderReturning(PulsarClient pulsarClient, Producer<byte[]> producer)
            throws Exception {
        ProducerBuilder<byte[]> producerBuilder = mock();
        when(pulsarClient.newProducer()).thenReturn(producerBuilder);
        when(producerBuilder.topic(anyString())).thenReturn(producerBuilder);
        when(producerBuilder.sendTimeout(anyInt(), any())).thenReturn(producerBuilder);
        when(producerBuilder.create()).thenReturn(producer);
        return producerBuilder;
    }

    @Test
    void concurrentPublishesToOneTopicCreateOneProducer() throws Exception {
        PulsarClient pulsarClient = mock(PulsarClient.class);
        Producer<byte[]> producer = mock();
        when(producer.isConnected()).thenReturn(true);
        ProducerBuilder<byte[]> producerBuilder = mock();
        when(pulsarClient.newProducer()).thenReturn(producerBuilder);
        when(producerBuilder.topic(anyString())).thenReturn(producerBuilder);
        when(producerBuilder.sendTimeout(anyInt(), any())).thenReturn(producerBuilder);
        // a slow create, like one against a broker that is coming back: every thread is inside the race window
        when(producerBuilder.create()).thenAnswer(inv -> {
            Thread.sleep(200);
            return producer;
        });
        TestPulsarConnectorClient client = new TestPulsarConnectorClient(pulsarClient, publicDefault());

        int threads = 16;
        ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        List<java.util.concurrent.Future<?>> done = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            done.add(pool.submit(() -> {
                start.await();
                client.publishMEAO(contextFor("device/commands"));
                return null;
            }));
        }
        start.countDown();
        for (java.util.concurrent.Future<?> f : done) {
            f.get(10, java.util.concurrent.TimeUnit.SECONDS);
        }
        pool.shutdown();

        verify(producerBuilder, times(1)).create();
        assertEquals(1, client.producers.size());
    }

    @Test
    void aDisconnectedProducerIsNotReplacedAndThePublishIsRetried() throws Exception {
        PulsarClient pulsarClient = mock(PulsarClient.class);
        Producer<byte[]> producer = mock();
        when(producer.isConnected()).thenReturn(false); // the broker is down, the producer reconnects by itself
        ProducerBuilder<byte[]> producerBuilder = producerBuilderReturning(pulsarClient, producer);
        TestPulsarConnectorClient client = new TestPulsarConnectorClient(pulsarClient, publicDefault());

        ProcessingContext<Object> first = contextFor("device/commands");
        ProcessingContext<Object> second = contextFor("device/commands");
        client.publishMEAO(first);
        client.publishMEAO(second);

        verify(producerBuilder, times(1)).create(); // it used to close and recreate on every publish
        verify(producer, never()).close();
        for (ProcessingContext<Object> context : List.of(first, second)) {
            assertTrue(context.getRequests().get(0).getError()
                    instanceof dynamic.mapper.processor.ConnectorNotConnectedException);
            assertEquals(503, ((dynamic.mapper.processor.ProcessingException) context.getErrors().get(0))
                    .getHttpStatusCode());
        }
    }

    @Test
    void aClosedProducerIsReplacedOnTheNextPublish() throws Exception {
        PulsarClient pulsarClient = mock(PulsarClient.class);
        Producer<byte[]> producer = mock();
        when(producer.isConnected()).thenReturn(true);
        when(producer.send(any(byte[].class))).thenThrow(new PulsarClientException.AlreadyClosedException("closed"));
        ProducerBuilder<byte[]> producerBuilder = producerBuilderReturning(pulsarClient, producer);
        TestPulsarConnectorClient client = new TestPulsarConnectorClient(pulsarClient, publicDefault());

        client.publishMEAO(contextFor("device/commands"));
        client.publishMEAO(contextFor("device/commands"));

        verify(producerBuilder, times(2)).create();
    }

    @Test
    void nothingIsPublishedWhileTheConnectorIsNotReady() throws Exception {
        PulsarClient pulsarClient = mock(PulsarClient.class);
        TestPulsarConnectorClient client = new TestPulsarConnectorClient(pulsarClient, publicDefault());
        client.ready = false;

        ProcessingContext<Object> context = contextFor("device/commands");
        client.publishMEAO(context);

        verify(pulsarClient, never()).newProducer();
        assertTrue(context.getRequests().get(0).getError()
                instanceof dynamic.mapper.processor.ConnectorNotConnectedException);
        assertEquals(503, ((dynamic.mapper.processor.ProcessingException) context.getErrors().get(0))
                .getHttpStatusCode());
    }

    @Test
    void theConnectorHoldsOutboundMessagesWhileDisconnected() {
        assertTrue(new TestPulsarConnectorClient(mock(PulsarClient.class), publicDefault())
                .holdsOutboundWhileDisconnected());
    }

    @Test
    void theBrokerIsReachableWhenItAnswersEvenWithAnError() {
        PulsarClient pulsarClient = mock(PulsarClient.class);
        TestPulsarConnectorClient client = new TestPulsarConnectorClient(pulsarClient, publicDefault());

        when(pulsarClient.getPartitionsForTopic(anyString()))
                .thenReturn(CompletableFuture.completedFuture(List.of()));
        assertTrue(client.isBrokerReachable());

        // an unknown topic or a missing permission is still an answer
        when(pulsarClient.getPartitionsForTopic(anyString()))
                .thenReturn(CompletableFuture.failedFuture(new PulsarClientException.NotFoundException("no topic")));
        assertTrue(client.isBrokerReachable());
    }

    @Test
    void theBrokerIsNotReachableOnATimeoutOrALostConnection() {
        PulsarClient pulsarClient = mock(PulsarClient.class);
        TestPulsarConnectorClient client = new TestPulsarConnectorClient(pulsarClient, publicDefault());

        when(pulsarClient.getPartitionsForTopic(anyString()))
                .thenReturn(CompletableFuture.failedFuture(new PulsarClientException.TimeoutException("lookup")));
        assertFalse(client.isBrokerReachable());

        when(pulsarClient.getPartitionsForTopic(anyString()))
                .thenReturn(CompletableFuture.failedFuture(new PulsarClientException.ConnectException("refused")));
        assertFalse(client.isBrokerReachable());

        when(pulsarClient.isClosed()).thenReturn(true);
        assertFalse(client.isBrokerReachable());
    }

    @Test
    void housekeepingReplacesAProducerOnlyAfterItStaysDisconnected() {
        PulsarClient pulsarClient = mock(PulsarClient.class);
        TestPulsarConnectorClient client = new TestPulsarConnectorClient(pulsarClient, publicDefault());
        Producer<byte[]> stuck = mock();
        when(stuck.isConnected()).thenReturn(false);
        client.producers.put("device/commands", stuck);

        for (int cycle = 1; cycle < PulsarConnectorClient.PRODUCER_REPLACE_AFTER_CYCLES; cycle++) {
            client.connectorSpecificHousekeeping("tenant");
            assertEquals(1, client.producers.size(), "kept in cycle " + cycle);
        }
        client.connectorSpecificHousekeeping("tenant");

        assertTrue(client.producers.isEmpty());
    }

    @Test
    void housekeepingForgetsAProducerThatReconnected() {
        PulsarClient pulsarClient = mock(PulsarClient.class);
        TestPulsarConnectorClient client = new TestPulsarConnectorClient(pulsarClient, publicDefault());
        Producer<byte[]> producer = mock();
        when(producer.isConnected()).thenReturn(false);
        client.producers.put("device/commands", producer);

        client.connectorSpecificHousekeeping("tenant");
        client.connectorSpecificHousekeeping("tenant");
        when(producer.isConnected()).thenReturn(true); // it came back before the limit
        client.connectorSpecificHousekeeping("tenant");
        when(producer.isConnected()).thenReturn(false);
        client.connectorSpecificHousekeeping("tenant");
        client.connectorSpecificHousekeeping("tenant");

        assertEquals(1, client.producers.size(), "the count started over after the reconnect");
    }

    // ---- consumers: left alone while disconnected, replaced only when stuck

    @SuppressWarnings("unchecked")
    private Consumer<byte[]> disconnectedConsumer(TestPulsarConnectorClient client, String topic, Qos qos) {
        Consumer<byte[]> consumer = mock(Consumer.class);
        when(consumer.isConnected()).thenReturn(false);
        client.consumers.put(topic, consumer);
        client.consumerQos.put(topic, qos);
        return consumer;
    }

    private void brokerAnswers(PulsarClient pulsarClient, boolean answers) {
        when(pulsarClient.getPartitionsForTopic(anyString())).thenReturn(answers
                ? CompletableFuture.completedFuture(List.of())
                : CompletableFuture.failedFuture(new PulsarClientException.TimeoutException("lookup")));
    }

    @Test
    void aDisconnectedConsumerIsKeptWhileTheBrokerIsDown() throws Exception {
        PulsarClient pulsarClient = mock(PulsarClient.class);
        brokerAnswers(pulsarClient, false);
        TestPulsarConnectorClient client = new TestPulsarConnectorClient(pulsarClient, publicDefault());
        Consumer<byte[]> consumer = disconnectedConsumer(client, "device/data", Qos.AT_LEAST_ONCE);

        // it used to be closed and dropped on the first cycle, and nothing subscribed again
        for (int cycle = 0; cycle < PulsarConnectorClient.CONSUMER_REPLACE_AFTER_CYCLES + 5; cycle++) {
            client.monitorSubscriptions();
        }

        assertTrue(client.consumers.containsKey("device/data"));
        verify(consumer, never()).close();
        assertTrue(client.subscribed.isEmpty());
    }

    @Test
    void aStuckConsumerIsReplacedAndSubscribedAgainWithItsQos() throws Exception {
        PulsarClient pulsarClient = mock(PulsarClient.class);
        brokerAnswers(pulsarClient, true);
        TestPulsarConnectorClient client = new TestPulsarConnectorClient(pulsarClient, publicDefault());
        Consumer<byte[]> stuck = disconnectedConsumer(client, "device/data", Qos.EXACTLY_ONCE);

        for (int cycle = 1; cycle < PulsarConnectorClient.CONSUMER_REPLACE_AFTER_CYCLES; cycle++) {
            client.monitorSubscriptions();
            assertTrue(client.subscribed.isEmpty(), "kept in cycle " + cycle);
        }
        client.monitorSubscriptions();

        verify(stuck).close();
        assertEquals(List.of("device/data@EXACTLY_ONCE"), client.subscribed);
        assertTrue(client.consumers.containsKey("device/data"));
    }

    @Test
    void aConsumerThatReconnectedStartsTheCountOver() throws Exception {
        PulsarClient pulsarClient = mock(PulsarClient.class);
        brokerAnswers(pulsarClient, true);
        TestPulsarConnectorClient client = new TestPulsarConnectorClient(pulsarClient, publicDefault());
        Consumer<byte[]> consumer = disconnectedConsumer(client, "device/data", Qos.AT_LEAST_ONCE);

        for (int cycle = 1; cycle < PulsarConnectorClient.CONSUMER_REPLACE_AFTER_CYCLES; cycle++) {
            client.monitorSubscriptions();
        }
        when(consumer.isConnected()).thenReturn(true); // it came back just before the limit
        client.monitorSubscriptions();
        when(consumer.isConnected()).thenReturn(false);
        for (int cycle = 1; cycle < PulsarConnectorClient.CONSUMER_REPLACE_AFTER_CYCLES; cycle++) {
            client.monitorSubscriptions();
        }

        verify(consumer, never()).close();
        assertTrue(client.subscribed.isEmpty());
    }

    @Test
    void aFailedSubscribeAgainIsRetriedNextCycle() throws Exception {
        PulsarClient pulsarClient = mock(PulsarClient.class);
        brokerAnswers(pulsarClient, true);
        TestPulsarConnectorClient client = new TestPulsarConnectorClient(pulsarClient, publicDefault());
        disconnectedConsumer(client, "device/data", Qos.AT_LEAST_ONCE);
        client.subscribeFails = true;

        for (int cycle = 0; cycle < PulsarConnectorClient.CONSUMER_REPLACE_AFTER_CYCLES; cycle++) {
            client.monitorSubscriptions();
        }
        assertFalse(client.consumers.containsKey("device/data"), "closed, not yet subscribed again");

        client.subscribeFails = false;
        client.monitorSubscriptions();

        assertEquals(List.of("device/data@AT_LEAST_ONCE"), client.subscribed);
        assertTrue(client.consumers.containsKey("device/data"));
    }

    @Test
    void aConnectedConsumerIsLeftAlone() throws Exception {
        PulsarClient pulsarClient = mock(PulsarClient.class);
        TestPulsarConnectorClient client = new TestPulsarConnectorClient(pulsarClient, publicDefault());
        @SuppressWarnings("unchecked")
        Consumer<byte[]> consumer = mock(Consumer.class);
        when(consumer.isConnected()).thenReturn(true);
        client.consumers.put("device/data", consumer);

        for (int cycle = 0; cycle < PulsarConnectorClient.CONSUMER_REPLACE_AFTER_CYCLES * 2; cycle++) {
            client.monitorSubscriptions();
        }

        verify(consumer, never()).close();
        assertTrue(client.subscribed.isEmpty());
    }
}
