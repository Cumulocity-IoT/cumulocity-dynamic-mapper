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

package dynamic.mapper.processor.util;

import dynamic.mapper.model.API;

/**
 * Utility class for deriving Cumulocity API types from MQTT-style or REST-style topics.
 */
public class APITopicUtil {

    private APITopicUtil() {
        // Utility class - prevent instantiation
    }

    /**
     * Derive API type from MQTT-style topic or REST path-style topic.
     * Handles both simple MQTT topics and REST path-style topics:
     * - "measurements/9877263" → MEASUREMENT
     * - "measurement/measurements/9877263" → MEASUREMENT (REST path format)
     * - "events/9877263" → EVENT
     * - "event/events/9877263" → EVENT (REST path format)
     * - "eventsWithChildren/9877263" → EVENT_WITH_CHILDREN
     * - "alarms/9877263" → ALARM
     * - "alarm/alarms/9877263" → ALARM (REST path format)
     * - "alarmsWithChildren/9877263" → ALARM_WITH_CHILDREN
     * - "inventory/managedObjects/9877263" → INVENTORY
     * - "managedobjects/9877263" → INVENTORY
     * - "operations/9877263" → OPERATION
     * - "devicecontrol/operations/9877263" → OPERATION (REST path format)
     *
     * @param topic The topic string to parse
     * @return The derived API type, or null if it cannot be determined
     */
    public static API deriveAPIFromTopic(String topic) {
        if (topic == null || topic.isEmpty()) {
            return null;
        }

        String[] segments = topic.split("/");
        if (segments.length == 0) {
            return null;
        }

        // Try first segment, then second segment (for REST path format like "measurement/measurements/...")
        String firstSegment = segments[0].toLowerCase();
        String secondSegment = segments.length > 1 ? segments[1].toLowerCase() : null;

        // Map topic segment to API type
        // Check for exact matches (including withChildren variants and REST paths)
        API result = deriveFromSegment(firstSegment);
        if (result != null) {
            return result;
        }

        if (secondSegment != null) {
            // Try second segment for REST path format
            result = deriveFromSegment(secondSegment);
            if (result != null) {
                return result;
            }
        }

        return null;
    }

    /**
     * Helper method to derive API from a single segment
     *
     * @param segment The topic segment to parse
     * @return The derived API type, or null if not recognized
     */
    private static API deriveFromSegment(String segment) {
        if (segment == null) {
            return null;
        }

        switch (segment) {
            case "measurement":
            case "measurements":
                return API.MEASUREMENT;

            case "event":
            case "events":
                return API.EVENT;
            case "eventswithchildren":
                return API.EVENT_WITH_CHILDREN;

            case "alarm":
            case "alarms":
                return API.ALARM;
            case "alarmswithchildren":
                return API.ALARM_WITH_CHILDREN;

            case "inventory":
            case "managedobjects":
                return API.INVENTORY;

            case "devicecontrol":
            case "operation":
            case "operations":
                return API.OPERATION;

            default:
                return null;
        }
    }
}
