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

package dynamic.mapping.model;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import jakarta.validation.constraints.NotNull;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

@Getter
@Setter
@ToString()
public class Extension implements Serializable {

    public Extension() {
        extensionEntries = new HashMap<String, ExtensionEntry>();
    }
    
    public Extension(String id, String name) {
        this();
        this.name = name;
        this.id = id;
    }

    public Extension(String id, String name, boolean external) {
        this(id, name);
        this.external = external;
    }

    @NotNull
    public String id;

    @NotNull
    public ExtensionStatus loaded;

    @NotNull
    public String name;

    @NotNull
    public boolean external;

    @NotNull
    public Map<String, ExtensionEntry> extensionEntries;
}
