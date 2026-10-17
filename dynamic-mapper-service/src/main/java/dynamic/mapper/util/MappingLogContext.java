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

import java.util.Map;
import java.util.regex.Pattern;

import org.slf4j.MDC;

/**
 * Puts the mapping a unit of work processes a payload for into the logging context, so every log line written by it
 * carries the mapping's {@code identifier} (see {@link MappingLogConverter}) and the service log can be filtered by
 * mapping (docs/planning/IMPLEMENTATION-PLAN-SERVICE-LOGS-MAPPING-FILTER.md).
 *
 * <p>Opened around the processing of <em>one</em> mapping on its worker thread. It is deliberately not propagated
 * by {@link MdcPropagatingExecutorService} (which carries only the tenant): the router fans out one task per mapping and
 * each sets its own. Lines of the connector or the dispatcher run outside any such scope and stay untagged.
 *
 * <p>Only identifiers of the form {@link #VALID} are accepted: the value ends up in a log line and in the name of an
 * event fragment, so anything else is treated as "no mapping".
 */
public final class MappingLogContext implements AutoCloseable {

    /** The MDC key. */
    public static final String KEY = "mapperMapping";

    /** What an identifier may look like: generated alphanumerics, plus {@code _} and {@code -}. */
    public static final Pattern VALID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    /** Whether {@code identifier} can be used as a log tag / fragment name. */
    public static boolean isValid(String identifier) {
        return identifier != null && VALID.matcher(identifier).matches();
    }

    /** The mapping of a log event's MDC, or {@code null}. */
    public static String mappingOf(Map<String, String> mdc) {
        if (mdc == null) {
            return null;
        }
        String mapping = mdc.get(KEY);
        return isValid(mapping) ? mapping : null;
    }

    private final String previous;

    private MappingLogContext(String identifier) {
        this.previous = MDC.get(KEY);
        if (isValid(identifier)) {
            MDC.put(KEY, identifier);
        } else {
            MDC.remove(KEY);
        }
    }

    /** Opens a scope for {@code identifier}; close it (try-with-resources) to restore the previous value. */
    public static MappingLogContext open(String identifier) {
        return new MappingLogContext(identifier);
    }

    @Override
    public void close() {
        if (previous == null) {
            MDC.remove(KEY);
        } else {
            MDC.put(KEY, previous);
        }
    }
}
