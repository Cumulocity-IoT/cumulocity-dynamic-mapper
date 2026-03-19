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

package dynamic.mapper.processor.model;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Thread-safe class for managing mutable processing state during message transformation.
 *
 * Designed for concurrent access - all state modifications are thread-safe.
 * Uses ConcurrentHashMap for the processing cache and AtomicBoolean for flags.
 *
 * This class eliminates the thread-safety issues present in the original ProcessingContext
 * where TreeMap was used without synchronization.
 */
@Slf4j
public class ProcessingState {
    @Getter
    private final ProcessingType processingType;

    @Getter
    private final MappingType mappingType;

    /**
     * Cache for storing substitution values during processing.
     * Key: path target (e.g., "measurement.temperature.value")
     * Value: list of substitution values for that path
     *
     * Thread-safe: Uses ConcurrentHashMap with synchronized list values
     */
    private final ConcurrentHashMap<String, List<SubstituteValue>> processingCache;

    /**
     * Flag indicating whether the mapping needs repair (e.g., device lookup failed).
     * Thread-safe: Uses AtomicBoolean for lock-free concurrent updates.
     */
    private final AtomicBoolean needsRepair;

    /**
     * Flag indicating whether to ignore further processing.
     * Thread-safe: Uses AtomicBoolean for lock-free concurrent updates.
     */
    private final AtomicBoolean ignoreFurtherProcessing;

    /**
     * Creates a new ProcessingState with the specified types.
     *
     * @param processingType the type of processing operation
     * @param mappingType the type of mapping being applied
     */
    public ProcessingState(ProcessingType processingType, MappingType mappingType) {
        this.processingType = processingType;
        this.mappingType = mappingType;
        this.processingCache = new ConcurrentHashMap<>();
        this.needsRepair = new AtomicBoolean(false);
        this.ignoreFurtherProcessing = new AtomicBoolean(false);
    }

    /**
     * Adds a substitution value to the processing cache.
     * Thread-safe - can be called concurrently from multiple threads.
     *
     * @param key the path target for the substitution
     * @param value the value to substitute
     * @param type the type of substitution
     * @param repairStrategy the repair strategy for this substitution
     * @param expandArray whether to expand array values
     */
    public void addSubstitution(String key, Object value, SubstituteValue.TYPE type,
                                RepairStrategy repairStrategy, boolean expandArray) {
        SubstituteValue substituteValue = new SubstituteValue(value, type, repairStrategy, expandArray);

        processingCache.compute(key, (k, existingList) -> {
            if (existingList == null) {
                // Create a new synchronized list for this key
                List<SubstituteValue> newList = Collections.synchronizedList(new ArrayList<>());
                newList.add(substituteValue);
                return newList;
            } else {
                // Add to existing list (thread-safe because list is synchronized)
                existingList.add(substituteValue);
                return existingList;
            }
        });

        log.debug("Added substitution: key={}, type={}, value={}", key, type, value);
    }

    /**
     * Puts a complete list of substitution values for a given key.
     * Thread-safe - replaces the entire list atomically.
     *
     * @param key the path target
     * @param values the list of substitution values
     */
    public void putSubstitutions(String key, List<SubstituteValue> values) {
        // Wrap in synchronized list for thread-safe access
        processingCache.put(key, Collections.synchronizedList(new ArrayList<>(values)));
    }

    /**
     * Gets substitution values for a given key.
     * Returns an immutable view to prevent concurrent modification issues.
     *
     * @param key the path target
     * @return immutable list of substitution values, or empty list if not found
     */
    public List<SubstituteValue> getSubstitutions(String key) {
        List<SubstituteValue> values = processingCache.get(key);
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        // Return immutable copy to prevent external modification
        synchronized (values) {
            return Collections.unmodifiableList(new ArrayList<>(values));
        }
    }

    /**
     * Gets all path targets (keys) in the processing cache.
     *
     * @return immutable set of all path targets
     */
    public Set<String> getPathTargets() {
        return Collections.unmodifiableSet(processingCache.keySet());
    }

    /**
     * Gets the size of the processing cache.
     *
     * @return number of entries in the cache
     */
    public int getCacheSize() {
        return processingCache.size();
    }

    /**
     * Gets the entire processing cache as an immutable map.
     * Each list value is also wrapped as immutable.
     *
     * @return immutable view of the processing cache
     */
    public Map<String, List<SubstituteValue>> getProcessingCache() {
        Map<String, List<SubstituteValue>> immutableCache = new ConcurrentHashMap<>();
        processingCache.forEach((key, values) -> {
            synchronized (values) {
                immutableCache.put(key, Collections.unmodifiableList(new ArrayList<>(values)));
            }
        });
        return Collections.unmodifiableMap(immutableCache);
    }

    /**
     * Checks if the mapping needs repair.
     *
     * @return true if repair is needed
     */
    public boolean needsRepair() {
        return needsRepair.get();
    }

    /**
     * Sets the repair flag.
     * Thread-safe - uses atomic operation.
     *
     * @param value true to mark as needing repair
     */
    public void setNeedsRepair(boolean value) {
        needsRepair.set(value);
    }

    /**
     * Checks if further processing should be ignored.
     *
     * @return true if further processing should be skipped
     */
    public boolean shouldIgnoreFurtherProcessing() {
        return ignoreFurtherProcessing.get();
    }

    /**
     * Sets the ignore further processing flag.
     * Thread-safe - uses atomic operation.
     *
     * @param value true to ignore further processing
     */
    public void setIgnoreFurtherProcessing(boolean value) {
        ignoreFurtherProcessing.set(value);
    }

    /**
     * Clears all state (cache and flags).
     * Use with caution - typically only for cleanup or reset scenarios.
     */
    public void clear() {
        processingCache.clear();
        needsRepair.set(false);
        ignoreFurtherProcessing.set(false);
        log.debug("Processing state cleared");
    }
}
