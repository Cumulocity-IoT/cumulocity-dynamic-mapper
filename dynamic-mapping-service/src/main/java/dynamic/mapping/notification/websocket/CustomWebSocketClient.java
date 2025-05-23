/*
 * Copyright (c) 2025 Cumulocity GmbH.
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

package dynamic.mapping.notification.websocket;

import lombok.extern.slf4j.Slf4j;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;


@Slf4j
public class CustomWebSocketClient extends WebSocketClient {
    private final NotificationCallback callback;
    private ScheduledExecutorService executorService = null;
    private String tenant;

    public CustomWebSocketClient(URI serverUri, NotificationCallback callback, String tenant) {
        super(serverUri);
        this.callback = callback;
        this.tenant = tenant;
    }

    @Override
    public void onOpen(ServerHandshake serverHandshake) {
        this.executorService = Executors.newScheduledThreadPool(1);
        this.callback.onOpen(this.uri);
        //send(ByteBuffer.allocate(0));
        executorService.scheduleAtFixedRate(this::sendPing, 1, 1, TimeUnit.MINUTES);
    }

    @Override
    public void onMessage(String message) {
        Notification notification = Notification.parse(message);
        this.callback.onNotification(notification);
        if (notification.getAckHeader() != null) {
            send(notification.getAckHeader()); // ack message
        } else {
            throw new RuntimeException("No message id found for ack");
        }
    }

    @Override
    public void onClose(int statusCode, String reason, boolean remote) {
        log.info("Tenant {} - WebSocket closed {} statusCode: {}, reason: {}", tenant, remote ? "by server." : "", statusCode, reason);
        if (this.executorService != null)
            this.executorService.shutdownNow();
        this.callback.onClose(statusCode, reason);
    }

    @Override
    public void onError(Exception e) {
        log.error("Tenant {} - WebSocket error: ", tenant, e);
        this.callback.onError(e);
    }
}
