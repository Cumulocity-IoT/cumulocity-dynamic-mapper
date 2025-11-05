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

package dynamic.mapper.processor.util;

import java.util.Collection;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class GraalVMLeakDetector {

    /**
     * Validates that an object doesn't contain any GraalVM Value references
     * Throws exception if leak detected
     */
    public static void validateNoGraalVMValues(Object obj, String context) {
        if (obj == null) {
            return;
        }

        String className = obj.getClass().getName();

        // Check if object is a GraalVM Value
        if (className.contains("org.graalvm.polyglot.Value")) {
            String error = String.format(
                    "MEMORY LEAK DETECTED: GraalVM Value found in %s. Class: %s",
                    context, className);
            log.error(error);
            throw new IllegalStateException(error);
        }

        // Recursively check collections
        if (obj instanceof Collection) {
            for (Object item : (Collection<?>) obj) {
                validateNoGraalVMValues(item, context + " (collection element)");
            }
        }

        // Recursively check maps
        if (obj instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) obj;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                validateNoGraalVMValues(entry.getKey(), context + " (map key)");
                validateNoGraalVMValues(entry.getValue(), context + " (map value)");
            }
        }
    }

    /**
     * Logs warning instead of throwing exception
     */
    public static boolean containsGraalVMValues(Object obj) {
        if (obj == null) {
            return false;
        }

        String className = obj.getClass().getName();
        if (className.contains("org.graalvm.polyglot.Value")) {
            return true;
        }

        if (obj instanceof Collection) {
            for (Object item : (Collection<?>) obj) {
                if (containsGraalVMValues(item)) {
                    return true;
                }
            }
        }

        if (obj instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) obj;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (containsGraalVMValues(entry.getKey()) ||
                        containsGraalVMValues(entry.getValue())) {
                    return true;
                }
            }
        }

        return false;
    }
}