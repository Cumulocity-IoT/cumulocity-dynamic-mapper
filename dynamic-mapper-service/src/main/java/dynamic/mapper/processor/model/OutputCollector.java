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

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe collector for processing outputs including requests, errors, warnings, and logs.
 *
 * Designed for concurrent access - all add operations are thread-safe.
 * Uses CopyOnWriteArrayList for requests (optimized for read-heavy workloads)
 * and ConcurrentLinkedQueue for errors/warnings/logs (optimized for write-heavy workloads).
 *
 * This class eliminates the thread-safety issues present in the original ProcessingContext
 * where ArrayList and other non-thread-safe collections were used.
 */
@Slf4j
public class OutputCollector {
    private final CopyOnWriteArrayList<DynamicMapperRequest> requests;
    private final ConcurrentLinkedQueue<Exception> errors;
    private final ConcurrentLinkedQueue<String> warnings;
    private final ConcurrentLinkedQueue<String> logs;

    /**
     * Creates a new empty OutputCollector.
     */
    public OutputCollector() {
        this.requests = new CopyOnWriteArrayList<>();
        this.errors = new ConcurrentLinkedQueue<>();
        this.warnings = new ConcurrentLinkedQueue<>();
        this.logs = new ConcurrentLinkedQueue<>();
    }

    /**
     * Adds a request to the collector.
     * Thread-safe - can be called concurrently from multiple threads.
     *
     * @param request the request to add
     * @return the index of the added request
     */
    public int addRequest(DynamicMapperRequest request) {
        requests.add(request);
        return requests.size() - 1;
    }

    /**
     * Adds an error to the collector.
     * Thread-safe - can be called concurrently from multiple threads.
     *
     * @param error the error to add
     */
    public void addError(Exception error) {
        errors.add(error);
        log.debug("Error added to collector: {}", error.getMessage());
    }

    /**
     * Adds a warning to the collector.
     * Thread-safe - can be called concurrently from multiple threads.
     *
     * @param warning the warning message to add
     */
    public void addWarning(String warning) {
        warnings.add(warning);
        log.debug("Warning added to collector: {}", warning);
    }

    /**
     * Adds a log message to the collector.
     * Thread-safe - can be called concurrently from multiple threads.
     *
     * @param logMessage the log message to add
     */
    public void addLog(String logMessage) {
        logs.add(logMessage);
    }

    /**
     * Gets an immutable view of all collected requests.
     * Safe to iterate even if other threads are adding requests.
     *
     * @return immutable list of requests
     */
    public List<DynamicMapperRequest> getRequests() {
        return Collections.unmodifiableList(new ArrayList<>(requests));
    }

    /**
     * Gets an immutable view of all collected errors.
     *
     * @return immutable list of errors
     */
    public List<Exception> getErrors() {
        return Collections.unmodifiableList(new ArrayList<>(errors));
    }

    /**
     * Gets an immutable view of all collected warnings.
     *
     * @return immutable list of warnings
     */
    public List<String> getWarnings() {
        return Collections.unmodifiableList(new ArrayList<>(warnings));
    }

    /**
     * Gets an immutable view of all collected logs.
     *
     * @return immutable list of log messages
     */
    public List<String> getLogs() {
        return Collections.unmodifiableList(new ArrayList<>(logs));
    }

    /**
     * Gets the current request (last added request) or null if no requests exist.
     *
     * @return the last request or null
     */
    public DynamicMapperRequest getCurrentRequest() {
        if (requests.isEmpty()) {
            return null;
        }
        return requests.get(requests.size() - 1);
    }

    /**
     * Checks if any errors have been collected.
     *
     * @return true if at least one error exists
     */
    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    /**
     * Checks if any warnings have been collected.
     *
     * @return true if at least one warning exists
     */
    public boolean hasWarnings() {
        return !warnings.isEmpty();
    }

    /**
     * Gets the total number of requests collected.
     *
     * @return the number of requests
     */
    public int getRequestCount() {
        return requests.size();
    }

    /**
     * Gets the total number of errors collected.
     *
     * @return the number of errors
     */
    public int getErrorCount() {
        return errors.size();
    }

    /**
     * Gets the total number of warnings collected.
     *
     * @return the number of warnings
     */
    public int getWarningCount() {
        return warnings.size();
    }

    /**
     * Gets the total number of log messages collected.
     *
     * @return the number of log messages
     */
    public int getLogCount() {
        return logs.size();
    }

    /**
     * Clears all collected data.
     * Use with caution - typically used for cleanup or reset scenarios.
     */
    public void clear() {
        requests.clear();
        errors.clear();
        warnings.clear();
        logs.clear();
    }
}
