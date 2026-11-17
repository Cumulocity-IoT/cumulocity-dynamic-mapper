/*
 * Copyright (c) 2022-2026 Cumulocity GmbH.
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

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyArray;
import org.graalvm.polyglot.proxy.ProxyObject;

/**
 * Lazy counterparts of the {@link ProxyObject}/{@link ProxyArray} trees that
 * {@link JavaScriptInteropHelper#toJsInterop} builds eagerly.
 *
 * <p>The eager conversion copies the whole payload into proxy objects before the Smart Function
 * runs, although most functions read a few fields of a large message (or reject it after reading
 * one). These views wrap the Java {@link Map}/{@link List} as it is and convert a child only when
 * the script first reads it; a read child is remembered, so {@code msg.a === msg.a} still holds.
 *
 * <p><b>Copy semantics are kept.</b> The eager conversion detached the script from the Java data: a
 * script that wrote into the payload changed only its own copy. A view therefore never writes into
 * the source. The first write (or delete) switches that one object to a private eager copy and
 * every later access goes to it, so a script observes exactly what it would have with the eager
 * conversion.
 *
 * <p><b>Contract.</b> The source must not change while a script holds the view, and it must have
 * String keys. A payload, a state value and the config meet both; use the eager conversion for data
 * that is shared with other threads or changed by Java code during the call (the whole state map,
 * the inventory cache).
 *
 * <p>Instances belong to one polyglot context and one thread, like the context itself, and are not
 * synchronized.
 */
final class LazyJsProxies {

    private LazyJsProxies() {
    }

    /** Wraps a source value: maps and collections become lazy views, anything else is returned as is. */
    static Object wrap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return new LazyObject(map);
        }
        if (value instanceof List<?> list) {
            return new LazyArray(list);
        }
        if (value instanceof Collection<?> collection) {
            return new LazyArray(new ArrayList<>(collection));
        }
        return value;
    }

    private static final class LazyObject implements ProxyObject {
        private final Map<?, ?> source;
        // children already wrapped, by key (also keeps their identity stable)
        private Map<String, Object> wrapped;
        // set on the first write; from then on this is the only truth
        private ProxyObject detached;

        LazyObject(Map<?, ?> source) {
            this.source = source;
        }

        @Override
        public Object getMember(String key) {
            if (detached != null) {
                return detached.getMember(key);
            }
            if (wrapped != null) {
                Object cached = wrapped.get(key);
                if (cached != null) {
                    return cached;
                }
            }
            Object value = source.get(key);
            Object result = wrap(value);
            if (result != value && result != null) {
                if (wrapped == null) {
                    wrapped = new HashMap<>();
                }
                wrapped.put(key, result);
            }
            return result;
        }

        @Override
        public Object getMemberKeys() {
            if (detached != null) {
                return detached.getMemberKeys();
            }
            String[] keys = new String[source.size()];
            int i = 0;
            for (Object key : source.keySet()) {
                keys[i++] = String.valueOf(key);
            }
            return ProxyArray.fromArray((Object[]) keys);
        }

        @Override
        public boolean hasMember(String key) {
            return detached != null ? detached.hasMember(key) : source.containsKey(key);
        }

        @Override
        public void putMember(String key, Value value) {
            detach().putMember(key, value);
        }

        @Override
        public boolean removeMember(String key) {
            return detach().removeMember(key);
        }

        private ProxyObject detach() {
            if (detached == null) {
                Map<String, Object> copy = new LinkedHashMap<>();
                for (Object key : source.keySet()) {
                    String name = String.valueOf(key);
                    copy.put(name, getMember(name));
                }
                detached = ProxyObject.fromMap(copy);
            }
            return detached;
        }
    }

    private static final class LazyArray implements ProxyArray {
        private static final Object UNSET = new Object();

        private final List<?> source;
        private Object[] wrapped;
        private ProxyArray detached;

        LazyArray(List<?> source) {
            this.source = source;
        }

        @Override
        public Object get(long index) {
            if (detached != null) {
                return detached.get(index);
            }
            if (index < 0 || index >= source.size()) {
                return null; // reads past the end give undefined, as with the eager copy
            }
            int i = (int) index;
            if (wrapped == null) {
                wrapped = new Object[source.size()];
                java.util.Arrays.fill(wrapped, UNSET);
            }
            Object cached = wrapped[i];
            if (cached == UNSET) {
                cached = wrap(source.get(i));
                wrapped[i] = cached;
            }
            return cached;
        }

        @Override
        public void set(long index, Value value) {
            detach().set(index, value);
        }

        @Override
        public boolean remove(long index) {
            return detach().remove(index);
        }

        @Override
        public long getSize() {
            return detached != null ? detached.getSize() : source.size();
        }

        private ProxyArray detach() {
            if (detached == null) {
                List<Object> copy = new ArrayList<>(source.size());
                for (int i = 0; i < source.size(); i++) {
                    copy.add(get(i));
                }
                detached = ProxyArray.fromList(copy);
            }
            return detached;
        }
    }
}
