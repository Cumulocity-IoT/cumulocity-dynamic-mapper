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

package dynamic.mapper.processor.extension;

/**
 * Utility class for formatting exceptions in processor extensions with detailed location information.
 *
 * <p>This utility helps developers debug processor extensions by providing detailed error messages
 * that include the exact location (file and line number) where exceptions occur.</p>
 *
 * <p>Example usage:</p>
 * <pre>
 * {@code
 * try {
 *     // Extension code
 * } catch (Exception e) {
 *     String errorMsg = ExtensionExceptionUtil.formatExceptionWithLocation(
 *         "Failed to process custom measurement", e
 *     );
 *     log.error("{} - {}", context.getTenant(), errorMsg, e);
 *     context.addWarning(errorMsg);
 * }
 * }
 * </pre>
 */
public class ExtensionExceptionUtil {

    private ExtensionExceptionUtil() {
        // Utility class - prevent instantiation
    }

    /**
     * Formats an exception message with the location (file and line number) where it occurred.
     *
     * <p>This method extracts the first stack trace element from the exception that belongs to
     * a processor extension class (not framework code) and includes the file name and line number
     * in the formatted message.</p>
     *
     * <p>Example output:</p>
     * <pre>
     * Failed to process custom measurement at ProcessorExtensionCustomMeasurement.java:75: Cannot invoke "Object.toString()" because the return value of "java.util.Map.get(Object)" is null
     * </pre>
     *
     * @param prefix The prefix message (e.g., "Failed to process custom measurement")
     * @param e The exception that was thrown
     * @return A formatted error message including the location and exception details
     */
    public static String formatExceptionWithLocation(String prefix, Exception e) {
        StringBuilder message = new StringBuilder(prefix);

        // Find the first stack trace element from extension code (not framework)
        StackTraceElement location = findExtensionStackTraceElement(e);

        if (location != null) {
            message.append(" at ")
                   .append(location.getFileName())
                   .append(":")
                   .append(location.getLineNumber());
        }

        // Append the exception message
        if (e.getMessage() != null) {
            message.append(": ").append(e.getMessage());
        }

        return message.toString();
    }

    /**
     * Formats an exception message with detailed stack trace information.
     *
     * <p>This method provides more detailed information by including the class name and method
     * in addition to the file and line number.</p>
     *
     * <p>Example output:</p>
     * <pre>
     * Failed to process custom measurement at ProcessorExtensionCustomMeasurement.onMessage(ProcessorExtensionCustomMeasurement.java:75): Cannot invoke "Object.toString()" because the return value of "java.util.Map.get(Object)" is null
     * </pre>
     *
     * @param prefix The prefix message
     * @param e The exception that was thrown
     * @return A formatted error message with detailed location information
     */
    public static String formatExceptionWithDetailedLocation(String prefix, Exception e) {
        StringBuilder message = new StringBuilder(prefix);

        StackTraceElement location = findExtensionStackTraceElement(e);

        if (location != null) {
            message.append(" at ")
                   .append(location.getClassName().substring(location.getClassName().lastIndexOf('.') + 1))
                   .append(".")
                   .append(location.getMethodName())
                   .append("(")
                   .append(location.getFileName())
                   .append(":")
                   .append(location.getLineNumber())
                   .append(")");
        }

        if (e.getMessage() != null) {
            message.append(": ").append(e.getMessage());
        }

        return message.toString();
    }

    /**
     * Finds the first stack trace element that belongs to extension code.
     *
     * <p>This method looks for stack trace elements from:</p>
     * <ul>
     *   <li>dynamic.mapper.processor.extension.external.*</li>
     *   <li>dynamic.mapper.processor.extension.internal.*</li>
     * </ul>
     *
     * <p>Framework code and Java standard library code are excluded.</p>
     *
     * @param e The exception
     * @return The first extension stack trace element, or null if none found
     */
    private static StackTraceElement findExtensionStackTraceElement(Exception e) {
        if (e.getStackTrace() == null || e.getStackTrace().length == 0) {
            return null;
        }

        for (StackTraceElement element : e.getStackTrace()) {
            String className = element.getClassName();

            // Look for extension code (not framework code)
            if (className.startsWith("dynamic.mapper.processor.extension.external") ||
                className.startsWith("dynamic.mapper.processor.extension.internal")) {
                return element;
            }
        }

        // Fallback to first element if no extension code found
        return e.getStackTrace()[0];
    }
}
