/*
 * Copyright (c) 2022-2026 Cumulocity GmbH.
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
package dynamic.mapper.connector.googlepubsub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import com.google.protobuf.ByteString;
import com.google.pubsub.v1.PubsubMessage;

import dynamic.mapper.configuration.ConnectorConfiguration;
import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.connector.core.ConnectorSpecification;
import dynamic.mapper.connector.core.callback.ConnectorMessage;
import dynamic.mapper.connector.core.client.ConnectorException;
import dynamic.mapper.connector.core.client.ConnectorType;
import dynamic.mapper.model.API;
import dynamic.mapper.model.Direction;
import dynamic.mapper.model.Qos;
import dynamic.mapper.processor.inbound.CamelDispatcherInbound;
import dynamic.mapper.processor.model.DynamicMapperRequest;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.model.ProcessingResultWrapper;

import com.google.cloud.pubsub.v1.AckReplyConsumer;

/**
 * Tests for GooglePubSubClient's connector-declaration and pure-logic pieces (topic resolution,
 * config validation, inbound ack/nack behaviour). Publishing itself requires a live Pub/Sub
 * client/emulator and is verified manually/via integration testing instead (see EXTENSIONS.md
 * conventions).
 */
public class GooglePubSubClientTest {

    private ConnectorConfiguration configWithProperties(Map<String, Object> properties) {
        ConnectorConfiguration configuration = new ConnectorConfiguration();
        configuration.setIdentifier("test-identifier");
        configuration.setConnectorType(ConnectorType.GOOGLE_PUBSUB);
        configuration.setEnabled(true);
        configuration.setName("Google Pub/Sub Test");
        configuration.setProperties(properties);
        return configuration;
    }

    @Test
    public void testIsConfigValid_missingRequiredProperties_returnsFalse() {
        GooglePubSubClient client = new GooglePubSubClient();

        assertFalse(client.isConfigValid(null));
        assertFalse(client.isConfigValid(configWithProperties(new HashMap<>())));

        Map<String, Object> onlyProjectId = new HashMap<>();
        onlyProjectId.put("projectId", "my-gcp-project");
        assertFalse(client.isConfigValid(configWithProperties(onlyProjectId)));
    }

    @Test
    public void testIsConfigValid_allRequiredPropertiesPresent_returnsTrue() {
        GooglePubSubClient client = new GooglePubSubClient();

        Map<String, Object> properties = new HashMap<>();
        properties.put("projectId", "my-gcp-project");
        properties.put("topicId", "input-messages");
        properties.put("serviceAccountKey", "{\"type\":\"service_account\"}");

        assertTrue(client.isConfigValid(configWithProperties(properties)));
    }

    @Test
    public void testConnectorSpecification_supportsBothDirections() {
        GooglePubSubClient client = new GooglePubSubClient();
        ConnectorSpecification spec = client.getConnectorSpecification();

        assertEquals(ConnectorType.GOOGLE_PUBSUB, spec.getConnectorType());
        assertEquals(2, spec.getSupportedDirections().size());
        assertTrue(spec.getSupportedDirections().contains(Direction.INBOUND));
        assertTrue(spec.getSupportedDirections().contains(Direction.OUTBOUND));

        assertTrue(spec.getProperties().get("projectId").getRequired());
        assertTrue(spec.getProperties().get("topicId").getRequired());
        assertEquals("input-messages", spec.getProperties().get("topicId").getDefaultValue());
        assertTrue(spec.getProperties().get("serviceAccountKey").getRequired());
        assertEquals(30, spec.getProperties().get("publishTimeoutSeconds").getDefaultValue());
        assertNotNull(spec.getProperties().get("subscriptionId"));
        assertFalse(spec.getProperties().get("subscriptionId").getRequired());
    }

    @Test
    public void testSupportsWildcardInTopic_alwaysFalse() {
        GooglePubSubClient client = new GooglePubSubClient();

        assertFalse(client.supportsWildcardInTopic(Direction.INBOUND));
        assertFalse(client.supportsWildcardInTopic(Direction.OUTBOUND));
    }

    @Test
    public void testResolveTopic_prefersRequestPublishTopicOverContextAndDefault() {
        DynamicMapperRequest request = DynamicMapperRequest.builder().publishTopic("device-specific-topic").build();
        ProcessingContext<Object> context = ProcessingContext.builder().resolvedPublishTopic("context-topic").build();

        assertEquals("device-specific-topic", GooglePubSubClient.resolveTopic(request, context, "input-messages"));
    }

    @Test
    public void testResolveTopic_fallsBackToContextResolvedPublishTopic() {
        DynamicMapperRequest request = DynamicMapperRequest.builder().build();
        ProcessingContext<Object> context = ProcessingContext.builder().resolvedPublishTopic("context-topic").build();

        assertEquals("context-topic", GooglePubSubClient.resolveTopic(request, context, "input-messages"));
    }

    @Test
    public void testResolveTopic_fallsBackToConnectorDefaultTopic() {
        DynamicMapperRequest request = DynamicMapperRequest.builder().build();
        ProcessingContext<Object> context = ProcessingContext.builder().build();

        assertEquals("input-messages", GooglePubSubClient.resolveTopic(request, context, "input-messages"));
    }

    @Test
    public void testMessageTypeDerivation_matchesGoogleMdeExpectedValues() {
        assertEquals("measurement", API.MEASUREMENT.toC8yObjectType());
        assertEquals("alarm", API.ALARM.toC8yObjectType());
        assertEquals("event", API.EVENT.toC8yObjectType());
    }

