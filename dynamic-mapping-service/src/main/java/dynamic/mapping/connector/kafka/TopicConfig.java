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

package dynamic.mapping.connector.kafka;

import java.util.Properties;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TopicConfig {
	private String tenant;
	private String bootstrapServers;
	private String topic;
	private String username;
	private String password;
	private String saslMechanism;
	private String groupId;
	private Properties defaultPropertiesConsumer;

	public TopicConfig(String tenant, String bootstrapServers, String topic, String username, String password,
			String saslMechanism,
			String groupId,
			Properties defaultPropertiesConsumer) {
		this.tenant = tenant;
		this.bootstrapServers = bootstrapServers;
		this.topic = topic;
		this.username = username;
		this.password = password;
		this.saslMechanism = saslMechanism;
		this.groupId = groupId;
		this.defaultPropertiesConsumer = defaultPropertiesConsumer;
	}

	@Override
	public String toString() {
		return getClass().getSimpleName() +
				" bootstrapServers='" + bootstrapServers + '\'' +
				", topic='" + topic + '\'';
	}
}
