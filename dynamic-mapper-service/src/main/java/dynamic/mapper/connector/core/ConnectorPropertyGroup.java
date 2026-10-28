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

package dynamic.mapper.connector.core;

/**
 * Display groups for connector properties. The connector form shows one legend per group. The groups appear in
 * the order below (the UI keeps the same list in {@code CONNECTOR_PROPERTY_GROUP_ORDER}), properties inside a group
 * are sorted by {@link ConnectorProperty#order}.
 */
public final class ConnectorPropertyGroup {

    /** Host, port, URL, protocol: where the broker or endpoint is. */
    public static final String CONNECTION = "Connection";
    /** TLS and server certificate trust. */
    public static final String TLS = "TLS";
    /** User, password, token, client credentials. */
    public static final String AUTHENTICATION = "Authentication";
    /** Where the data lives on the broker: tenant, namespace, topic type, project, topic. */
    public static final String TOPICS = "Topics";
    /** Subscription, consumer group, queue, and exchange settings. */
    public static final String SUBSCRIPTION = "Subscription";
    /** Client identity and session behavior. */
    public static final String SESSION = "Session";
    /** HTTP request settings: headers, accepted content type, health endpoint. */
    public static final String REQUEST = "Request";
    /** Polling interval and cursor handling. */
    public static final String POLLING = "Polling";
    /** Pagination of polled responses. */
    public static final String PAGINATION = "Pagination";
    /** Connection, operation, and publish timeouts. */
    public static final String TIMEOUTS = "Timeouts";
    /** Reconnect and recovery behavior. */
    public static final String RELIABILITY = "Reliability";
    /** Sparkplug host support. */
    public static final String SPARKPLUG = "Sparkplug";
    /** Everything else, including read-only capability flags and raw client properties. */
    public static final String ADVANCED = "Advanced";

    private ConnectorPropertyGroup() {
    }
}
