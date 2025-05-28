/*
 * Copyright (c) 2025 Cumulocity GmbH.
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

package dynamic.mapping.processor;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.graalvm.polyglot.Engine;
import org.springframework.beans.factory.annotation.Autowired;
import org.graalvm.polyglot.Context;

import dynamic.mapping.core.ConfigurationRegistry;

public class GraalContextPool {

    @Autowired
    ConfigurationRegistry configurationRegistry;

    private final BlockingQueue<Context> contextPool;
    private final Engine graalsEngine;
    private final int maxPoolSize;

    public GraalContextPool(Engine graalsEngine, int poolSize) {
        this.graalsEngine = graalsEngine;
        this.maxPoolSize = poolSize;
        this.contextPool = new LinkedBlockingQueue<>(poolSize);

        // Pre-warm the pool
        for (int i = 0; i < poolSize; i++) {
            contextPool.offer(createContext());
        }
    }

    public Context borrowContext() throws InterruptedException {
        Context context = contextPool.poll(1, TimeUnit.SECONDS);
        return context != null ? context : createContext();
    }

    public void returnContext(Context context) {
        if (contextPool.size() < maxPoolSize) {
            // Reset context state if needed
            contextPool.offer(context);
        } else {
            context.close();
        }
    }

    private Context createContext() {
        Context graalsContext = Context.newBuilder("js")
                .engine(graalsEngine)
                // .option("engine.WarnInterpreterOnly", "false")
                .allowHostAccess(configurationRegistry.getHostAccess())
                .allowHostClassLookup(className ->
                // Allow only the specific SubstitutionContext class
                className.equals("dynamic.mapping.processor.model.SubstitutionContext")
                        || className.equals("dynamic.mapping.processor.model.SubstitutionResult")
                        || className.equals("dynamic.mapping.processor.model.SubstituteValue")
                        || className.equals("dynamic.mapping.processor.model.SubstituteValue$TYPE")
                        || className.equals("dynamic.mapping.processor.model.RepairStrategy")
                        // Allow base collection classes needed for return values
                        || className.equals("java.util.ArrayList") ||
                        className.equals("java.util.HashMap"))
                .build();

        return graalsContext;
    }
}