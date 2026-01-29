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

import lombok.*;
import java.util.List;

/**
 * Configuration model for YAML-based extension definitions.
 * Represents the structure of extension-internal.yaml and extension-external.yaml files.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class ExtensionConfig {

    /**
     * List of extension definitions
     */
    private List<ExtensionDefinition> extensions;

    /**
     * Represents a single extension definition in the YAML file
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @ToString
    public static class ExtensionDefinition {
        /**
         * Unique identifier for the extension event
         */
        private String eventName;

        /**
         * Fully qualified class name of the implementation
         */
        private String className;

        /**
         * Human-readable description of the extension's purpose
         */
        private String description;

        /**
         * Version of the extension implementation
         */
        private String version;
    }
}
