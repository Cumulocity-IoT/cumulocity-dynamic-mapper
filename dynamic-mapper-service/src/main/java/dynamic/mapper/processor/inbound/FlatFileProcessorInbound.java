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

package dynamic.mapper.processor.inbound;

import static java.util.Map.entry;

import dynamic.mapper.connector.core.callback.ConnectorMessage;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.model.Mapping;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;

//@Service
public class FlatFileProcessorInbound extends JSONProcessorInbound {

    public FlatFileProcessorInbound(ConfigurationRegistry configurationRegistry) {
        super(configurationRegistry);
    }

    @Override
    public Object deserializePayload(Mapping mapping, ConnectorMessage message)
            throws IOException {
        String payloadMessage = (message.getPayload() != null
                ? new String(message.getPayload(), Charset.defaultCharset())
                : "");
        // Object payloadObjectNode = objectMapper.valueToTree(new PayloadWrapper(payloadMessage));
        Object payloadObjectNode = new HashMap<>(Map.ofEntries(
            entry("message", payloadMessage)));
        return payloadObjectNode;
    }
}