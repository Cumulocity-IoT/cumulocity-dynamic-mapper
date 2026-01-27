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

import dynamic.mapper.core.C8YAgent;
import dynamic.mapper.processor.flow.CumulocityObject;
import dynamic.mapper.processor.flow.DataPreparationContext;
import dynamic.mapper.processor.flow.Message;
import dynamic.mapper.processor.model.ProcessingContext;
import org.springframework.stereotype.Component;

/**
 * Extension interface for complete inbound processing (Broker → Cumulocity).
 *
 * <p>This interface supports two patterns:</p>
 *
 * <h3>1. New Pattern - Return-Value Based (SMART Function Pattern)</h3>
 * <p>Implement {@link #onMessage(Message, DataPreparationContext)} to follow the functional
 * programming pattern used by SMART JavaScript functions. This is the recommended approach
 * for new extensions.</p>
 *
 * <pre>
 * {@code
 * @Override
 * public CumulocityObject[] onMessage(Message<byte[]> message, DataPreparationContext context) {
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
 * <h3>2. Legacy Pattern - Side-Effect Based (Deprecated)</h3>
 * <p>The original {@link #substituteInTargetAndSend(ProcessingContext, C8YAgent)} method
 * is still supported for backwards compatibility but is deprecated. Existing extensions
 * will continue to work unchanged.</p>
 *
 * <p>Benefits of the new pattern:</p>
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
 * @see DataPreparationContext
 * @see CumulocityObject
 */
@Component
public interface ProcessorExtensionInbound<O> extends InboundExtension<O> {

    /**
     * Legacy pattern: Perform substitutions in the target template and send to Cumulocity.
     *
     * <p><strong>DEPRECATED:</strong> This method uses the side-effect based pattern
     * where extensions directly call {@code c8yAgent.createMEAO()} to send data.
     * New extensions should use {@link #onMessage(Message, DataPreparationContext)} instead.</p>
     *
     * <p>Existing implementations will continue to work. The processor automatically
     * detects which pattern an extension uses at runtime.</p>
     *
     * @param context Processing context containing the source payload and mapping info
     * @param c8yAgent C8Y agent for sending data to Cumulocity
     * @deprecated Use {@link #onMessage(Message, DataPreparationContext)} for new extensions
     */
    @Deprecated(since = "2.0", forRemoval = false)
    default void substituteInTargetAndSend(ProcessingContext<O> context, C8YAgent c8yAgent) {
        throw new UnsupportedOperationException(
            "Extension must implement either substituteInTargetAndSend() or onMessage()");
    }

    /**
     * New pattern: Process an incoming message and return Cumulocity objects to create.
     *
     * <p>This method follows the SMART function pattern used by JavaScript extensions.
     * It receives an immutable {@link Message} wrapper and a {@link DataPreparationContext}
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
     * <p>If this method returns {@code null}, the processor will fall back to calling
     * {@link #substituteInTargetAndSend(ProcessingContext, C8YAgent)} for backwards compatibility.</p>
     *
     * @param message Immutable message wrapper containing payload, topic, clientId, etc.
     * @param context Data preparation context with access to state, inventory, C8Y agent, etc.
     * @return Array of CumulocityObject instances to create, or null if using legacy pattern
     * @see CumulocityObject
     * @see Message
     * @see DataPreparationContext
     */
    default CumulocityObject[] onMessage(Message<O> message, DataPreparationContext context) {
        return null; // Null indicates this extension uses the legacy pattern
    }
}
