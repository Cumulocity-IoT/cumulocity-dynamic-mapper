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

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public enum API {
    ALARM("ALARM", "source.id", "alarms"),
    EVENT("EVENT", "source.id", "events"),
    MEASUREMENT("MEASUREMENT", "source.id", "measurements"),
    INVENTORY("INVENTORY", "_DEVICE_IDENT_", "managedObjects"),
    OPERATION("OPERATION", "deviceId", "operations"),
    EMPTY("NN", "nn", "nn"),

    ALL("ALL", "*", "*");

    public final String name;
    public final String identifier;
    public final String notificationFilter;

    private API(String name, String identifier, String notificationFilter) {
        this.name = name;
        this.identifier = identifier;
        this.notificationFilter = notificationFilter;
        this.aliases = Arrays.asList(name, identifier, notificationFilter);
    }

    private List<String> aliases;

    static final private Map<String, API> ALIAS_MAP = new HashMap<String, API>();

    static {
        for (API api : API.values()) {
            ALIAS_MAP.put(api.name(), api);
            for (String alias : api.aliases)
                ALIAS_MAP.put(alias, api);
        }
    }

    static public API fromString(String value) {
        API api = ALIAS_MAP.get(value);
        if (api == null)
            throw new IllegalArgumentException("Not an alias: " + value);
        return api;
    }
}