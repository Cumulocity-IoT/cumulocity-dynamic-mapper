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
 */

package dynamic.mapper.connector.core.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.Test;

import dynamic.mapper.connector.core.client.MappingSubscriptionManager.SubscriptionCallback;
import dynamic.mapper.model.Direction;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.Qos;

/**
 * Tests for {@link MappingSubscriptionManager#updateSubscriptionsInbound}, the full-reconcile
 * entry point used when the deployment map changes or mappings are reloaded.
 * <p>
 * The key behaviour under test is that a reconcile properly <em>removes</em> mappings that are
 * no longer effective (e.g. un-deployed from this connector), not just adds new ones.
 */
class MappingSubscriptionManagerTest {

    /** Records the topics currently subscribed at the (fake) broker. */
    private static class RecordingCallback implements SubscriptionCallback {
        final Set<String> subscribedTopics = ConcurrentHashMap.newKeySet();

        @Override
        public void subscribe(String topic, Qos qos) {
            subscribedTopics.add(topic);
        }

        @Override
        public void unsubscribe(String topic) {
            subscribedTopics.remove(topic);
        }
    }

    private static Mapping inbound(String identifier, String topic) {
        return Mapping.builder()
                .id(identifier)
                .identifier(identifier)
                .mappingTopic(topic)
                .direction(Direction.INBOUND)
                .active(true)
                .qos(Qos.AT_LEAST_ONCE)
                .build();
    }

    @Test
    void reconcile_unsubscribesMappingRemovedFromDeployment() {
        RecordingCallback callback = new RecordingCallback();
        MappingSubscriptionManager manager = new MappingSubscriptionManager("t1", "connector-1", callback);

        Mapping m1 = inbound("m1", "topic/a");
        Mapping m2 = inbound("m2", "topic/b");

        // Initial state: both mappings deployed.
        manager.updateSubscriptionsInbound(new ArrayList<>(List.of(m1, m2)), false, true, mapping -> true);

        assertEquals(Set.of("topic/a", "topic/b"), callback.subscribedTopics);
        assertEquals(Set.of("m1", "m2"), manager.getEffectiveMappingsInbound().keySet());

        // m2 is un-deployed from this connector -> reconcile with only m1.
        manager.updateSubscriptionsInbound(new ArrayList<>(List.of(m1)), false, true, mapping -> true);

        assertEquals(Set.of("topic/a"), callback.subscribedTopics,
                "topic of the un-deployed mapping must be unsubscribed at the broker");
        assertEquals(Set.of("m1"), manager.getEffectiveMappingsInbound().keySet(),
                "the un-deployed mapping must be dropped from the effective set");
        assertFalse(manager.getSubscriptionCountsView().containsKey("topic/b"),
                "stale topic reference counts must not linger");
        assertTrue(manager.getSubscriptionCountsView().containsKey("topic/a"));
    }

    @Test
    void reconcile_keepsSharedTopicSubscribedWhenOneOfTwoMappingsRemoved() {
        RecordingCallback callback = new RecordingCallback();
        MappingSubscriptionManager manager = new MappingSubscriptionManager("t1", "connector-1", callback);

        Mapping m1 = inbound("m1", "shared/topic");
        Mapping m2 = inbound("m2", "shared/topic");

        manager.updateSubscriptionsInbound(new ArrayList<>(List.of(m1, m2)), false, true, mapping -> true);
        assertEquals(Set.of("shared/topic"), callback.subscribedTopics);

        // Remove m2 only; the topic is still needed by m1 and must stay subscribed.
        manager.updateSubscriptionsInbound(new ArrayList<>(List.of(m1)), false, true, mapping -> true);

        assertEquals(Set.of("shared/topic"), callback.subscribedTopics,
                "shared topic must remain subscribed while another mapping still uses it");
        assertEquals(Set.of("m1"), manager.getEffectiveMappingsInbound().keySet());
        assertEquals(1, manager.getSubscriptionCountsView().get("shared/topic").intValue());
    }

    @Test
    void addSubscriptionInbound_isIdempotentForSameMapping() throws Exception {
        RecordingCallback callback = new RecordingCallback();
        MappingSubscriptionManager manager = new MappingSubscriptionManager("t1", "connector-1", callback);
        Mapping m1 = inbound("m1", "topic/a");

        // Subscribing the same mapping twice (e.g. re-activate, or re-save an active mapping)
        // must not inflate the topic reference count.
        manager.addSubscriptionInbound(m1, Qos.AT_LEAST_ONCE);
        manager.addSubscriptionInbound(m1, Qos.AT_LEAST_ONCE);

        assertEquals(1, manager.getSubscriptionCountsView().get("topic/a").intValue());
        assertEquals(Set.of("topic/a"), callback.subscribedTopics);

        // A single deactivation must therefore fully unsubscribe the topic.
        manager.removeSubscriptionInbound(m1);

        assertTrue(callback.subscribedTopics.isEmpty(),
                "a single remove must unsubscribe after a duplicate add");
        assertFalse(manager.getSubscriptionCountsView().containsKey("topic/a"));
    }

    @Test
    void addSubscriptionInbound_topicChangeReleasesOldTopic() throws Exception {
        RecordingCallback callback = new RecordingCallback();
        MappingSubscriptionManager manager = new MappingSubscriptionManager("t1", "connector-1", callback);

        manager.addSubscriptionInbound(inbound("m1", "topic/old"), Qos.AT_LEAST_ONCE);
        assertEquals(Set.of("topic/old"), callback.subscribedTopics);

        // Same mapping identifier, but its topic changed (mapping update).
        manager.addSubscriptionInbound(inbound("m1", "topic/new"), Qos.AT_LEAST_ONCE);

        assertEquals(Set.of("topic/new"), callback.subscribedTopics,
                "the old topic must be unsubscribed when a mapping's topic changes");
        assertFalse(manager.getSubscriptionCountsView().containsKey("topic/old"));
        assertEquals(1, manager.getSubscriptionCountsView().get("topic/new").intValue());
    }

    @Test
    void reconcile_dropsDeactivatedMapping() {
        RecordingCallback callback = new RecordingCallback();
        MappingSubscriptionManager manager = new MappingSubscriptionManager("t1", "connector-1", callback);

        Mapping m1 = inbound("m1", "topic/a");
        manager.updateSubscriptionsInbound(new ArrayList<>(List.of(m1)), false, true, mapping -> true);
        assertEquals(Set.of("m1"), manager.getEffectiveMappingsInbound().keySet());

        // Mapping becomes inactive.
        m1.setActive(false);
        manager.updateSubscriptionsInbound(new ArrayList<>(List.of(m1)), false, true, mapping -> true);

        assertTrue(manager.getEffectiveMappingsInbound().isEmpty());
        assertTrue(callback.subscribedTopics.isEmpty());
    }
}
