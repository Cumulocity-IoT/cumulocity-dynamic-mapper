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

package dynamic.mapper.connector.httppolling;

import static com.dashjoin.jsonata.Jsonata.jsonata;

import java.net.URI;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import com.dashjoin.jsonata.json.Json;

/**
 * Stateless request/response helpers for {@link HttpPollingConnector}: config validation,
 * query-parameter composition, and pagination/cursor extraction from a poll response. Every
 * method here is a pure function of its arguments — no field on {@link HttpPollingConnector}
 * (scheduling state, failure counters, in-flight guards) is read or touched — which is what
 * keeps this class safe to unit-test directly, without reflection or a partially-wired connector
 * instance, and safe to reason about in isolation from the connector's concurrency-sensitive
 * state. See {@code docs/feature/connector-http-polling.md} for the feature-level behavior these
 * methods implement.
 */
final class HttpPollingRequestHelper {

    /** Default {@code maxPagesPerPoll} when not configured or invalid. */
    static final int DEFAULT_MAX_PAGES_PER_POLL = 20;

    /** Default {@code pageStartValue} for {@code PageNumber} pagination mode. */
    static final long DEFAULT_PAGE_START_VALUE = 1L;

    /** Matches the {@code rel="next"} entry of an RFC 5988 {@code Link} header, e.g.
     * {@code <https://api.example.com/x?page=2>; rel="next"}. */
    private static final Pattern NEXT_LINK_PATTERN = Pattern.compile("<([^>]+)>\\s*;\\s*rel=\"?next\"?");

    private HttpPollingRequestHelper() {
        // static helpers only
    }

    /**
     * Validates the connector-specific properties: {@code url} required; {@code pollIntervalSeconds}
     * (if set) at or above {@code minPollIntervalSeconds}; Basic/Bearer auth credentials complete
     * when selected; {@code cursorParam}/{@code cursorExtractionExpression} either both set or both
     * empty; pagination mode requirements ({@code pageParam} for {@code NextFieldInBody}/
     * {@code PageNumber}, {@code nextPageExpression} additionally for {@code NextFieldInBody}).
     */
    static boolean isConfigValid(Map<String, Object> properties, long minPollIntervalSeconds,
            String tenant, Logger log) {
        if (properties == null) {
            return false;
        }

        String url = (String) properties.get("url");
        if (StringUtils.isEmpty(url)) {
            return false;
        }

        Object pollIntervalRaw = properties.get("pollIntervalSeconds");
        if (pollIntervalRaw != null) {
            try {
                long pollIntervalSeconds = Long.parseLong(pollIntervalRaw.toString());
                if (pollIntervalSeconds < minPollIntervalSeconds) {
                    log.warn("{} - pollIntervalSeconds {} is below the enforced minimum of {}s", tenant,
                            pollIntervalSeconds, minPollIntervalSeconds);
                    return false;
                }
            } catch (NumberFormatException e) {
                log.warn("{} - Invalid pollIntervalSeconds value '{}', ignoring", tenant, pollIntervalRaw);
            }
        }

        String authentication = (String) properties.get("authentication");
        String user = (String) properties.get("user");
        String password = (String) properties.get("password");
        String token = (String) properties.get("token");

        if ("Basic".equalsIgnoreCase(authentication)) {
            if (StringUtils.isEmpty(user) || StringUtils.isEmpty(password)) {
                return false;
            }
        } else if ("Bearer".equalsIgnoreCase(authentication)) {
            if (StringUtils.isEmpty(token)) {
                return false;
            }
        }

        String cursorParam = (String) properties.get("cursorParam");
        String cursorExtractionExpression = (String) properties.get("cursorExtractionExpression");
        if (StringUtils.isEmpty(cursorParam) != StringUtils.isEmpty(cursorExtractionExpression)) {
            log.warn("{} - incremental fetch requires both cursorParam and cursorExtractionExpression", tenant);
            return false;
        }

        String paginationMode = (String) properties.get("paginationMode");
        String pageParam = (String) properties.get("pageParam");
        String nextPageExpression = (String) properties.get("nextPageExpression");
        if ("NextFieldInBody".equals(paginationMode)) {
            if (StringUtils.isEmpty(pageParam) || StringUtils.isEmpty(nextPageExpression)) {
                log.warn("{} - paginationMode NextFieldInBody requires both pageParam and " +
                        "nextPageExpression to be set", tenant);
                return false;
            }
        } else if ("PageNumber".equals(paginationMode)) {
            if (StringUtils.isEmpty(pageParam)) {
                log.warn("{} - paginationMode PageNumber requires pageParam to be set", tenant);
                return false;
            }
        }

        return true;
    }

    /**
     * Normalizes a mapping topic into a URI path segment: exactly one leading {@code /}, no
     * duplicate slashes when joined onto a trailing-slash-stripped base URL. {@code "devices/x"}
     * and {@code "/devices/x"} both become {@code "/devices/x"}.
     */
    static String topicPath(String topic) {
        return topic.startsWith("/") ? topic : "/" + topic;
    }

