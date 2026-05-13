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
package dynamic.mapper.processor.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The action to perform on a Cumulocity domain object or via a custom route.
 *
 * <ul>
 *   <li>{@link #CREATE}  – create a new object (POST)</li>
 *   <li>{@link #UPDATE}  – replace an existing object (PUT)</li>
 *   <li>{@link #DELETE}  – delete an object (DELETE)</li>
 *   <li>{@link #PATCH}   – partially update an object (PATCH)</li>
 * </ul>
 */
public enum MappingAction {
    CREATE("create"),
    UPDATE("update"),
    DELETE("delete"),
    PATCH("patch");

    private final String value;

    MappingAction(String value) {
        this.value = value;
    }

    /** Returns the lower-case wire value used in JSON and JavaScript. */
    @JsonValue
    public String getValue() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }

    /**
     * Parse from a string value (case-insensitive).
     *
     * @throws IllegalArgumentException if the value is not recognised
     */
    @JsonCreator
    public static MappingAction fromValue(String value) {
        if (value == null) {
            return null;
        }
        for (MappingAction action : MappingAction.values()) {
            if (action.value.equalsIgnoreCase(value)) {
                return action;
            }
        }
        throw new IllegalArgumentException("Unknown MappingAction: " + value);
    }
}
