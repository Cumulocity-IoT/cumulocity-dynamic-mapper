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

package dynamic.mapping.connector.kafka;

import java.util.Properties;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TopicConfig {
    private String bootstrapServers;
    private String topic;
    private String tenant;
    private String username;
    private String password;
    private Properties defaultPropertiesConsumer;

    public TopicConfig(String bootstrapServers, String topic, String username, String password, String tenant,
            Properties defaultPropertiesConsumer) {
        this.bootstrapServers = bootstrapServers;
        this.topic = topic;
        this.username = username;
        this.password = password;
        this.tenant = tenant;
        this.defaultPropertiesConsumer = defaultPropertiesConsumer;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() +
                " bootstrapServers='" + bootstrapServers + '\'' +
                ", topic='" + topic + '\'';
    }
}
