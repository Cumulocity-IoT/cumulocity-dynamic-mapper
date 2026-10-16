/*
 * Copyright (c) 2025 Cumulocity GmbH
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
 * @authors Christof Strack
 */

package dynamic.mapper.util;

import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.pattern.MessageConverter;

/**
 * Registers the tenant-aware conversion words in logback's process-wide registries.
 *
 * <p>This is deliberately <em>not</em> done with {@code <conversionRule>} in
 * {@code logback-spring.xml}: in the deployed service the platform's own
 * {@code /etc/<service>-logging.xml} takes part in configuring logging, and a pattern parsed by a
 * configuration that never saw our rule prints {@code %PARSER_ERROR[tenantId]} on every line.
 * The static registries apply to every {@code PatternLayout} in the JVM whichever file built it.
 *
 * <p>Must run before logging is initialised, i.e. first thing in {@code main}.
 */
public final class TenantLogging {

    private TenantLogging() {
    }

    /** {@code %tenantId} — the line's tenant field. */
    public static final String TENANT_WORD = "tenantId";

    public static void register() {
        put(TENANT_WORD, TenantLogConverter.class, TenantLogConverter::new);
        // %m and its aliases: drop a leading "<tenant> - " that repeats the tenant field.
        for (String word : new String[] { "m", "msg", "message" }) {
            put(word, TenantMessageConverter.class, TenantMessageConverter::new);
        }
    }

    private static void put(String word, Class<? extends ClassicConverter> type,
            java.util.function.Supplier<ClassicConverter> supplier) {
        PatternLayout.DEFAULT_CONVERTER_MAP.put(word, type.getName());
        PatternLayout.DEFAULT_CONVERTER_SUPPLIER_MAP.put(word, supplier::get);
    }
}
