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

package mqtt.mapping.processor.incoming;

import java.io.IOException;

import org.apache.commons.codec.binary.Hex;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import mqtt.mapping.core.C8YAgent;
import mqtt.mapping.processor.model.PayloadWrapper;
import mqtt.mapping.processor.model.ProcessingContext;
import mqtt.mapping.service.MQTTClient;

@Service
public class GenericBinaryProcessor extends JSONProcessor {

    public GenericBinaryProcessor ( ObjectMapper objectMapper, MQTTClient mqttClient, C8YAgent c8yAgent){
        super(objectMapper, mqttClient, c8yAgent);
    }

    @Override
    public ProcessingContext<JsonNode> deserializePayload(ProcessingContext<JsonNode> context, MqttMessage mqttMessage) throws IOException{
        JsonNode payloadJsonNode = objectMapper.valueToTree(new PayloadWrapper(Hex.encodeHexString(mqttMessage.getPayload())));
        context.setPayload(payloadJsonNode);
        return context;
    }
}