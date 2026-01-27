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
 * Base interface for inbound extensions (Broker → Cumulocity).
 *
 * <p>Extensions implementing this interface are automatically tagged with Direction.INBOUND.</p>
 *
 * <p>For substitution-based processing, implement this interface directly and provide the
 * extractFromSource() method to parse the broker payload and add substitutions to the context.</p>
 *
 * <p>For complete processing with C8Y agent access, implement {@link ProcessorExtensionInbound}
 * which extends this interface.</p>
 *
 * <p>Example substitution-based extension:</p>
 * <pre>
 * public class MyInboundExtension implements InboundExtension&lt;byte[]&gt; {
 *     {@literal @}Override
 *     public void extractFromSource(ProcessingContext&lt;byte[]&gt; context) {
 *         // Parse payload and add substitutions
 *         context.addSubstitution("field", value, TYPE.TEXTUAL, ...);
 *     }
 * }
 * </pre>
 *
 * @param <O> The type of the source payload (typically byte[] or String)
 * @see ProcessorExtensionInbound for complete processing with C8YAgent
 */
@Component
public interface InboundExtension<O> {
    /**
     * Extract data from the broker payload and add substitutions to the context.
     *
     * @param context Processing context containing the broker payload and mapping info
     * @throws ProcessingException if parsing fails
     */
    void extractFromSource(ProcessingContext<O> context) throws ProcessingException;
}
