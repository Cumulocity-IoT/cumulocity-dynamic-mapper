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
import dynamic.mapper.processor.model.ProcessingContext;
import org.springframework.stereotype.Component;

/**
 * Extension interface for complete inbound processing (Broker → Cumulocity).
 *
 * <p>Provides access to both the processing context and the C8Y agent
 * for full control over transformation and sending to Cumulocity.</p>
 *
 * <p>By implementing this interface, the extension is automatically tagged with Direction.INBOUND
 * via the {@link InboundExtension} marker interface.</p>
 *
 * @param <O> The type of the source payload (typically byte[] or String)
 * @see InboundExtension
 */
@Component
public interface ProcessorExtensionInbound<O> extends InboundExtension<O> {
    /**
     * Perform substitutions in the target template and send to Cumulocity.
     *
     * @param context Processing context containing the source payload and mapping info
     * @param c8yAgent C8Y agent for sending data to Cumulocity
     */
    public void substituteInTargetAndSend(ProcessingContext<O> context, C8YAgent c8yAgent);
}
