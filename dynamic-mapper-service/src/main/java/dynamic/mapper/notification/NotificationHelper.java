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

import static com.dashjoin.jsonata.Jsonata.jsonata;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.dashjoin.jsonata.json.Json;

import dynamic.mapper.model.API;
import dynamic.mapper.notification.websocket.Notification;
import dynamic.mapper.processor.model.C8YMessage;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class NotificationHelper {

    private NotificationHelper() {
        // Utility class
    }

    public static String extractTenant(List<String> notificationHeaders, String fallback) {
        if (notificationHeaders == null || notificationHeaders.isEmpty()) {
            log.warn("No notification headers provided");
            return fallback;
        }
        
        try {
            return notificationHeaders.get(0).split("/")[1];
        } catch (Exception e) {
            log.warn("Failed to extract tenant from headers: {}", e.getMessage());
            return fallback;
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parsePayload(String payload) {
        try {
            return (Map<String, Object>) Json.parseJson(payload);
        } catch (Exception e) {
            log.warn("Failed to parse notification payload: {}", e.getMessage());
            return new HashMap<>();
        }
    }

    public static String extractSourceId(Map<String, Object> parsedPayload, API api) {
        try {
            var expression = jsonata(api.identifier);
            Object result = expression.evaluate(parsedPayload);
            return result instanceof String ? (String) result : null;
        } catch (Exception e) {
            log.warn("Could not extract source.id from payload: {}", e.getMessage());
            return null;
        }
    }

    public static C8YMessage createC8YMessage(Notification notification, String tenant) {
        C8YMessage message = new C8YMessage();
        Map<String, Object> parsedPayload = parsePayload(notification.getMessage());
        
        message.setParsedPayload(parsedPayload);
        message.setApi(notification.getApi());
        message.setDeviceName(String.valueOf(parsedPayload.get("name")));
        message.setOperation(notification.getOperation());
        message.setMessageId(String.valueOf(parsedPayload.get("id")));
        message.setSourceId(extractSourceId(parsedPayload, notification.getApi()));
        message.setPayload(notification.getMessage());
        message.setTenant(tenant);
        message.setSendPayload(true);
        
        return message;
    }
}
