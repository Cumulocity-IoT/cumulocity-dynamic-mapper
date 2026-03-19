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

/**
 * Input message wrapper passed to SMART_FUNCTION JavaScript code as the first argument ({@code msg}).
 *
 * <p>Intentionally avoids Lombok so that GraalVM's {@code allowPublicAccess(true)} can reliably
 * discover and expose both public fields and public getter methods to JavaScript.</p>
 *
 * <p>Fields are {@code public} so that both access styles work in JavaScript:</p>
 * <ul>
 *   <li>{@code msg.payload} — direct field access (used by TypeScript-compiled code)</li>
 *   <li>{@code msg.getPayload()} — Java-style getter call (used by JS mapping templates)</li>
 * </ul>
 *
 * <p>JavaScript usage:</p>
 * <pre>
 *   function onMessage(msg, context) {
 *       var payload = msg.payload;        // or msg.getPayload()
 *       var topic   = msg.topic;          // or msg.getTopic()
 *       var client  = msg.clientId;       // inbound: MQTT client id; outbound: null
 *       var source  = msg.sourceId;       // outbound: C8Y device id; inbound: null
 *   }
 * </pre>
 */
public class InputMessage {

    public final Object payload;
    public final String topic;
    public final String clientId;
    public final String sourceId;

    public InputMessage(Object payload, String topic, String clientId, String sourceId) {
        this.payload = payload;
        this.topic = topic;
        this.clientId = clientId;
        this.sourceId = sourceId;
    }

    /** Alias for {@link #payload}. Supports {@code msg.getPayload()} in JS mapping templates. */
    public Object getPayload() {
        return payload;
    }

    /** Alias for {@link #topic}. Supports {@code msg.getTopic()} in JS mapping templates. */
    public String getTopic() {
        return topic;
    }

    /** Alias for {@link #clientId}. Supports {@code msg.getClientId()} in JS mapping templates. */
    public String getClientId() {
        return clientId;
    }

    /** Alias for {@link #sourceId}. Supports {@code msg.getSourceId()} in JS mapping templates. */
    public String getSourceId() {
        return sourceId;
    }
}
