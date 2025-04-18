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

package dynamic.mapping.processor.model;

public enum RepairStrategy {
    DEFAULT, // Process substitution as defined                  
    USE_FIRST_VALUE_OF_ARRAY, // If extracted content from the source payload is an array, copy only the first item to the target payload
    USE_LAST_VALUE_OF_ARRAY, // If extracted content from the source payload is an array, copy only the last item to the target payload
    IGNORE,
    REMOVE_IF_MISSING_OR_NULL, // Remove the node in the target if it the evaluation of the source expression returns undefined, empty. This allows for using mapping with dynamic content
    CREATE_IF_MISSING, // Create the node in the target if it doesn't exist. This allows for using mapping with dynamic content
}
