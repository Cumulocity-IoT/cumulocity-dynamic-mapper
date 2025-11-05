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

package dynamic.mapper.notification.websocket;

import java.net.URI;

import dynamic.mapper.model.Mapping;
import dynamic.mapper.processor.model.ProcessingResultWrapper;

/**
 * Implement this interface to handle notifications.
 */
public interface NotificationCallback {

    /**
     * Called when a connection to the WebSocket server is successfully established.
     * 
     * @param serverUri the WebSocket URI that was successfully connected to.
     */
    void onOpen(URI serverUri);

    /**
     * Called on receiving a notification. The notification will be acknowledged if
     * no exception raised.
     * 
     * @param notification the notification received.
     */
    ProcessingResultWrapper<?> onNotification(Notification notification);

    /**
     * Called on receiving a notification and testing this with the provided
     * mapping. The notification will be acknowledged if
     * no exception raised.
     * 
     * @param notification the notification received.
     * @param mapping      the mapping to test.
     */
    ProcessingResultWrapper<?> onTestNotification(Notification notification, Mapping mapping);

    /**
     * Called on receiving an exception from the WebSocket connection. This may be
     * whilst actively connected or during connection/disconnection.
     * 
     * @param t the exception thrown from the connection.
     */
    void onError(Throwable t);

    /**
     * Called on close of the underlying WebSocket connection. Normally, a
     * reconnection should be attempted.
     */
    void onClose(int statusCode, String reason);

}
