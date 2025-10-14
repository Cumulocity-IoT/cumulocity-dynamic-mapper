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

package dynamic.mapper.model;

import lombok.Getter;

import java.util.HashMap;
import java.util.Map;

/**
 * Exception thrown when mapping resolution fails.
 * This can occur during topic matching, filter evaluation, or tree traversal.
 * 
 * Provides additional context about the resolution failure.
 */
@Getter
public class ResolveException extends Exception {

    private final String tenant;
    private final String topic;
    private final ResolveErrorType errorType;
    private final Map<String, Object> context;

    /**
     * Constructs a new ResolveException with the specified detail message.
     *
     * @param message the detail message
     */
    public ResolveException(String message) {
        super(message);
        this.tenant = null;
        this.topic = null;
        this.errorType = ResolveErrorType.UNKNOWN;
        this.context = new HashMap<>();
    }

    /**
     * Constructs a new ResolveException with the specified detail message and cause.
     *
     * @param message the detail message
     * @param cause the cause of the exception
     */
    public ResolveException(String message, Throwable cause) {
        super(message, cause);
        this.tenant = null;
        this.topic = null;
        this.errorType = ResolveErrorType.UNKNOWN;
        this.context = new HashMap<>();
    }

    /**
     * Constructs a new ResolveException with the specified cause.
     *
     * @param cause the cause of the exception
     */
    public ResolveException(Throwable cause) {
        super(cause);
        this.tenant = null;
        this.topic = null;
        this.errorType = ResolveErrorType.UNKNOWN;
        this.context = new HashMap<>();
    }

    /**
     * Constructs a new ResolveException with full context information.
     *
     * @param message the detail message
     * @param tenant the tenant context
     * @param topic the topic being resolved
     * @param errorType the type of resolution error
     */
    public ResolveException(String message, String tenant, String topic, ResolveErrorType errorType) {
        super(formatMessage(message, tenant, topic, errorType));
        this.tenant = tenant;
        this.topic = topic;
        this.errorType = errorType;
        this.context = new HashMap<>();
    }

    /**
     * Constructs a new ResolveException with full context information and cause.
     *
     * @param message the detail message
     * @param cause the cause of the exception
     * @param tenant the tenant context
     * @param topic the topic being resolved
     * @param errorType the type of resolution error
     */
    public ResolveException(String message, Throwable cause, String tenant, String topic, ResolveErrorType errorType) {
        super(formatMessage(message, tenant, topic, errorType), cause);
        this.tenant = tenant;
        this.topic = topic;
        this.errorType = errorType;
        this.context = new HashMap<>();
    }

    /**
     * Adds additional context information to the exception.
     *
     * @param key the context key
     * @param value the context value
     * @return this exception for method chaining
     */
    public ResolveException addContext(String key, Object value) {
        this.context.put(key, value);
        return this;
    }

    /**
     * Gets a context value.
     *
     * @param key the context key
     * @return the context value, or null if not found
     */
    public Object getContext(String key) {
        return this.context.get(key);
    }

    /**
     * Formats the exception message with context information.
     */
    private static String formatMessage(String message, String tenant, String topic, ResolveErrorType errorType) {
        StringBuilder sb = new StringBuilder();
        
        if (tenant != null) {
            sb.append("[Tenant: ").append(tenant).append("] ");
        }
        
        if (errorType != null && errorType != ResolveErrorType.UNKNOWN) {
            sb.append("[").append(errorType).append("] ");
        }
        
        sb.append(message);
        
        if (topic != null) {
            sb.append(" (Topic: ").append(topic).append(")");
        }
        
        return sb.toString();
    }

    /**
     * Creates a ResolveException for topic pattern errors.
     */
    public static ResolveException topicPatternError(String tenant, String topic, String reason) {
        return new ResolveException(
            "Invalid topic pattern: " + reason,
            tenant,
            topic,
            ResolveErrorType.INVALID_TOPIC_PATTERN
        );
    }

    /**
     * Creates a ResolveException for tree traversal errors.
     */
    public static ResolveException treeTraversalError(String tenant, String topic, Throwable cause) {
        return new ResolveException(
            "Failed to traverse mapping tree",
            cause,
            tenant,
            topic,
            ResolveErrorType.TREE_TRAVERSAL_ERROR
        );
    }

    /**
     * Creates a ResolveException for duplicate mapping errors.
     */
    public static ResolveException duplicateMappingError(String tenant, String topic, String mappingId) {
        return new ResolveException(
            "Duplicate mapping detected: " + mappingId,
            tenant,
            topic,
            ResolveErrorType.DUPLICATE_MAPPING
        ).addContext("mappingId", mappingId);
    }

    /**
     * Creates a ResolveException for filter evaluation errors.
     */
    public static ResolveException filterEvaluationError(String tenant, String filter, Throwable cause) {
        return new ResolveException(
            "Failed to evaluate filter: " + filter,
            cause,
            tenant,
            null,
            ResolveErrorType.FILTER_EVALUATION_ERROR
        ).addContext("filter", filter);
    }

    /**
     * Creates a ResolveException for when no mappings are found.
     */
    public static ResolveException noMappingsFound(String tenant, String topic) {
        return new ResolveException(
            "No mappings found for topic",
            tenant,
            topic,
            ResolveErrorType.NO_MAPPINGS_FOUND
        );
    }

    /**
     * Enumeration of possible resolution error types.
     */
    public enum ResolveErrorType {
        /** Unknown or unspecified error */
        UNKNOWN,
        
        /** Invalid topic pattern syntax */
        INVALID_TOPIC_PATTERN,
        
        /** Error during tree traversal */
        TREE_TRAVERSAL_ERROR,
        
        /** Duplicate mapping detected */
        DUPLICATE_MAPPING,
        
        /** Filter evaluation failed */
        FILTER_EVALUATION_ERROR,
        
        /** No mappings found for the given criteria */
        NO_MAPPINGS_FOUND,
        
        /** Mapping tree is not initialized */
        TREE_NOT_INITIALIZED,
        
        /** Circular reference detected */
        CIRCULAR_REFERENCE,
        
        /** Maximum depth exceeded */
        MAX_DEPTH_EXCEEDED
    }
}