    /**
     * Runtime counterpart of {@link #isConfigValid}'s pagination checks: without a
     * {@code pageParam} (and, for {@code NextFieldInBody}, a {@code nextPageExpression}), the
     * connector cannot actually tell the server which page to fetch next, so every "page" request
     * would be identical to the first.
     */
    static boolean isPaginationRuntimeConfigValid(String paginationMode, String pageParam,
            String nextPageExpression) {
        if ("None".equals(paginationMode) || "NextLinkHeader".equals(paginationMode)) {
            return true;
        }
        if (StringUtils.isEmpty(pageParam)) {
            return false;
        }
        if ("NextFieldInBody".equals(paginationMode)) {
            return StringUtils.isNotEmpty(nextPageExpression);
        }
        return true;
    }

    /**
     * Composes the query parameters for one page request: the incremental-fetch cursor (if
     * configured, independent of pagination — the two features combine freely) plus, for
     * {@code NextFieldInBody}/{@code PageNumber} modes, the current page token/number under
     * {@code pageParam}. {@code NextLinkHeader} mode contributes nothing here — its continuation
     * is the absolute URI the caller hits directly instead.
     */
    static Map<String, String> buildQueryParams(String cursor, String cursorParam, String paginationMode,
            String pageParam, String pageParamValue) {
        Map<String, String> params = new LinkedHashMap<>();
        if (StringUtils.isNotEmpty(cursorParam) && cursor != null) {
            params.put(cursorParam, cursor);
        }
        if (("NextFieldInBody".equals(paginationMode) || "PageNumber".equals(paginationMode))
                && pageParamValue != null && StringUtils.isNotEmpty(pageParam)) {
            params.put(pageParam, pageParamValue);
        }
        return params;
    }

    /** Parses an RFC 5988 {@code Link} header for the {@code rel="next"} entry. */
    static URI extractNextLinkUri(ResponseEntity<String> response, String tenant, Logger log) {
        List<String> linkHeaders = response.getHeaders().get(HttpHeaders.LINK);
        if (linkHeaders == null) {
            return null;
        }
        for (String headerValue : linkHeaders) {
            for (String part : headerValue.split(",")) {
                Matcher m = NEXT_LINK_PATTERN.matcher(part.trim());
                if (m.find()) {
                    try {
                        return URI.create(m.group(1));
                    } catch (IllegalArgumentException e) {
                        log.warn("{} - Ignoring unparsable next-page Link header value: {}", tenant, m.group(1));
                        return null;
                    }
                }
            }
        }
        return null;
    }

    /** Evaluates {@code nextPageExpression} (JSONata) against the response body. */
    static String extractNextPageToken(String responseBody, String nextPageExpression, String tenant, Logger log) {
        if (StringUtils.isEmpty(nextPageExpression) || StringUtils.isEmpty(responseBody)) {
            return null;
        }
        return evaluateJsonataToString(nextPageExpression, responseBody, tenant, log, "nextPageExpression");
    }

    /** {@code PageNumber} mode's stop condition: an empty JSON array or object body. */
    static boolean isEmptyPage(String responseBody) {
        if (StringUtils.isBlank(responseBody)) {
            return true;
        }
        try {
            Object parsed = Json.parseJson(responseBody);
            if (parsed instanceof Collection<?> collection) {
                return collection.isEmpty();
            }
            if (parsed instanceof Map<?, ?> map) {
                return map.isEmpty();
            }
        } catch (Exception e) {
            // Unparsable body: don't guess — treat as non-empty so pagination halts on the next
            // maxPagesPerPoll cap rather than silently stopping early on a transient parse issue.
        }
        return false;
    }

    /**
     * Evaluates a JSONata {@code expression} against a JSON response body and returns the result
     * as a string, or {@code null} if the body is empty, evaluation fails, or the result is
     * {@code null} — shared by cursor extraction ({@code cursorExtractionExpression}) and
     * next-page-token extraction ({@code nextPageExpression}), which differ only in which
     * property they're evaluating (used purely for the log message on failure).
     */
    static String evaluateJsonataToString(String expression, String responseBody, String tenant, Logger log,
            String propertyNameForLogging) {
        if (StringUtils.isEmpty(expression) || StringUtils.isEmpty(responseBody)) {
            return null;
        }
        try {
            Object parsed = Json.parseJson(responseBody);
            Object extracted = jsonata(expression).evaluate(parsed);
            return extracted != null ? extracted.toString() : null;
        } catch (Exception e) {
            log.warn("{} - Failed to evaluate {} [{}]: {}", tenant, propertyNameForLogging, expression,
                    e.getMessage());
            return null;
        }
    }

    /** Parses {@code maxPagesPerPoll}, falling back to {@link #DEFAULT_MAX_PAGES_PER_POLL} for a
     * missing, non-numeric, or non-positive value. */
    static int getMaxPagesPerPoll(Object rawValue) {
        if (rawValue == null) {
            return DEFAULT_MAX_PAGES_PER_POLL;
        }
        try {
            int parsed = Integer.parseInt(rawValue.toString());
            return parsed > 0 ? parsed : DEFAULT_MAX_PAGES_PER_POLL;
        } catch (NumberFormatException e) {
            return DEFAULT_MAX_PAGES_PER_POLL;
        }
    }

    /** Parses {@code pageStartValue}, falling back to {@link #DEFAULT_PAGE_START_VALUE} for a
     * missing or non-numeric value. */
    static String readPageStartValue(Object rawValue) {
        if (rawValue == null) {
            return String.valueOf(DEFAULT_PAGE_START_VALUE);
        }
        try {
            return String.valueOf(Long.parseLong(rawValue.toString()));
        } catch (NumberFormatException e) {
            return String.valueOf(DEFAULT_PAGE_START_VALUE);
        }
    }
}
