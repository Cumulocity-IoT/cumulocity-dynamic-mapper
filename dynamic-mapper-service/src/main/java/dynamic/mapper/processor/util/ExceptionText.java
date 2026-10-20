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

/**
 * What to show for an exception in a log or error message. Many exceptions have no message
 * ({@code ConcurrentModificationException}, {@code NullPointerException} without helpful NPE text), so a message built
 * from {@code getMessage()} alone ends in "null".
 */
public final class ExceptionText {

    private ExceptionText() {
    }

    /** The exception's message, else its cause's, else its class name. */
    public static String of(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            String message = t.getMessage();
            if (message != null && !message.isBlank()) {
                return message;
            }
        }
        return e == null ? "unknown error" : e.getClass().getName();
    }
}
