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
 * Central registry of the names the Dynamic Mapper uses to register with the
 * Cumulocity <b>Notification 2.0</b> API.
 *
 * <p>
 * Every notification concept the mapper uses comes as a pair:
 * <ul>
 * <li>a <b>{@code *_SUBSCRIPTION}</b> name &mdash; the server-side subscription
 * registered via {@code NotificationSubscriptionApi}. It binds a <i>source</i>
 * (a single managed object, or the whole tenant) and an API filter
 * (INVENTORY, MEASUREMENT, EVENT, ALARM, OPERATION, or ALL) to a stable name so
 * the subscription can later be looked up, reused, and deleted.</li>
 * <li>a <b>{@code *_SUBSCRIBER}</b> name &mdash; the consumer/client identity
 * used to request a token ({@code NotificationTokenApi}) and open the
 * {@code /notification2/consumer} WebSocket that actually delivers the
 * notifications.</li>
 * </ul>
 *
 * <p>
 * The five subscription families below serve distinct purposes. INVENTORY-based
 * ones (management, cache) drive <i>control-plane</i> behaviour &mdash; reacting
 * to inventory changes; the per-device ones (static, dynamic, explorer) feed the
 * <i>data-plane</i> &mdash; forwarding device data outbound (Cumulocity &rarr;
 * broker).
 *
 * <p>
 * They also differ in how many consumer WebSockets are opened:
 * management and cache use <b>one shared WebSocket per tenant</b>; static and
 * dynamic use <b>one WebSocket per connector</b> (so each connector receives its
 * devices' data); explorer uses <b>one WebSocket per UI session</b>.
 *
 * <dl>
 * <dt><b>{@link #MANAGEMENT_SUBSCRIPTION} / {@link #MANAGEMENT_SUBSCRIBER}</b>
 * &mdash; inventory watcher that drives dynamic (re)subscription</dt>
 * <dd>Subscribes to the INVENTORY API. Used in two shapes:
 * <ul>
 * <li><i>tenant</i> context with a <b>type filter</b>
 * ({@code SubscriptionManager.createTypeSubscription}) &mdash; a tenant-wide
 * inventory subscription that fires whenever a managed object of a
 * mapping-selected device <i>type</i> is created/updated.</li>
 * <li><i>mo</i> context on a <b>device group</b> managed object
 * ({@code SubscriptionManager.subscribeByDeviceGroup}) &mdash; watches a group
 * MO for membership changes.</li>
 * </ul>
 * These inventory notifications are consumed by
 * {@code UpdateSubscriptionDeviceTypeTask} / {@code UpdateSubscriptionDeviceGroupTask},
 * which react to devices appearing in / disappearing from the selected
 * type or group and create/remove {@link #DYNAMIC_DEVICE_SUBSCRIPTION}s
 * accordingly. It is not itself an outbound data feed &mdash; it is the trigger
 * that keeps the dynamic device set in sync.</dd>
 *
 * <dt><b>{@link #DYNAMIC_DEVICE_SUBSCRIPTION} / {@link #DYNAMIC_DEVICE_SUBSCRIBER}</b>
 * &mdash; outbound feed for devices selected implicitly (by type or group)</dt>
 * <dd>Per-device (<i>mo</i> context) subscription created automatically for
 * every device that <i>matches</i> a device-type or device-group selection,
 * driven by the {@link #MANAGEMENT_SUBSCRIPTION} watcher above. Delivers the
 * device's data (measurements/events/alarms/operations, per the mapping's API)
 * on the shared dynamic-device WebSocket so it can be mapped outbound. Has
 * higher priority than {@link #STATIC_DEVICE_SUBSCRIPTION}: if a device already
 * has a dynamic subscription, an overlapping static one is skipped/removed
 * (see {@code SubscriptionManager.checkAndHandleDeduplication}).</dd>
 *
 * <dt><b>{@link #STATIC_DEVICE_SUBSCRIPTION} / {@link #STATIC_DEVICE_SUBSCRIBER}</b>
 * &mdash; outbound feed for devices selected explicitly (one by one)</dt>
 * <dd>Per-device (<i>mo</i> context) subscription created when a specific device
 * is subscribed individually (the default {@code subscription} value on the
 * {@code NotificationSubscriptionController} endpoints). Same outbound
 * data-plane role as the dynamic one, but its membership is fixed rather than
 * derived from a type/group filter. Lowest priority in deduplication.</dd>
 *
 * <dt><b>{@link #EXPLORER_DEVICE_SUBSCRIPTION} / {@link #EXPLORER_DEVICE_SUBSCRIBER}</b>
 * &mdash; short-lived live feed for the UI outbound "Explorer"</dt>
 * <dd>Per-device (<i>mo</i> context) subscription on the <b>ALL</b> API, created
 * by {@code ExplorerService} for an interactive explorer session so a user can
 * watch a device's (or all devices of a type's) notifications live &mdash;
 * independent of, and without requiring, any configured outbound mapping. Each
 * session opens its own subscriber WebSocket keyed by session id
 * ({@code NotificationConnectionManager.initializeExplorerDeviceClient}) and is
 * torn down when the session ends. Diagnostic/preview only, not a persistent
 * data flow.</dd>
 *
 * <dt><b>{@link #CACHE_INVENTORY_SUBSCRIPTION} / {@link #CACHE_INVENTORY_SUBSCRIBER}</b>
 * &mdash; keeps the local inventory cache fresh</dt>
 * <dd>Per-device (<i>mo</i> context) subscription on the INVENTORY API
 * ({@code SubscriptionManager.subscribeMOForInventoryCacheUpdates}). Its
 * notifications are consumed by {@code CacheInventoryUpdateClient} to invalidate
 * / refresh the mapper's in-memory managed-object cache (used e.g. for inventory
 * substitutions and identity lookups) whenever a cached device changes in
 * Cumulocity. Purely a cache-coherency mechanism; it carries no mapped device
 * telemetry.</dd>
 * </dl>
 */

public class Utils {
    public static final String WEBSOCKET_PATH = "/notification2/consumer/?token=";

    /**
     * Outbound feed for individually/explicitly selected devices (data-plane,
     * mo context). See the class Javadoc.
     */
    public static final String STATIC_DEVICE_SUBSCRIBER = "DynamicMapperStaticDeviceSubscriber";
    public static final String STATIC_DEVICE_SUBSCRIPTION = "DynamicMapperStaticDeviceSubscription";

    /**
     * Outbound feed for devices selected implicitly via device type or group;
     * created/removed automatically by the management watcher (data-plane, mo
     * context). Higher dedup priority than static. See the class Javadoc.
     */
    public static final String DYNAMIC_DEVICE_SUBSCRIBER = "DynamicMapperDynamicDeviceSubscriber";
    public static final String DYNAMIC_DEVICE_SUBSCRIPTION = "DynamicMapperDynamicDeviceSubscription";

    /**
     * INVENTORY watcher (tenant type-filter and/or group MO) that triggers
     * dynamic (re)subscription of matching devices. Control-plane, not a data
     * feed. See the class Javadoc.
     */
    public static final String MANAGEMENT_SUBSCRIBER = "DynamicMapperManagementSubscriber";
    public static final String MANAGEMENT_SUBSCRIPTION = "DynamicMapperManagementSubscription";

    /**
     * INVENTORY subscription used to keep the local managed-object cache
     * coherent; consumed by CacheInventoryUpdateClient. Carries no mapped
     * telemetry (mo context). See the class Javadoc.
     */
    public static final String CACHE_INVENTORY_SUBSCRIBER = "DynamicMapperCacheInventorySubscriber";
    public static final String CACHE_INVENTORY_SUBSCRIPTION = "DynamicMapperCacheInventorySubscription";

    /**
     * Short-lived ALL-API subscription backing an interactive outbound Explorer
     * session in the UI; independent of configured mappings and torn down with
     * the session (mo context). See the class Javadoc.
     */
    public static final String EXPLORER_DEVICE_SUBSCRIBER = "DynamicMapperExplorerDeviceSubscriber";
    public static final String EXPLORER_DEVICE_SUBSCRIPTION = "DynamicMapperExplorerDeviceSubscription";
    public static final int CONNECTION_TIMEOUT_SECONDS = 30;
    public static final int RECONNECT_INTERVAL_SECONDS = 60;
    public static final int CONFLICT_RETRY_COUNT = 5;
    public static final int CONFLICT_RETRY_DELAY_SECONDS = 60;

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
