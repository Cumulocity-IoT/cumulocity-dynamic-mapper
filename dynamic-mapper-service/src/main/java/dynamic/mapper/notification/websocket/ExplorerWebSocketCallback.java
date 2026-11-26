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

package dynamic.mapper.notification.websocket;

import java.net.URI;
import java.util.function.Consumer;

import dynamic.mapper.model.Mapping;
import dynamic.mapper.processor.runtime.ProcessingResultWrapper;
import lombok.extern.slf4j.Slf4j;

/**
 * Callback of the WebSocket that belongs to one Message Explorer session. Notifications go to the
 * delegate (an outbound dispatcher), but the connection lifecycle does not: the delegate's open, close and
 * error handlers update the status of the real connector it belongs to, and an explorer session closing
 * (or failing to authenticate) must not change that status. Instead the lifecycle is reported to the
 * session, so the user sees it.
 */
@Slf4j
public class ExplorerWebSocketCallback implements NotificationCallback {

    private final String tenant;
    private final String sessionId;
    private final NotificationCallback delegate;
    /** Receives a problem description when the connection is lost, and {@code null} once it is open again. */
    private final Consumer<String> connectionIssue;

    public ExplorerWebSocketCallback(String tenant, String sessionId, NotificationCallback delegate,
            Consumer<String> connectionIssue) {
        this.tenant = tenant;
        this.sessionId = sessionId;
        this.delegate = delegate;
        this.connectionIssue = connectionIssue;
    }

    @Override
    public void onOpen(URI serverUri) {
        log.info("{} - Explorer WebSocket open for session {}", tenant, sessionId);
        report(null);
    }

    @Override
    public ProcessingResultWrapper<?> onNotification(Notification notification) {
        return delegate.onNotification(notification);
    }

    @Override
    public ProcessingResultWrapper<?> onTestNotification(Notification notification, Mapping mapping, boolean send) {
        return delegate.onTestNotification(notification, mapping, send);
    }

    @Override
    public void onError(Throwable t) {
        log.warn("{} - Explorer WebSocket error for session {}: {}", tenant, sessionId, t.getMessage());
    }

    @Override
    public void onClose(int statusCode, String reason) {
        log.info("{} - Explorer WebSocket closed for session {}, statusCode: {}, reason: {}", tenant, sessionId,
                statusCode, reason);
        report(describeClose(statusCode, reason));
    }

    static String describeClose(int statusCode, String reason) {
        String why = reason != null && !reason.isBlank() ? reason : "status code " + statusCode;
        return "The Notification 2.0 WebSocket of this session was closed (" + why
                + "). Reconnecting automatically; messages are missed until it is open again.";
    }

    private void report(String issue) {
        try {
            connectionIssue.accept(issue);
        } catch (RuntimeException e) {
            log.debug("{} - Could not report explorer WebSocket state for session {}: {}", tenant, sessionId,
                    e.getMessage());
        }
    }
}
