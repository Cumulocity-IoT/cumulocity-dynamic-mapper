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

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class JavaScriptConsole {
    private final DataPrepContext flowContext;
    private final String tenant;
    
    public JavaScriptConsole(DataPrepContext flowContext, String tenant) {
        this.flowContext = flowContext;
        this.tenant = tenant;
    }
    
    public void log(Object... args) {
        String message = formatArgs(args);
        log.info("{} - JS: {}", tenant, message);
        flowContext.addLogMessage("LOG: " + message);
    }
    
    public void error(Object... args) {
        String message = formatArgs(args);
        log.error("{} - JS: {}", tenant, message);
        flowContext.addLogMessage("ERROR: " + message);
    }
    
    public void warn(Object... args) {
        String message = formatArgs(args);
        log.warn("{} - JS: {}", tenant, message);
        flowContext.addLogMessage("WARN: " + message);
    }
    
    public void debug(Object... args) {
        String message = formatArgs(args);
        log.debug("{} - JS: {}", tenant, message);
        flowContext.addLogMessage("DEBUG: " + message);
    }
    
    private String formatArgs(Object... args) {
        if (args == null || args.length == 0) return "";
        
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(" ");
            sb.append(args[i] != null ? args[i].toString() : "null");
        }
        return sb.toString();
    }
}
