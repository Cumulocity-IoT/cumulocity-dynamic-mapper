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

package dynamic.mapping.core;

import java.util.logging.Handler;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.graalvm.polyglot.Engine;
import org.slf4j.bridge.SLF4JBridgeHandler;

@Configuration
public class GraalConfig {
    /**
     * Logging bridge so that console.logs will end up in SLF4J
     */
    // private static final Handler GRAALJS_LOG_HANDLER = new SLF4JBridgeHandler();

    @Bean
    public Engine graalEngine() {
        return Engine.newBuilder()
            .option("engine.WarnInterpreterOnly", "false")
            .build();
    }
}