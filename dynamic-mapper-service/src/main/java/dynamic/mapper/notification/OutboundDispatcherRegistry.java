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

package dynamic.mapper.notification;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import dynamic.mapper.processor.outbound.OutboundMessageDispatcher;
import lombok.extern.slf4j.Slf4j;

/**
 * Holds the {@link OutboundMessageDispatcher} of each connector, per tenant.
 * <p>
 * The Notification 2.0 connections hand every outbound notification to the dispatcher of the
 * connector it belongs to, so the registry belongs to the notification code and not to the
 * connector registry. {@link NotificationSubscriber} owns its per-tenant lifecycle.
 */
@Slf4j
@Component
public class OutboundDispatcherRegistry {

    // Structure: < Tenant, < ConnectorIdentifier, OutboundMessageDispatcher > >
    private final Map<String, Map<String, OutboundMessageDispatcher>> dispatchers = new ConcurrentHashMap<>();

    public void initializeTenant(String tenant) {
        dispatchers.put(tenant, new ConcurrentHashMap<>());
    }

    public void removeTenant(String tenant) {
        dispatchers.remove(tenant);
    }

    public void add(String tenant, String identifier, OutboundMessageDispatcher dispatcher) {
        if (tenant == null || identifier == null || dispatcher == null) {
            log.warn("Cannot add dispatcher with null parameters: tenant={}, identifier={}", tenant, identifier);
            return;
        }
        dispatchers.computeIfAbsent(tenant, k -> new ConcurrentHashMap<>()).put(identifier, dispatcher);
        log.debug("{} - Added dispatcher {} for tenant", tenant, identifier);
    }

    public void remove(String tenant, String identifier) {
        if (tenant == null || identifier == null) {
            log.warn("Cannot remove dispatcher: invalid parameters (tenant={}, identifier={})", tenant, identifier);
            return;
        }
        Map<String, OutboundMessageDispatcher> tenantDispatchers = dispatchers.get(tenant);
        if (tenantDispatchers != null) {
            tenantDispatchers.remove(identifier);
            log.debug("{} - Removed dispatcher {}", tenant, identifier);
        }
    }

    /** All dispatchers of the tenant by connector identifier; empty if there are none. */
    public Map<String, OutboundMessageDispatcher> getAll(String tenant) {
        if (tenant == null) {
            return new HashMap<>();
        }
        return dispatchers.getOrDefault(tenant, new HashMap<>());
    }

    /** The dispatcher of one connector, or {@code null} if it has none. */
    public OutboundMessageDispatcher get(String tenant, String identifier) {
        Map<String, OutboundMessageDispatcher> tenantDispatchers = dispatchers.get(tenant);
        return tenantDispatchers != null ? tenantDispatchers.get(identifier) : null;
    }

    @jakarta.annotation.PreDestroy
    public void cleanup() {
        dispatchers.clear();
    }
}
