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

package dynamic.mapper.processor.extension;

import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.model.DataPreparationContext;
import dynamic.mapper.processor.model.DeviceMessage;
import dynamic.mapper.processor.model.Message;
import dynamic.mapper.processor.model.ProcessingContext;
import org.springframework.stereotype.Component;

/**
 * Extension interface for outbound processing (Cumulocity → Broker).
 *
 * <p>This interface uses the return-value based SMART Function Pattern.
 * Implement {@link #onMessage(Message, DataPreparationContext)} to follow the functional
 * programming pattern used by SMART JavaScript functions.</p>
 *
 * <pre>
 * {@code
 * @Override
 * public DeviceMessage[] onMessage(Message<byte[]> message, DataPreparationContext context) {
 *     // Parse Cumulocity payload
 *     Map<?, ?> alarm = Json.parseJson(new String(message.getPayload(), "UTF-8"));
 *
 *     // Transform to custom format
 *     Map<String, Object> customFormat = Map.of(
 *         "deviceId", alarm.get("source").get("id"),
 *         "alarmType", alarm.get("type"),
 *         "severity", alarm.get("severity")
 *     );
 *     String customJson = new ObjectMapper().writeValueAsString(customFormat);
 *
 *     // Build and return result
 *     return new DeviceMessage[] {
 *         DeviceMessage.forTopic(context.getMapping().getPublishTopic())
 *             .payload(customJson)
 *             .retain(false)
 *             .build()
 *     };
 * }
 * }
 * </pre>
 *
 * <p>Benefits of this pattern:</p>
 * <ul>
 *   <li>Cleaner code - no side effects, easier to test</li>
 *   <li>Consistent with SMART JavaScript function pattern</li>
 *   <li>Builder pattern for cleaner object construction</li>
 *   <li>Better separation of concerns</li>
 * </ul>
 *
 * <p>By implementing this interface, the extension is automatically tagged with Direction.OUTBOUND
 * via the {@link OutboundExtension} marker interface.</p>
 *
 * <p>Use this interface when:</p>
 * <ul>
 *   <li>You need to generate binary protocols (protobuf, custom formats)</li>
 *   <li>Complex transformations that cannot be expressed as substitutions</li>
 *   <li>Custom message formats requiring Java code</li>
 * </ul>
 *
 * <p>Typical use cases:</p>
 * <ul>
 *   <li>Cumulocity Operation → Binary device protocol</li>
 *   <li>Cumulocity Alarm → Custom JSON device format</li>
 *   <li>Complex multi-message protocols</li>
 * </ul>
 *
 * @param <O> The type of payload being processed (typically byte[] or Object)
 *
 * @see OutboundExtension for parsing only (substitution-based)
 * @see ProcessorExtensionInbound for inbound complete processing
 * @see Message
 * @see DataPreparationContext
 * @see DeviceMessage
 */
@Component
public interface ProcessorExtensionOutbound<O>  {

    /**
     * New pattern: Process a Cumulocity message and return device messages to publish.
     *
     * <p>This method follows the SMART function pattern used by JavaScript extensions.
     * It receives an immutable {@link Message} wrapper containing the Cumulocity payload
     * (Event, Alarm, Operation, etc.) and a {@link DataPreparationContext} for accessing
     * state, inventory, and utility methods.</p>
     *
     * <p>The extension should parse the Cumulocity message, perform any necessary transformations,
     * and return an array of {@link DeviceMessage}s to be published to the broker.
     * Use the builder pattern for clean object construction:</p>
     *
     * <pre>
     * {@code
     * DeviceMessage.forTopic("device/alarms")
     *     .payload(customJson)
     *     .retain(false)
     *     .transportField("qos", "1")
     *     .build()
     * }
     * </pre>
     *
     * <p>Each {@link DeviceMessage} should specify:</p>
     * <ul>
     *   <li>topic - The broker topic to publish to</li>
     *   <li>payload - The message payload (String, byte[], or Object)</li>
     *   <li>retain (optional) - Whether to retain the message</li>
     *   <li>transportFields (optional) - Transport-specific fields (QoS, headers, etc.)</li>
     *   <li>clientId (optional) - Target client ID</li>
     * </ul>
     *
     * @param message Immutable message wrapper containing Cumulocity payload, topic, clientId, etc.
     * @param context Data preparation context with access to state, inventory, mapping, etc.
     * @return Array of DeviceMessage instances to publish
     * @throws ProcessingException if parsing or transformation fails
     * @see DeviceMessage
     * @see Message
     * @see DataPreparationContext
     */
    DeviceMessage[] onMessage(Message<O> message, DataPreparationContext context)
            throws ProcessingException;
}

