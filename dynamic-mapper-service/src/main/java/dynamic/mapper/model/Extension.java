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

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import jakarta.validation.constraints.NotNull;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;

@Getter
@Setter
@ToString()
@Schema(description = "Processor extension configuration providing custom data transformation capabilities")
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

    @Schema(
        requiredMode = Schema.RequiredMode.REQUIRED,
        description = "Unique identifier for the extension",
        example = "custom-json-processor"
    )
    @NotNull
    public String id;

    @Schema(
        requiredMode = Schema.RequiredMode.REQUIRED,
        description = "Loading status of the extension",
        implementation = ExtensionStatus.class,
        example = "LOADED"
    )
    @NotNull
    public ExtensionStatus loaded;

    @Schema(
        requiredMode = Schema.RequiredMode.REQUIRED,
        description = "Display name of the extension",
        example = "Custom JSON Processor"
    )
    @NotNull
    public String name;

    @Schema(
        requiredMode = Schema.RequiredMode.REQUIRED,
        description = "Whether this is an external extension (true) or built-in extension (false)",
        example = "true"
    )
    @NotNull
    public boolean external;

    @Schema(
        requiredMode = Schema.RequiredMode.REQUIRED,
        description = "Map of available extension entry points and their configurations"
    )
    @NotNull
    public Map<String, ExtensionEntry> extensionEntries;
}