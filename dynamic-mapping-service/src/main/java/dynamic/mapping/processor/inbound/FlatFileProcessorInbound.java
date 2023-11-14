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

package dynamic.mapping.processor.inbound;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dynamic.mapping.connector.core.callback.ConnectorMessage;
import dynamic.mapping.core.C8YAgent;
import dynamic.mapping.processor.model.PayloadWrapper;
import dynamic.mapping.processor.model.ProcessingContext;

import java.io.IOException;
import java.nio.charset.Charset;

//@Service
public class FlatFileProcessorInbound extends JSONProcessorInbound {


    public FlatFileProcessorInbound(ObjectMapper objectMapper, C8YAgent c8yAgent, String tenant){
        super(objectMapper, c8yAgent, tenant);
    }

    @Override
    public ProcessingContext<JsonNode> deserializePayload(ProcessingContext<JsonNode> context, ConnectorMessage message) throws IOException {
        String payloadMessage  = (message.getPayload() != null
                    ? new String(message.getPayload(), Charset.defaultCharset())
                    : "");
        JsonNode payloadJsonNode = objectMapper.valueToTree(new PayloadWrapper(payloadMessage));
        context.setPayload(payloadJsonNode);
        return context;
    }

}