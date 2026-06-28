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

package dynamic.mapper.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import dynamic.mapper.processor.model.C8YMessage;
import dynamic.mapper.notification.websocket.Notification;

/**
 * Unit tests for {@link NotificationHelper}.
 *
 * <p>L2 fix: {@code String.valueOf(null)} returns the four-character string {@code "null"},
 * not {@code null}. Downstream consumers that check {@code if (deviceName != null)} would
 * silently act on a spurious string. The fix uses explicit null checks for "name" and "id"
 * fields from the parsed payload.
 *
 * <p>Note: {@link Notification#parse(String)} with a header-free message body
 * (no {@code \n} headers) produces a notification whose {@code getMessage()} returns the raw
 * JSON — sufficient for exercising {@code createC8YMessage}'s field-extraction logic.
 */
class NotificationHelperTest {

    // ------------------------------------------------------------------
    // L2 — String.valueOf(null) yields "null" string, not null
    // ------------------------------------------------------------------

    /**
     * L2 fixed: when the payload has no "name" key, deviceName must be null, not "null".
     */
    @Test
    void createC8YMessage_missingName_deviceNameIsNull() {
        Notification notification = Notification.parse("{\"id\":\"42\"}");

        C8YMessage msg = NotificationHelper.createC8YMessage(notification, "t1");

        assertNull(msg.getDeviceName(),
                "L2 fixed: missing 'name' must yield null, not the string \"null\"");
        assertEquals("42", msg.getMessageId(),
                "id field must still be extracted when present");
    }

    /**
     * L2 fixed: when the payload has no "id" key, messageId must be null, not "null".
     */
    @Test
    void createC8YMessage_missingId_messageIdIsNull() {
        Notification notification = Notification.parse("{\"name\":\"myDevice\"}");

        C8YMessage msg = NotificationHelper.createC8YMessage(notification, "t1");

        assertEquals("myDevice", msg.getDeviceName(),
                "name field must still be extracted when present");
        assertNull(msg.getMessageId(),
                "L2 fixed: missing 'id' must yield null, not the string \"null\"");
    }

    /**
     * Both fields absent — both must be null.
     */
    @Test
    void createC8YMessage_bothAbsent_bothNull() {
        Notification notification = Notification.parse("{\"source\":{\"id\":\"99\"}}");

        C8YMessage msg = NotificationHelper.createC8YMessage(notification, "t1");

        assertNull(msg.getDeviceName(), "L2 fixed: absent name must be null");
        assertNull(msg.getMessageId(), "L2 fixed: absent id must be null");
    }

    /**
     * Happy-path: both fields present must be populated correctly.
     */
    @Test
    void createC8YMessage_bothPresent_populatedCorrectly() {
        Notification notification = Notification.parse("{\"name\":\"sensor-1\",\"id\":\"dev-123\"}");

        C8YMessage msg = NotificationHelper.createC8YMessage(notification, "t1");

        assertEquals("sensor-1", msg.getDeviceName());
        assertEquals("dev-123", msg.getMessageId());
    }
}
