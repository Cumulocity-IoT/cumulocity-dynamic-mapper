/*
 * Copyright (c) 2022-2026 Cumulocity GmbH.
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

package dynamic.mapper.processor.inbound.deserializer;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.codec.binary.Hex;

import dynamic.mapper.connector.core.callback.ConnectorMessage;
import dynamic.mapper.model.Mapping;


public class HexPayloadDeserializer implements PayloadDeserializer<Object> {
    
    @Override
    public Object deserializePayload(Mapping mapping, ConnectorMessage message) throws IOException {
        if (message.getPayload() == null) {
            throw new IOException("Hex payload is null");
        }
        
        try {

            Object payloadObjectNode = new HashMap<>(
                Map.of("payload", "0x" + Hex.encodeHexString(message.getPayload()))
            );
            
            return payloadObjectNode;
            
        } catch (Exception e) {
            throw new IOException("Failed to deserialize hex payload: " + e.getMessage(), e);
        }
    }
}
