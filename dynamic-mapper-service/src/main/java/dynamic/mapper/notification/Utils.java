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

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Contains names for device and management subscriptions.
 */

public class Utils {
    public static final String WEBSOCKET_PATH = "/notification2/consumer/?token=";
    public static final String STATIC_DEVICE_SUBSCRIBER = "DynamicMapperStaticDeviceSubscriber";
    public static final String STATIC_DEVICE_SUBSCRIPTION = "DynamicMapperStaticDeviceSubscription";
    public static final String DYNAMIC_DEVICE_SUBSCRIBER = "DynamicMapperDynamicDeviceSubscriber";
    public static final String DYNAMIC_DEVICE_SUBSCRIPTION = "DynamicMapperDynamicDeviceSubscription";
    public static final String MANAGEMENT_SUBSCRIBER = "DynamicMapperManagementSubscriber";
    public static final String MANAGEMENT_SUBSCRIPTION = "DynamicMapperManagementSubscription";
    public static final String CACHE_INVENTORY_SUBSCRIBER = "DynamicMapperCacheInventorySubscriber";
    public static final String CACHE_INVENTORY_SUBSCRIPTION = "DynamicMapperCacheInventorySubscription";
    public static final int CONNECTION_TIMEOUT_SECONDS = 30;
    public static final int RECONNECT_INTERVAL_SECONDS = 60;

    /**
     * Creates a changed type filter from the new types and return null if types
     * from existingTypeFilter and new types are the same.
     * 
     * @param newTypes           List of new type names (e.g., ["firstType",
     *                           "secondType", "thirdType"])
     * @param existingTypeFilter Existing filter string in the form "'type1' or
     *                           'type2' or 'type3'"
     * @return Combined filter string with all unique types joined by " or ", or
     *         null if no changes detected
     */
    public static String createChangedTypeFilter(List<String> newTypes, String existingTypeFilter) {
        if (newTypes == null) {
            newTypes = List.of();
        }

        // Extract existing types from the filter string
        Set<String> existingTypes = Utils.parseTypesFromFilter(existingTypeFilter);

        // Create a set of all new types for comparison
        Set<String> newTypesSet = new HashSet<>(newTypes);

        // If both sets contain the same types, return null (no change)
        if (existingTypes.equals(newTypesSet)) {
            return null;
        }

        // Create the new filter string by joining all types with " or "
        return newTypesSet.stream()
                .map(type -> "'" + type + "'")
                .collect(Collectors.joining(" or "));
    }

    /**
     * Parses type names from a filter string like "'type1' or 'type2' or 'type3'".
     * 
     * @param filterString The filter string to parse
     * @return Set of extracted type names (without quotes)
     */
    public static Set<String> parseTypesFromFilter(String filterString) {
        Set<String> types = new HashSet<>();

        if (filterString == null || filterString.trim().isEmpty()) {
            return types;
        }

        // Pattern to match quoted strings like 'type1', 'type2', etc.
        Pattern pattern = Pattern.compile("'([^']*)'");
        Matcher matcher = pattern.matcher(filterString);

        while (matcher.find()) {
            String type = matcher.group(1); // Get the content inside the quotes
            if (!type.trim().isEmpty()) {
                types.add(type);
            }
        }

        return types;
    }
}
