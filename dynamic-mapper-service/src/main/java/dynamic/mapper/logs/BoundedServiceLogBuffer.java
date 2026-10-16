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

package dynamic.mapper.logs;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * In-memory {@link ServiceLogBuffer}, bounded <b>per tenant</b> (the bound is passed with each record, because it is a
 * setting of that tenant): when a tenant's queue is full the oldest record is dropped, so one tenant that logs furiously
 * (or whose events cannot be stored) can neither exhaust the service's memory nor push out another tenant's lines.
 * Dropped records are counted.
 *
 * <p>Called from every logging thread, so all access is guarded by one short lock; nothing is formatted or sent while
 * holding it.
 */
public class BoundedServiceLogBuffer implements ServiceLogBuffer {

    private final Map<String, Deque<ServiceLogRecord>> queues = new HashMap<>();
    private final Map<String, Integer> dropped = new HashMap<>();
    private final Map<String, Long> bytes = new HashMap<>();
    private long totalBytes;

    /** Most memory one tenant's waiting lines may take, whatever its record bound: its records are dropped oldest first. */
    static final long MAX_BYTES_PER_TENANT = 16L * 1024 * 1024;
    /** Most memory all tenants' waiting lines may take together; a tenant over it loses its own oldest lines. */
    static final long MAX_BYTES_TOTAL = 128L * 1024 * 1024;

    /** A conservative estimate of what a record keeps alive: its text (UTF-16 worst case) and its headers. */
    static long sizeOf(ServiceLogRecord r) {
        return 2L * r.text().length() + 200;
    }

    public synchronized void offer(ServiceLogRecord record, int maxPerTenant) {
        Deque<ServiceLogRecord> queue = queues.computeIfAbsent(record.tenant(), t -> new ArrayDeque<>());
        queue.addLast(record);
        add(record.tenant(), sizeOf(record));
        trim(record.tenant(), queue, maxPerTenant);
    }

    @Override
    public synchronized Map<String, List<ServiceLogRecord>> drain(Collection<String> tenants) {
        Map<String, List<ServiceLogRecord>> out = new LinkedHashMap<>();
        for (String tenant : tenants) {
            Deque<ServiceLogRecord> queue = queues.remove(tenant);
            forget(tenant);
            if (queue != null && !queue.isEmpty()) {
                out.put(tenant, new ArrayList<>(queue));
            }
        }
        return out;
    }

    @Override
    public synchronized void retainOnly(Set<String> keep) {
        queues.keySet().removeIf(tenant -> !keep.contains(tenant));
        new java.util.ArrayList<>(bytes.keySet()).stream().filter(t -> !keep.contains(t)).forEach(this::forget);
        dropped.keySet().removeIf(tenant -> !keep.contains(tenant));
    }

    @Override
    public synchronized void discard(String tenant) {
        queues.remove(tenant);
        forget(tenant);
        dropped.remove(tenant);
    }

    @Override
    public synchronized void restore(String tenant, List<ServiceLogRecord> records, int maxPerTenant) {
        Deque<ServiceLogRecord> queue = queues.computeIfAbsent(tenant, t -> new ArrayDeque<>());
        List<ServiceLogRecord> reversed = new ArrayList<>(records);
        Collections.reverse(reversed);
        for (ServiceLogRecord r : reversed) {
            queue.addFirst(r); // the oldest ends up first again
            add(tenant, sizeOf(r));
        }
        trim(tenant, queue, maxPerTenant);
    }

    private void trim(String tenant, Deque<ServiceLogRecord> queue, int maxPerTenant) {
        while (!queue.isEmpty() && (queue.size() > maxPerTenant || bytes.getOrDefault(tenant, 0L) > MAX_BYTES_PER_TENANT
                || totalBytes > MAX_BYTES_TOTAL)) {
            add(tenant, -sizeOf(queue.pollFirst()));
            dropped.merge(tenant, 1, Integer::sum);
        }
    }

    private void add(String tenant, long delta) {
        bytes.merge(tenant, delta, Long::sum);
        totalBytes += delta;
    }

    private void forget(String tenant) {
        Long held = bytes.remove(tenant);
        if (held != null) {
            totalBytes -= held;
        }
    }

    @Override
    public synchronized Map<String, Integer> takeDropped(Collection<String> tenants) {
        Map<String, Integer> out = new HashMap<>();
        for (String tenant : tenants) {
            Integer n = dropped.remove(tenant);
            if (n != null) {
                out.put(tenant, n);
            }
        }
        return out;
    }
}
