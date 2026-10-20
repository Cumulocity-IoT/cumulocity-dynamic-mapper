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

package dynamic.mapper.processor.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Copies a parsed JSON payload (nested maps and lists), so that each mapping of one notification works on its own tree.
 *
 * <p>An outbound notification is parsed once and then handed to every matching mapping, which run in parallel. The
 * enrichment of a JSONata mapping writes its own tokens ({@code _IDENTITY_}, {@code _RETAIN_}, {@code _TOPIC_LEVEL_})
 * into the payload it was given, while a Smart Function mapping serializes it: on a shared tree that is a
 * {@link java.util.ConcurrentModificationException}, and one mapping's tokens show up in another mapping's payload.
 *
 * <p>Only the containers are copied; the values in them (strings, numbers, booleans) are immutable and shared. Key order
 * is kept.
 */
public final class PayloadCopy {

    private PayloadCopy() {
    }

    @SuppressWarnings("unchecked")
    public static <T> T deepCopy(T value) {
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>(Math.max(16, (int) (map.size() / 0.75f) + 1));
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                copy.put(entry.getKey(), deepCopy(entry.getValue()));
            }
            return (T) copy;
        }
        if (value instanceof Collection<?> collection) {
            List<Object> copy = new ArrayList<>(collection.size());
            for (Object element : collection) {
                copy.add(deepCopy(element));
            }
            return (T) copy;
        }
        return value;
    }
}
