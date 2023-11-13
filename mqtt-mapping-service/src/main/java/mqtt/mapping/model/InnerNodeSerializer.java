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

package mqtt.mapping.model;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;

@Slf4j
public class InnerNodeSerializer extends StdSerializer<InnerNode> {

    public InnerNodeSerializer() {
        this(null);
    }

    public InnerNodeSerializer(Class<InnerNode> t) {
        super(t);
    }

    @Override
    public void serialize(
            InnerNode value, JsonGenerator jgen, SerializerProvider provider)
            throws IOException, JsonProcessingException {
        log.debug("Serializing node {}, {}", value.getLevel(), value.getAbsolutePath());
        jgen.writeStartObject();
        jgen.writeNumberField("depthIndex", value.getDepthIndex());
        jgen.writeStringField("level", value.getLevel());
        jgen.writeStringField("parentNode",
                (value.getParentNode() != null ? value.getParentNode().getAbsolutePath() : "null"));
        jgen.writeStringField("absolutePath", value.getAbsolutePath());
        if (value.isMappingNode()) {
            provider.defaultSerializeField("mapping", value.getMapping(), jgen);
        } else {
            provider.defaultSerializeField("childNodes", value.getChildNodes(), jgen);
        }
        jgen.writeEndObject();

    }
    
}