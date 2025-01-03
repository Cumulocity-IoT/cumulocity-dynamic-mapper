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

import static java.util.Map.entry;

import dynamic.mapping.connector.core.callback.ConnectorMessage;
import dynamic.mapping.core.ConfigurationRegistry;
import dynamic.mapping.model.Mapping;
import org.apache.commons.codec.binary.Hex;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

//@Service
public class BinaryProcessorInbound extends JSONProcessorInbound {

    public BinaryProcessorInbound(ConfigurationRegistry configurationRegistry) {
        super(configurationRegistry);
    }

    @Override
    public Object deserializePayload(Mapping mapping, ConnectorMessage message)
            throws IOException {
        Object payloadObjectNode = new HashMap<>(Map.ofEntries(entry("message", "0x" + Hex.encodeHexString(message.getPayload()))));
        return payloadObjectNode;
    }
}