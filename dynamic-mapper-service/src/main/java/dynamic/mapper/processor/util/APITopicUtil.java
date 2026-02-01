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
 * Utility class for bidirectional conversion between Cumulocity API types and their various string representations.
 * Handles topics, REST paths, resource names, and simple type names.
 */
public class APITopicUtil {

    private APITopicUtil() {
        // Utility class - prevent instantiation
    }

    /**
     * Convert API enum to its Cumulocity resource name (notification filter format).
     * This is the reverse operation of deriveAPIFromTopic.
     *
     * Examples:
     * - API.MEASUREMENT → "measurements"
     * - API.EVENT → "events"
     * - API.EVENT_WITH_CHILDREN → "eventsWithChildren"
     * - API.ALARM → "alarms"
     * - API.INVENTORY → "managedobjects"
     * - API.OPERATION → "operations"
     *
     * @param api The API enum to convert
     * @return The resource name, or "events" as default for unknown types
     */
    public static String convertAPIToResource(API api) {
        if (api == null) {
            return "events";
        }

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

    /**
     * Derive API type from MQTT-style topic, REST path-style topic, or simple resource/type name.
     * Handles multiple formats:
     * - Simple type names: "measurement", "event", "alarm", "inventory", "operation" → corresponding API
     * - MQTT topics: "measurements/9877263" → MEASUREMENT
     * - REST path format: "measurement/measurements/9877263" → MEASUREMENT
     * - WithChildren variants: "eventsWithChildren", "alarmsWithChildren" → EVENT_WITH_CHILDREN, ALARM_WITH_CHILDREN
     * - REST paths: "inventory/managedObjects/9877263" → INVENTORY
     * - Alternative names: "managedobjects", "managedobject" → INVENTORY
     *
     * This method replaces the need for separate conversion methods by handling all input formats.
     *
     * @param topicOrType The topic string, REST path, or simple type name to parse
     * @return The derived API type, or null if it cannot be determined
     */
    public static API deriveAPIFromTopic(String topicOrType) {
        if (topicOrType == null || topicOrType.isEmpty()) {
            return null;
        }

        String[] segments = topicOrType.split("/");
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
     * Helper method to derive API from a single segment.
     * Handles various naming conventions:
     * - Simple type names: "measurement", "event", "alarm", "inventory", "operation"
     * - Plural resource names: "measurements", "events", "alarms", "operations"
     * - Alternative names: "managedobject", "managedobjects"
     * - WithChildren variants: "eventswithchildren", "alarmswithchildren"
     * - REST path prefixes: "devicecontrol"
     *
     * @param segment The topic segment to parse (case-insensitive)
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
            case "managedobject":
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
