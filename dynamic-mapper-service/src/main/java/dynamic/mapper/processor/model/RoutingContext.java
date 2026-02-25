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

package dynamic.mapper.processor.model;

import dynamic.mapper.model.API;
import dynamic.mapper.model.Qos;
import lombok.Builder;
import lombok.Value;

/**
 * Immutable context holding routing information for message processing.
 *
 * Thread-safe by design - all fields are final and immutable after construction.
 * Can be safely shared across multiple threads and processing operations.
 *
 * This context separates routing concerns from other processing aspects,
 * making it clear which operations need routing information.
 */
@Value
@Builder(toBuilder = true)
public class RoutingContext {
    /**
     * The MQTT topic from which the message was received (inbound)
     * or to which it should be published (outbound)
     */
    String topic;

    /**
     * The MQTT client ID that sent the message (inbound)
     * or should send it (outbound)
     */
    String clientId;

    /**
     * The Cumulocity API type (e.g., MEASUREMENT, EVENT, ALARM, INVENTORY, OPERATION)
     */
    API api;

    /**
     * Quality of Service level for MQTT message delivery
     * (0 = at most once, 1 = at least once, 2 = exactly once)
     */
    Qos qos;

    /**
     * The resolved topic for publishing outbound messages.
     * May contain substituted placeholders from the original topic template.
     */
    String resolvedPublishTopic;

    /**
     * The tenant identifier for multi-tenant deployments
     */
    String tenant;

    /**
     * Creates a copy of this context with a different resolved publish topic.
     *
     * @param newResolvedPublishTopic the new resolved publish topic
     * @return a new RoutingContext with the updated topic
     */
    public RoutingContext withResolvedPublishTopic(String newResolvedPublishTopic) {
        return this.toBuilder()
            .resolvedPublishTopic(newResolvedPublishTopic)
            .build();
    }

    /**
     * Creates a copy of this context with a different tenant.
     *
     * @param newTenant the new tenant identifier
     * @return a new RoutingContext with the updated tenant
     */
    public RoutingContext withTenant(String newTenant) {
        return this.toBuilder()
            .tenant(newTenant)
            .build();
    }
}
