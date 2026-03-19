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

import dynamic.mapper.processor.model.CumulocityObject;
import dynamic.mapper.processor.model.JavaExtensionContext;
import dynamic.mapper.processor.model.Message;

import org.springframework.stereotype.Component;

/**
 * Extension interface for complete inbound processing (Broker → Cumulocity).
 *
 * <p>This interface uses the return-value based SMART Function Pattern.
 * Implement {@link #onMessage(Message, JavaExtensionContext)} to follow the functional
 * programming pattern used by SMART JavaScript functions.</p>
 *
 * <pre>
 * {@code
 * @Override
 * public CumulocityObject[] onMessage(Message<byte[]> message, JavaExtensionContext context) {
 *     // Parse input
 *     Map<?, ?> json = Json.parseJson(new String(message.getPayload(), "UTF-8"));
 *
 *     // Build and return result using builder pattern
 *     return new CumulocityObject[] {
 *         CumulocityObject.measurement()
 *             .type("c8y_Temperature")
 *             .time(json.get("timestamp"))
 *             .fragment("c8y_Temperature", "T", json.get("value"), "C")
 *             .externalId(json.get("deviceId"), context.getMapping().getExternalIdType())
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
 * <p>By implementing this interface, the extension is automatically tagged with Direction.INBOUND
 * via the {@link InboundExtension} marker interface.</p>
 *
 * @param <O> The type of the source payload (typically byte[] or String)
 * @see InboundExtension
 * @see Message
 * @see JavaExtensionContext
 * @see CumulocityObject
 */
@Component
public interface ProcessorExtensionInbound<O> {
    /**
     * New pattern: Process an incoming message and return Cumulocity objects to create.
     *
     * <p>This method follows the SMART function pattern used by JavaScript extensions.
     * It receives an immutable {@link Message} wrapper and a {@link JavaExtensionContext}
     * for accessing state, inventory, and utility methods.</p>
     *
     * <p>The extension should parse the incoming message, perform any necessary transformations,
     * and return an array of {@link CumulocityObject}s to be created in Cumulocity.
     * Use the builder pattern for clean object construction:</p>
     *
     * <pre>
     * {@code
     * CumulocityObject.measurement()
     *     .type("c8y_Temperature")
     *     .fragment("c8y_Temperature", "T", 25.5, "C")
     *     .externalId("device-001", "c8y_Serial")
     *     .build()
     * }
     * </pre>
     *
     * <p>Each {@link CumulocityObject} should specify:</p>
     * <ul>
     *   <li>cumulocityType - MEASUREMENT, EVENT, ALARM, OPERATION, or MANAGED_OBJECT</li>
     *   <li>action - "create", "update", "delete", etc.</li>
     *   <li>payload - The object data</li>
     *   <li>externalSource - External ID(s) for device lookup/creation</li>
     *   <li>contextData (optional) - Additional metadata</li>
     * </ul>
     *
     * @param message Immutable message wrapper containing payload, topic, clientId, etc.
     * @param context Data preparation context with access to state, inventory, C8Y agent, etc.
     * @return Array of CumulocityObject instances to create
     * @see CumulocityObject
     * @see Message
     * @see JavaExtensionContext
     */
    CumulocityObject[] onMessage(Message<O> message, JavaExtensionContext context);
}
