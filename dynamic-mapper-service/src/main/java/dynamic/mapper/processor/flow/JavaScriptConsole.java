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

 package dynamic.mapper.processor.flow;

import org.graalvm.polyglot.Value;
import com.fasterxml.jackson.databind.ObjectMapper;
import dynamic.mapper.model.Mapping;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class JavaScriptConsole {
    private final DataPrepContext flowContext;
    private final String tenant;
    private final Mapping mapping;
    private static final ObjectMapper objectMapper = new ObjectMapper();

    public JavaScriptConsole(DataPrepContext flowContext, String tenant, Mapping mapping) {
        this.flowContext = flowContext;
        this.tenant = tenant;
        this.mapping = mapping;
    }
    
    public void log(Object... args) {
        String message = formatArgs(args);
        log.info("{} - JS LOG: {}", tenant, message);
        flowContext.addLogMessage("JS LOG: " + message);
    }

    public void error(Object... args) {
        String message = formatArgs(args);
        log.error("{} - JS ERROR: {}", tenant, message);
        flowContext.addLogMessage("JS ERROR: " + message);
    }

    public void warn(Object... args) {
        String message = formatArgs(args);
        log.warn("{} - JS WARN: {}", tenant, message);
        flowContext.addLogMessage("JS WARN: " + message);
    }

    public void debug(Object... args) {
        String message = formatArgs(args);
        // Use INFO level when mapping.debug is enabled, otherwise use DEBUG level
        if (mapping != null && mapping.getDebug()) {
            log.info("{} - JS DEBUG: {}", tenant, message);
        } else {
            log.debug("{} - JS DEBUG: {}", tenant, message);
        }
        flowContext.addLogMessage("JS DEBUG: " + message);
    }
    
    private String formatArgs(Object... args) {
        if (args == null || args.length == 0) return "";

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(" ");
            sb.append(formatSingleArg(args[i]));
        }
        return sb.toString();
    }

    private String formatSingleArg(Object arg) {
        if (arg == null) {
            return "null";
        }

        // Check if it's a GraalVM Value object
        if (arg instanceof Value) {
            Value value = (Value) arg;

            // Handle primitive types
            if (value.isString()) {
                return value.asString();
            }
            if (value.isNumber()) {
                return value.toString();
            }
            if (value.isBoolean()) {
                return String.valueOf(value.asBoolean());
            }
            if (value.isNull()) {
                return "null";
            }

            // Handle objects and arrays - convert to JSON
            try {
                // Convert GraalVM Value to Java object, then to JSON string
                Object javaObject = value.as(Object.class);
                return objectMapper.writeValueAsString(javaObject);
            } catch (Exception e) {
                // Fallback to toString if JSON conversion fails
                log.debug("Failed to convert Value to JSON: {}", e.getMessage());
                return value.toString();
            }
        }

        // For regular Java objects, try to serialize as JSON if it's a complex type
        if (arg instanceof String || arg.getClass().isPrimitive()) {
            return arg.toString();
        }

        try {
            return objectMapper.writeValueAsString(arg);
        } catch (Exception e) {
            return arg.toString();
        }
    }
}
