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

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Collections;
import java.util.Map;

/**
 * Immutable message wrapper for Java Extension onMessage pattern.
 *
 * <p>This class provides a clean, consistent API for accessing message data
 * in extension implementations, matching the JavaScript SMART function pattern.</p>
 *
 * <p>Example usage in an extension:</p>
 * <pre>
 * {@code
 * @Override
 * public CumulocityObject[] onMessage(Message<byte[]> message, DataPreparationContext context) {
 *     byte[] payload = message.getPayload();
 *     String topic = message.getTopic();
 *     // ... process message
 * }
 * }
 * </pre>
 *
 * @param <O> The type of the payload (typically byte[], String, or Object)
 */
@Getter
@AllArgsConstructor
public class Message<O> {
    /**
     * The message payload.
     */
    private final O payload;

    /**
     * The topic this message was received on (for inbound) or should be sent to (for outbound).
     */
    private final String topic;

    /**
     * The client ID of the connector.
     */
    private final String clientId;

    /**
     * Transport-specific fields (e.g., Kafka headers, MQTT properties).
     * Immutable map.
     */
    private final Map<String, String> transportFields;

    /**
     * Factory method to create a Message from a ProcessingContext.
     *
     * @param context The processing context
     * @param <O> The payload type
     * @return A new Message instance
     */
    public static <O> Message<O> from(ProcessingContext<O> context) {
        return new Message<>(
            context.getPayload(),
            context.getTopic(),
            context.getClientId(),
            Collections.emptyMap() // TODO: Extract transport fields if available
        );
    }

    /**
     * Get transport fields as an immutable map.
     *
     * @return Immutable map of transport fields
     */
    public Map<String, String> getTransportFields() {
        return Collections.unmodifiableMap(transportFields);
    }
}
