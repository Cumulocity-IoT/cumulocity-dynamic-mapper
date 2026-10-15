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
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory {@link ServiceLogBuffer}, bounded <b>per tenant</b>: when a tenant's queue is full the oldest record is
 * dropped, so one tenant that logs furiously (or whose events cannot be stored) can neither exhaust the service's memory
 * nor push out another tenant's lines. Dropped records are counted.
 *
 * <p>Called from every logging thread, so all access is guarded by one short lock; nothing is formatted or sent while
 * holding it.
 */
public class BoundedServiceLogBuffer implements ServiceLogBuffer {

    private final int maxPerTenant;
    private final Map<String, Deque<ServiceLogRecord>> queues = new HashMap<>();
    private final Map<String, Integer> dropped = new HashMap<>();

    public BoundedServiceLogBuffer(int maxPerTenant) {
        if (maxPerTenant < 1) {
            throw new IllegalArgumentException("maxPerTenant must be >= 1");
        }
        this.maxPerTenant = maxPerTenant;
    }

    public synchronized void offer(ServiceLogRecord record) {
        Deque<ServiceLogRecord> queue = queues.computeIfAbsent(record.tenant(), t -> new ArrayDeque<>());
        if (queue.size() >= maxPerTenant) {
            queue.pollFirst();
            dropped.merge(record.tenant(), 1, Integer::sum);
        }
        queue.addLast(record);
    }

    @Override
    public synchronized Map<String, List<ServiceLogRecord>> drainAll() {
        Map<String, List<ServiceLogRecord>> out = new LinkedHashMap<>();
        queues.forEach((tenant, queue) -> {
            if (!queue.isEmpty()) {
                out.put(tenant, new ArrayList<>(queue));
            }
        });
        queues.clear();
        return out;
    }

    @Override
    public synchronized void restore(String tenant, List<ServiceLogRecord> records) {
        Deque<ServiceLogRecord> queue = queues.computeIfAbsent(tenant, t -> new ArrayDeque<>());
        List<ServiceLogRecord> reversed = new ArrayList<>(records);
        Collections.reverse(reversed);
        for (ServiceLogRecord r : reversed) {
            queue.addFirst(r); // the oldest ends up first again
        }
        while (queue.size() > maxPerTenant) {
            queue.pollFirst();
            dropped.merge(tenant, 1, Integer::sum);
        }
    }

    @Override
    public synchronized Map<String, Integer> takeDropped() {
        Map<String, Integer> out = new HashMap<>(dropped);
        dropped.clear();
        return out;
    }
}
