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
import dynamic.mapper.processor.model.ProcessingContext;
import org.springframework.stereotype.Component;

/**
 * Extension interface for outbound processing (Cumulocity → Broker).
 *
 * <p>This interface is used when the extension needs to perform complete
 * transformation from Cumulocity payload to broker message format.
 * The extension prepares the outbound requests, and the framework handles sending.</p>
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
 * <p>Example implementation:</p>
 * <pre>
 * public class ProcessorExtensionOperationToBinary
 *         implements ProcessorExtensionOutbound&lt;byte[]&gt; {
 *
 *     {@literal @}Override
 *     public void extractAndPrepare(ProcessingContext&lt;byte[]&gt; context)
 *             throws ProcessingException {
 *         // 1. Parse Cumulocity payload
 *         String json = new String(context.getPayload(), "UTF-8");
 *         Map&lt;String, Object&gt; operation = Json.parseJson(json);
 *
 *         // 2. Generate binary protocol
 *         byte[] binaryMessage = generateBinaryProtocol(operation);
 *
 *         // 3. Create request and add to context
 *         DynamicMapperRequest request = DynamicMapperRequest.builder()
 *                 .payload(binaryMessage)
 *                 .publishTopic(context.getResolvedPublishTopic())
 *                 .build();
 *         context.addRequest(request);
 *
 *         // SendOutboundProcessor will handle the actual sending
 *     }
 * }
 * </pre>
 *
 * @param <O> The type of payload being processed (typically byte[] or Object)
 *
 * @see OutboundExtension for parsing only (substitution-based)
 * @see ProcessorExtensionInbound for inbound complete processing (with C8YAgent)
 */
@Component
public interface ProcessorExtensionOutbound<O> extends OutboundExtension<O> {

    /**
     * Extract data from Cumulocity payload and prepare outbound requests.
     *
     * <p>This method is responsible for:</p>
     * <ol>
     *   <li>Parsing the Cumulocity payload from the context</li>
     *   <li>Generating the broker message format</li>
     *   <li>Creating DynamicMapperRequest(s) with the generated payload</li>
     *   <li>Adding requests to context.getRequests()</li>
     * </ol>
     *
     * <p>The actual sending is handled by SendOutboundProcessor.</p>
     *
     * <p>The context provides:</p>
     * <ul>
     *   <li>context.getPayload() - The Cumulocity payload (Event, Alarm, etc.)</li>
     *   <li>context.getMapping() - Mapping configuration</li>
     *   <li>context.getResolvedPublishTopic() - Topic to publish to</li>
     *   <li>context.addRequest() - Method to add prepared requests</li>
     * </ul>
     *
     * @param context Processing context containing the Cumulocity payload and mapping info
     * @throws ProcessingException if parsing or transformation fails
     */
    void extractAndPrepare(ProcessingContext<O> context)
            throws ProcessingException;
}
