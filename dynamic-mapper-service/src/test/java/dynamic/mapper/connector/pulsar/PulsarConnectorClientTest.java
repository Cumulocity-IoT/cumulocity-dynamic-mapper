/*
 * Copyright (c) 2026 Cumulocity GmbH.
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

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
                "useSelfSignedCertificate", true,
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
                "useSelfSignedCertificate", true,
                "nameCertificate", "my-ca",
                "fingerprintSelfSignedCertificate", "AA:BB:CC",
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
                "useSelfSignedCertificate", true,
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

    private static class TestPulsarConnectorClient extends PulsarConnectorClient {
        TestPulsarConnectorClient(PulsarClient client, ConnectorConfiguration configuration) {
            pulsarClient = client;
            connectorConfiguration = configuration;
            serviceConfiguration = new ServiceConfiguration();
            tenant = "tenant";
        }
    }
}
