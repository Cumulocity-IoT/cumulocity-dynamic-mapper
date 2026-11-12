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
 */

package dynamic.mapper.connector.core.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dynamic.mapper.model.Qos;
import jakarta.ws.rs.NotSupportedException;

class ExplorerSubscriptionHandlerTest {

    private final List<String> subscribed = new ArrayList<>();
    private final List<Qos> subscribedQos = new ArrayList<>();
    private final List<String> unsubscribed = new ArrayList<>();
    private Set<String> mappingTopics = Set.of();
    private ConnectorException subscribeFailure;
    private RuntimeException subscribeRuntimeFailure;
    private RuntimeException unsubscribeFailure;

    private ExplorerSubscriptionHandler handler() {
        return new ExplorerSubscriptionHandler("t1", "conn", t -> mappingTopics.contains(t),
                (topic, qos) -> {
                    if (subscribeFailure != null) {
                        throw subscribeFailure;
                    }
                    if (subscribeRuntimeFailure != null) {
                        throw subscribeRuntimeFailure;
                    }
                    subscribed.add(topic);
                    subscribedQos.add(qos);
                },
                topic -> {
                    if (unsubscribeFailure != null) {
                        throw unsubscribeFailure;
                    }
                    unsubscribed.add(topic);
                });
    }

    @Test
    void subscribesFreeTopicAtLeastOnce() {
        assertNull(handler().subscribe("a/b"));
        assertEquals(List.of("a/b"), subscribed);
        assertEquals(List.of(Qos.AT_LEAST_ONCE), subscribedQos);
    }

    @Test
    void skipsTopicAlreadySubscribedByMapping() {
        mappingTopics = Set.of("a/b");
        assertNull(handler().subscribe("a/b"));
        assertEquals(List.of(), subscribed);
    }

    @Test
    void connectorWithoutSubscriptionsIsNotAnError() {
        subscribeRuntimeFailure = new NotSupportedException("no subscriptions");
        assertNull(handler().subscribe("a/b"));
    }

    @Test
    void subscribeFailureIsReturnedAsMessage() {
        subscribeFailure = new ConnectorException("not connected");
        assertEquals("not connected", handler().subscribe("a/b"));
    }

    @Test
    void unsubscribesFreeTopic() {
        handler().unsubscribe("a/b");
        assertEquals(List.of("a/b"), unsubscribed);
    }

    @Test
    void keepsSubscriptionWhileMappingUsesTopic() {
        mappingTopics = Set.of("a/b");
        handler().unsubscribe("a/b");
        assertEquals(List.of(), unsubscribed);
    }

    @Test
    void unsubscribeFailureIsSwallowed() {
        unsubscribeFailure = new IllegalStateException("boom");
        handler().unsubscribe("a/b");
        assertEquals(List.of(), unsubscribed);
    }
}
