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

import lombok.Builder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

import java.util.Map;

/**
 * View into the GraalVM execution resources held by a {@link ProcessingContext}.
 *
 * <p>This class is a <em>view/snapshot</em> — it does NOT own the GraalVM resources it
 * references. The owning {@link ProcessingContext} is responsible for closing the
 * {@code graalContext} via its own {@link ProcessingContext#close()} method.
 * Do NOT call {@code close()} on this object or wrap it in try-with-resources.</p>
 *
 * <p>This class separates execution engine concerns from other processing aspects,
 * making it clear which operations require script execution capabilities.</p>
 */
@Slf4j
@Getter
@Builder
public class ExecutionContext {
    /**
     * The GraalVM engine instance (can be shared across multiple contexts).
     * Not closed by this context - engine lifecycle managed separately.
     */
    private final Engine graalEngine;

    /**
     * The GraalVM context for script execution.
     * MUST be closed to prevent memory leaks.
     */
    private final Context graalContext;

    /**
     * Shared code source available to all mappings (e.g., utility functions).
     */
    private final Source sharedSource;

    /**
     * System code source for internal functionality.
     */
    private final Source systemSource;

    /**
     * Mapping-specific code source.
     */
    private final Source mappingSource;

    /**
     * Evaluated source value from script execution.
     */
    private final Value sourceValue;

    /**
     * Raw shared code as string (before compilation).
     */
    private final String sharedCode;

    /**
     * Raw system code as string (before compilation).
     */
    private final String systemCode;

    /**
     * Data preparation flow context for complex transformations.
     */
    private final DataPrepContext flowContext;

    /**
     * State maintained across flow execution steps.
     */
    private final Map<String, Object> flowState;

    /**
     * Result from flow execution.
     */
    private Object flowResult;

    /**
     * Result from extension execution.
     */
    private Object extensionResult;

    /**
     * The tenant identifier for logging purposes.
     */
    private final String tenant;

    /**
     * Sets the flow result.
     *
     * @param result the result to set
     */
    public void setFlowResult(Object result) {
        this.flowResult = result;
    }

    /**
     * Sets the extension result.
     *
     * @param result the result to set
     */
    public void setExtensionResult(Object result) {
        this.extensionResult = result;
    }

    /**
     * Checks if this context has a valid GraalVM context.
     *
     * @return true if graalContext is present and not closed
     */
    public boolean hasGraalContext() {
        return graalContext != null;
    }

    /**
     * Checks if this context has flow execution capabilities.
     *
     * @return true if flowContext is present
     */
    public boolean hasFlowContext() {
        return flowContext != null;
    }

    /**
     * Checks if shared code is available.
     *
     * @return true if sharedSource or sharedCode is present
     */
    public boolean hasSharedCode() {
        return sharedSource != null || (sharedCode != null && !sharedCode.isEmpty());
    }

    /**
     * Checks if system code is available.
     *
     * @return true if systemSource or systemCode is present
     */
    public boolean hasSystemCode() {
        return systemSource != null || (systemCode != null && !systemCode.isEmpty());
    }

    /**
     * Checks if mapping code is available.
     *
     * @return true if mappingSource is present
     */
    public boolean hasMappingCode() {
        return mappingSource != null;
    }
}
