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

package dynamic.mapper.util;

import java.util.Optional;
import java.util.Set;

import com.cumulocity.sdk.client.SDKException;

/**
 * Classifies Cumulocity SDK errors as transient platform-side failures (e.g. a 502/503/504
 * during a platform rollout/maintenance window) versus genuine configuration/permission problems
 * that retrying won't fix. Shared by {@code AConnectorClient}'s subscription-init retry and
 * {@code MappingStatusService}'s periodic inventory status push, both of which see the same
 * kind of outage and should treat it the same way: log it quietly and try again, rather than
 * as an unexpected error.
 */
public final class CumulocityErrors {

    private static final Set<Integer> RETRYABLE_HTTP_STATUS_CODES = Set.of(502, 503, 504);

    private CumulocityErrors() {
    }

    /**
     * Walks the cause chain of {@code t} looking for an {@link SDKException} whose HTTP status
     * is one of the transient platform-side codes (502/503/504).
     */
    public static Optional<SDKException> findTransientPlatformError(Throwable t) {
        Throwable current = t;
        while (current != null) {
            if (current instanceof SDKException sdkException
                    && RETRYABLE_HTTP_STATUS_CODES.contains(sdkException.getHttpStatus())) {
                return Optional.of(sdkException);
            }
            current = current.getCause();
        }
        return Optional.empty();
    }

    public static boolean isTransientPlatformError(Throwable t) {
        return findTransientPlatformError(t).isPresent();
    }

    /**
     * The first line of {@code message}, for logging a compact summary of an {@link SDKException}
     * whose message embeds the raw (often multi-line) HTTP error body.
     */
    public static String firstLine(String message) {
        if (message == null) {
            return "";
        }
        int newlineIndex = message.indexOf('\n');
        return newlineIndex >= 0 ? message.substring(0, newlineIndex) : message;
    }
}
