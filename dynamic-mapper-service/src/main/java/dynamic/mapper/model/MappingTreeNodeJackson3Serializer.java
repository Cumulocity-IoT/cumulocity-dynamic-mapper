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


package dynamic.mapper.model;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ser.std.StdSerializer;

/**
 * Jackson 3 counterpart of {@link MappingTreeNodeSerializer}. Spring Boot 4 writes MVC responses
 * with Jackson 3, which ignores the Jackson 2 serializer registered on the legacy ObjectMapper.
 * Without it, the default bean serialization follows parentNode back up the tree and recurses
 * until the maximum nesting depth is exceeded.
 */
@Slf4j
public class MappingTreeNodeJackson3Serializer extends StdSerializer<MappingTreeNode> {

	public MappingTreeNodeJackson3Serializer() {
		super(MappingTreeNode.class);
	}

	@Override
	public void serialize(MappingTreeNode value, JsonGenerator jgen, SerializationContext ctxt)
			throws JacksonException {
		log.debug("Serializing node {}, {}", value.getLevel(), value.getAbsolutePath());
		jgen.writeStartObject();
		jgen.writeNumberProperty("depthIndex", value.getDepthIndex());
		jgen.writeStringProperty("level", value.getLevel());
		jgen.writeStringProperty("nodeId", value.getNodeId());
		jgen.writeBooleanProperty("isMappingNode", value.getMappingNode());
		jgen.writeStringProperty("parentNode",
				(value.getParentNode() != null ? value.getParentNode().getAbsolutePath() : "null"));
		jgen.writeStringProperty("absolutePath", value.getAbsolutePath());
		if (value.getMappingNode()) {
			ctxt.defaultSerializeProperty("mapping", value.getMapping(), jgen);
		} else {
			ctxt.defaultSerializeProperty("childNodes", value.getChildNodes(), jgen);
		}
		jgen.writeEndObject();
	}
}
