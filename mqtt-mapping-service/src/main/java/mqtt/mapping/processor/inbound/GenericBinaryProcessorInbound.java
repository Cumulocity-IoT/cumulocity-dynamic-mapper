/*
 * Copyright (c) 2022 Software AG, Darmstadt, Germany and/or Software AG USA Inc., Reston, VA, USA,
 * and/or its subsidiaries and/or its affiliates and/or their licensors.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * @authors Christof Strack, Stefan Witschel
 */

package mqtt.mapping.processor.inbound;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import mqtt.mapping.connector.core.callback.ConnectorMessage;
import mqtt.mapping.core.C8YAgent;
import mqtt.mapping.processor.model.PayloadWrapper;
import mqtt.mapping.processor.model.ProcessingContext;
import org.apache.commons.codec.binary.Hex;

import java.io.IOException;

//@Service
public class GenericBinaryProcessorInbound extends JSONProcessorInbound {

    public GenericBinaryProcessorInbound (ObjectMapper objectMapper, C8YAgent c8yAgent, String tenant){
        super(objectMapper, c8yAgent, tenant);
    }

    @Override
    public ProcessingContext<JsonNode> deserializePayload(ProcessingContext<JsonNode> context, ConnectorMessage message) throws IOException{
        JsonNode payloadJsonNode = objectMapper.valueToTree(new PayloadWrapper(Hex.encodeHexString(message.getPayload())));
        context.setPayload(payloadJsonNode);
        return context;
    }
}