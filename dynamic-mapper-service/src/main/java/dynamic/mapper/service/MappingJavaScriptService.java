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

package dynamic.mapper.service;

import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.model.Mapping;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.graalvm.polyglot.Context;
import org.slf4j.bridge.SLF4JBridgeHandler;
import org.springframework.stereotype.Service;

import java.util.logging.Handler;

/**
 * Manages JavaScript code execution for mappings
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MappingJavaScriptService {

    private static final Handler GRAALJS_LOG_HANDLER = new SLF4JBridgeHandler();
    
    private final ConfigurationRegistry configurationRegistry;

    /**
     * Removes JavaScript code from the GraalVM engine
     */
    public void removeCodeFromEngine(String tenant, Mapping mapping) {
        if (mapping.getCode() == null || mapping.getCode().isBlank()) {
            return;
        }

        String functionName = Mapping.EXTRACT_FROM_SOURCE + "_" + mapping.getIdentifier();
        
        try (Context context = Context.newBuilder("js")
                .engine(configurationRegistry.getGraalEngine(tenant))
                .logHandler(GRAALJS_LOG_HANDLER)
                .allowHostAccess(configurationRegistry.getHostAccess())
                .option("js.foreign-object-prototype", "true")
                .build()) {

            // Remove the function from global scope
            context.eval("js", "delete globalThis." + functionName);
            
            log.debug("{} - Removed JavaScript function: {}", tenant, functionName);
            
        } catch (Exception e) {
            log.warn("{} - Failed to remove JavaScript code for mapping {}: {}", 
                tenant, mapping.getId(), e.getMessage());
        }
    }
}