    // ── Inbound ack/nack tests ──────────────────────────────────────────────

    /**
     * Helper to create a client with mocked dispatcher and serviceConfiguration injected
     * via reflection so that {@link GooglePubSubClient#processPubSubMessage} can run without
     * a live Pub/Sub connection.
     */
    @SuppressWarnings("unchecked")
    private GooglePubSubClient clientWithMocks(CamelDispatcherInbound dispatcher,
            ServiceConfiguration serviceConfiguration) throws Exception {
        GooglePubSubClient client = new GooglePubSubClient();

        Field dispatcherField = findField(client.getClass(), "dispatcher");
        dispatcherField.setAccessible(true);
        dispatcherField.set(client, dispatcher);

        Field scField = findField(client.getClass(), "serviceConfiguration");
        scField.setAccessible(true);
        scField.set(client, serviceConfiguration);

        Field tenantField = findField(client.getClass(), "tenant");
        tenantField.setAccessible(true);
        tenantField.set(client, "test-tenant");

        Field nameField = findField(client.getClass(), "connectorName");
        nameField.setAccessible(true);
        nameField.set(client, "test-connector");

        Field idField = findField(client.getClass(), "connectorIdentifier");
        idField.setAccessible(true);
        idField.set(client, "test-id");

        return client;
    }

    private Field findField(Class<?> clazz, String name) throws NoSuchFieldException {
        while (clazz != null) {
            try {
                return clazz.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    @SuppressWarnings("unchecked")
    private ProcessingResultWrapper<Object> successWrapper() {
        ProcessingContext<Object> ctx = mock(ProcessingContext.class);
        when(ctx.hasError()).thenReturn(false);
        CompletableFuture<List<ProcessingContext<Object>>> future = CompletableFuture.completedFuture(
                Collections.singletonList(ctx));
        return ProcessingResultWrapper.<Object>builder()
                .processingResult((java.util.concurrent.Future) future)
                .consolidatedQos(Qos.AT_LEAST_ONCE)
                .pipelineTimeoutMS(0)
                .build();
    }

    @SuppressWarnings("unchecked")
    private ProcessingResultWrapper<Object> errorWrapper() {
        ProcessingContext<Object> ctx = mock(ProcessingContext.class);
        when(ctx.hasError()).thenReturn(true);
        CompletableFuture<List<ProcessingContext<Object>>> future = CompletableFuture.completedFuture(
                Collections.singletonList(ctx));
        return ProcessingResultWrapper.<Object>builder()
                .processingResult((java.util.concurrent.Future) future)
                .consolidatedQos(Qos.AT_LEAST_ONCE)
                .pipelineTimeoutMS(0)
                .build();
    }

    @Test
    public void testProcessPubSubMessage_atMostOnce_acksImmediately() throws Exception {
        CamelDispatcherInbound dispatcher = mock(CamelDispatcherInbound.class);
        ServiceConfiguration sc = mock(ServiceConfiguration.class);
        when(sc.getLogPayload()).thenReturn(false);
        doReturn(successWrapper()).when(dispatcher).onMessage(any(ConnectorMessage.class));

        GooglePubSubClient client = clientWithMocks(dispatcher, sc);
        AckReplyConsumer consumer = mock(AckReplyConsumer.class);

        PubsubMessage message = PubsubMessage.newBuilder()
                .setData(ByteString.copyFromUtf8("{\"value\":1}"))
                .setMessageId("msg-1")
                .build();

        client.processPubSubMessage(message, consumer, "test-topic", Qos.AT_MOST_ONCE);

        verify(consumer).ack();
        verify(consumer, never()).nack();
    }

    @Test
    public void testProcessPubSubMessage_atLeastOnce_acksOnSuccess() throws Exception {
        CamelDispatcherInbound dispatcher = mock(CamelDispatcherInbound.class);
        ServiceConfiguration sc = mock(ServiceConfiguration.class);
        when(sc.getLogPayload()).thenReturn(false);
        doReturn(successWrapper()).when(dispatcher).onMessage(any(ConnectorMessage.class));

        GooglePubSubClient client = clientWithMocks(dispatcher, sc);
        AckReplyConsumer consumer = mock(AckReplyConsumer.class);

        PubsubMessage message = PubsubMessage.newBuilder()
                .setData(ByteString.copyFromUtf8("{\"value\":2}"))
                .setMessageId("msg-2")
                .build();

        client.processPubSubMessage(message, consumer, "test-topic", Qos.AT_LEAST_ONCE);

        verify(consumer).ack();
        verify(consumer, never()).nack();
    }

    @Test
    public void testProcessPubSubMessage_atLeastOnce_nacksOnProcessingError() throws Exception {
        CamelDispatcherInbound dispatcher = mock(CamelDispatcherInbound.class);
        ServiceConfiguration sc = mock(ServiceConfiguration.class);
        when(sc.getLogPayload()).thenReturn(false);
        doReturn(errorWrapper()).when(dispatcher).onMessage(any(ConnectorMessage.class));

        GooglePubSubClient client = clientWithMocks(dispatcher, sc);
        AckReplyConsumer consumer = mock(AckReplyConsumer.class);

        PubsubMessage message = PubsubMessage.newBuilder()
                .setData(ByteString.copyFromUtf8("{\"value\":3}"))
                .setMessageId("msg-3")
                .build();

        client.processPubSubMessage(message, consumer, "test-topic", Qos.AT_LEAST_ONCE);

        verify(consumer).nack();
        verify(consumer, never()).ack();
    }
}
