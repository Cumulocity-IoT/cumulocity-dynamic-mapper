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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dynamic.mapper.configuration.ConnectorConfiguration;
import dynamic.mapper.connector.core.ConnectorSpecification;
import dynamic.mapper.connector.core.client.ConnectorType;
import dynamic.mapper.model.API;
import dynamic.mapper.model.Direction;
import dynamic.mapper.processor.model.DynamicMapperRequest;
import dynamic.mapper.processor.model.ProcessingContext;

/**
 * Tests for GooglePubSubClient's connector-declaration and pure-logic pieces (topic resolution,
 * config validation). Publishing itself requires a live Pub/Sub client/emulator and is verified
 * manually/via integration testing instead (see EXTENSIONS.md conventions).
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
    public void testConnectorSpecification_isOutboundOnlyWithExpectedProperties() {
        GooglePubSubClient client = new GooglePubSubClient();
        ConnectorSpecification spec = client.getConnectorSpecification();

        assertEquals(ConnectorType.GOOGLE_PUBSUB, spec.getConnectorType());
        assertEquals(1, spec.getSupportedDirections().size());
        assertTrue(spec.getSupportedDirections().contains(Direction.OUTBOUND));

        assertTrue(spec.getProperties().get("projectId").getRequired());
        assertTrue(spec.getProperties().get("topicId").getRequired());
        assertEquals("input-messages", spec.getProperties().get("topicId").getDefaultValue());
        assertTrue(spec.getProperties().get("serviceAccountKey").getRequired());
        assertEquals(30, spec.getProperties().get("publishTimeoutSeconds").getDefaultValue());
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
}
