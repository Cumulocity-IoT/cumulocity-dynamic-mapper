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

import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import dynamic.mapper.model.API;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
@Getter
public class Notification {
    private final String ackHeader;
    private final List<String> notificationHeaders;
    private final String message;
    private final API api;
    private final String operation;

    public static Notification parse(String message) {
        ArrayList<String> headers = new ArrayList<>(8);
        while (true) {
            int i = message.indexOf('\n');
            if (i == -1) {
                break;
            }
            String header = message.substring(0, i);
            message = message.substring(i + 1);
            if (header.length() == 0) {
                break;
            }
            headers.add(header);
        }
        if (headers.isEmpty()) {
            return new Notification(null, Collections.emptyList(), message, API.EMPTY, null);
        }
        String apiString = headers.get(1).split("/")[2];
        String operation = headers.get(2);
        API api = API.EMPTY;
        switch (apiString) {
            case "alarms":
                api = API.ALARM;
                break;
            case "events":
                api = API.EVENT;
                break;
            case "eventsWithChildren":
                api = API.EVENT_WITH_CHILDREN;
                break;
            case "alarmsWithChildren":
                api = API.ALARM_WITH_CHILDREN;
                break;
            case "measurements":
                api = API.MEASUREMENT;
                break;
            case "managedobjects":
                api = API.INVENTORY;
                break;
            case "operations":
                api = API.OPERATION;
                break;
            default:
                break;
        }
        return new Notification(headers.get(0), Collections.unmodifiableList(headers.subList(1, headers.size())),
                message, api, operation);
    }

    public String getTenantFromNotificationHeaders() {
        return notificationHeaders.get(0).split("/")[0];
    }

    public static String getApiPath(API api) {
        switch (api) {
            case ALARM:
                return "alarms";
            case ALARM_WITH_CHILDREN:
                return "alarmsWithChildren";
            case EVENT:
                return "events";
            case EVENT_WITH_CHILDREN:
                return "eventsWithChildren";
            case MEASUREMENT:
                return "measurements";
            case INVENTORY:
                return "managedobjects";
            case OPERATION:
                return "operations";
            default:
                return "events";
        }
    }

}
