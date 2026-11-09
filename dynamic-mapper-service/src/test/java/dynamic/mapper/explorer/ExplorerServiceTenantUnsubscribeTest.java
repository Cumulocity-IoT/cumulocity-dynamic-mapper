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

package dynamic.mapper.explorer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.Test;

import com.cumulocity.microservice.subscription.model.MicroserviceSubscriptionRemovedEvent;

import dynamic.mapper.connector.core.registry.ConnectorRegistry;

class ExplorerServiceTenantUnsubscribeTest {

    @Test
    @SuppressWarnings("unchecked")
    void unsubscribeStopsAndRemovesAllSessionsOfTheTenantOnly() throws Exception {
        ExplorerService service = new ExplorerService(mock(ConnectorRegistry.class), null, null, null, null);
        ConcurrentHashMap<String, ConcurrentHashMap<String, ExplorerSession>> sessions = field(service, "sessions");
        Map<String, Object> listeners = field(service, "listenerRegistry");

        sessions.computeIfAbsent("t1", k -> new ConcurrentHashMap<>()).put("s1", session("s1", "t1"));
        sessions.computeIfAbsent("t2", k -> new ConcurrentHashMap<>()).put("s2", session("s2", "t2"));
        listeners.put("s1", new Object());
        listeners.put("s2", new Object());

        MicroserviceSubscriptionRemovedEvent event = mock(MicroserviceSubscriptionRemovedEvent.class);
        when(event.getTenant()).thenReturn("t1");
        service.onTenantUnsubscribed(event);

        assertFalse(sessions.containsKey("t1"));
        assertFalse(listeners.containsKey("s1"), "listener of the removed tenant's session must be unregistered");
        assertTrue(sessions.containsKey("t2"));
        assertTrue(listeners.containsKey("s2"));
    }

    private static ExplorerSession session(String id, String tenant) {
        return ExplorerSession.builder().sessionId(id).tenant(tenant).direction("INBOUND")
                .connectorIdentifier("c").lastPolledAt(System.currentTimeMillis()).build();
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(target);
    }
}
