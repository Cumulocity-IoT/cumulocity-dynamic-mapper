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

package dynamic.mapper.connector.core.client;

import java.util.function.Supplier;

import lombok.extern.slf4j.Slf4j;

/**
 * Tracks whether a connect or a disconnect of one connector client is in progress, and whether the last
 * disconnect was requested by us. Connector subclasses use it through {@code beginConnection()},
 * {@code endConnection()}, {@code beginDisconnection()} and {@code endDisconnection()} on
 * {@link AConnectorClient}.
 * <p>
 * It answers two questions the subclasses need:
 * <ul>
 * <li><b>Is another connect (or disconnect) already running?</b> A second {@code begin} call returns
 * {@code false}, so the caller returns without doing the work twice.</li>
 * <li><b>Was this disconnect our own?</b> {@link #isDisconnectIntentional()} lets a connection-lost
 * listener tell a requested disconnect from an unexpected drop, so it does not report or reconnect for
 * the first.</li>
 * </ul>
 * All state changes and reads share one lock, so the flags are always read as a consistent set.
 * This guard does not exclude a connect body from a disconnect body; {@link ConnectorLifecycleExecutor}
 * does that for tasks started through {@code submitConnect()} and {@code submitDisconnect()}.
 */
@Slf4j
final class ConnectionGuard {

    private final Supplier<String> tenant;

    private boolean connecting;
    private boolean disconnecting;
    private boolean intentionalDisconnect;

    /** @param tenant supplies the tenant for log lines; it is assigned after construction of the client */
    ConnectionGuard(Supplier<String> tenant) {
        this.tenant = tenant;
    }

    /** @return {@code true} if the caller may connect now; also clears the intentional-disconnect mark */
    synchronized boolean beginConnection() {
        if (connecting) {
            log.debug("{} - Connection already in progress", tenant.get());
            return false;
        }
        connecting = true;
        intentionalDisconnect = false;
        return true;
    }

    synchronized void endConnection() {
        log.debug("{} - Connection phase ended", tenant.get());
        connecting = false;
    }

    /** @return {@code true} if the caller may disconnect now; also marks the disconnect as intentional */
    synchronized boolean beginDisconnection() {
        if (disconnecting) {
            log.debug("{} - Disconnection already in progress", tenant.get());
            return false;
        }
        disconnecting = true;
        intentionalDisconnect = true;
        return true;
    }

    synchronized void endDisconnection() {
        log.debug("{} - Disconnection phase ended", tenant.get());
        disconnecting = false;
    }

    synchronized boolean isConnecting() {
        return connecting;
    }

    synchronized boolean isDisconnecting() {
        return disconnecting;
    }

    /** True while we disconnect, and after our last disconnect until the next connect begins. */
    synchronized boolean isDisconnectIntentional() {
        return intentionalDisconnect || disconnecting;
    }
}
