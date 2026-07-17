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

package dynamic.mapper.notification.service;

import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;
import com.cumulocity.model.idtype.GId;
import com.cumulocity.rest.representation.PageStatisticsRepresentation;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.cumulocity.rest.representation.reliable.notification.NotificationSubscriptionRepresentation;
import com.cumulocity.sdk.client.PagedCollectionResource;
import com.cumulocity.sdk.client.QueryParam;
import com.cumulocity.sdk.client.messaging.notifications.NotificationSubscriptionApi;
import com.cumulocity.sdk.client.messaging.notifications.NotificationSubscriptionFilter;
import com.cumulocity.sdk.client.messaging.notifications.PagedNotificationSubscriptionCollectionRepresentation;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.model.API;
import dynamic.mapper.model.Device;
import dynamic.mapper.model.NotificationSubscriptionResponse;
import dynamic.mapper.notification.Utils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Service for querying notification subscriptions.
 * Provides methods to retrieve subscription information for devices, groups, and types.
 */
@Slf4j
@Service
public class SubscriptionQueryService {

    private final NotificationSubscriptionApi subscriptionAPI;
    private final MicroserviceSubscriptionsService subscriptionsService;
    private final ConfigurationRegistry configurationRegistry;
    private final ExecutorService virtualThreadPool;

    public SubscriptionQueryService(NotificationSubscriptionApi subscriptionAPI,
                                     MicroserviceSubscriptionsService subscriptionsService,
                                     @Lazy ConfigurationRegistry configurationRegistry,
                                     @Qualifier("virtualThreadPool") ExecutorService virtualThreadPool) {
        this.subscriptionAPI = subscriptionAPI;
        this.subscriptionsService = subscriptionsService;
        this.configurationRegistry = configurationRegistry;
        this.virtualThreadPool = virtualThreadPool;
    }

    // === Public API ===

    /**
     * Get notification subscriptions for devices
     */
    public Future<List<NotificationSubscriptionRepresentation>> getNotificationSubscriptionForDevices(
            String tenant, String deviceId, String deviceSubscription) {

        NotificationSubscriptionFilter filter = new NotificationSubscriptionFilter();
        filter = filter.bySubscription(deviceSubscription != null ? deviceSubscription : Utils.STATIC_DEVICE_SUBSCRIPTION);

        if (deviceId != null) {
            GId id = new GId();
            id.setValue(deviceId);
            filter = filter.bySource(id);
        }
        filter = filter.byContext("mo");

        NotificationSubscriptionFilter finalFilter = filter;

        return virtualThreadPool.submit(() -> subscriptionsService.callForTenant(tenant, () -> {
            List<NotificationSubscriptionRepresentation> deviceSubList = new ArrayList<>();
            try {
                Iterator<NotificationSubscriptionRepresentation> subIt = subscriptionAPI
                        .getSubscriptionsByFilter(finalFilter).get().allPages().iterator();

                while (subIt.hasNext()) {
                    // L7: byContext("mo") filter already guarantees context; check removed
                    NotificationSubscriptionRepresentation nsr = subIt.next();
                    log.debug("{} - Retrieved device subscription: {}", tenant, nsr.getId().getValue());
                    deviceSubList.add(nsr);
                }
            } catch (Exception e) {
                log.error("{} - Error retrieving device subscriptions: {}", tenant, e.getMessage(), e);
                throw new RuntimeException("Failed to retrieve device subscriptions: " + e.getMessage(), e);
            }
            return deviceSubList;
        }));
    }

    /**
     * Get notification subscriptions for device groups
     */
    public Future<List<NotificationSubscriptionRepresentation>> getNotificationSubscriptionForDeviceGroup(
            String tenant, String deviceId, String deviceSubscription) {

        NotificationSubscriptionFilter filter = new NotificationSubscriptionFilter();
        filter = filter.bySubscription(deviceSubscription != null ? deviceSubscription : Utils.MANAGEMENT_SUBSCRIPTION);

        if (deviceId != null) {
            GId id = new GId();
            id.setValue(deviceId);
            filter = filter.bySource(id);
        }
        filter = filter.byContext("mo");

        NotificationSubscriptionFilter finalFilter = filter;

        return virtualThreadPool.submit(() -> subscriptionsService.callForTenant(tenant, () -> {
            List<NotificationSubscriptionRepresentation> managementSubList = new ArrayList<>();
            try {
                Iterator<NotificationSubscriptionRepresentation> subIt = subscriptionAPI
                        .getSubscriptionsByFilter(finalFilter).get().allPages().iterator();

                while (subIt.hasNext()) {
                    // L7: byContext("mo") filter already guarantees context; check removed
                    NotificationSubscriptionRepresentation nsr = subIt.next();
                    log.debug("{} - Retrieved management subscription: {}", tenant, nsr.getId().getValue());
                    managementSubList.add(nsr);
                }
            } catch (Exception e) {
                log.error("{} - Error retrieving management subscriptions: {}", tenant, e.getMessage(), e);
                throw new RuntimeException("Failed to retrieve management subscriptions: " + e.getMessage(), e);
            }
            return managementSubList;
        }));
    }

    /**
     * Get notification subscription for device type
     */
    public Future<NotificationSubscriptionRepresentation> getNotificationSubscriptionForDeviceType(String tenant) {

        NotificationSubscriptionFilter filter = new NotificationSubscriptionFilter()
                .bySubscription(Utils.MANAGEMENT_SUBSCRIPTION)
                .byContext("tenant");

        return virtualThreadPool.submit(() -> subscriptionsService.callForTenant(tenant, () -> {
            try {
                Iterator<NotificationSubscriptionRepresentation> subIt = subscriptionAPI
                        .getSubscriptionsByFilter(filter).get().allPages().iterator();

                // L7: byContext("tenant") filter already guarantees context; return first match
                if (subIt.hasNext()) {
                    NotificationSubscriptionRepresentation nsr = subIt.next();
                    log.debug("{} - Retrieved type subscription: {}", tenant, nsr.getId().getValue());
                    return nsr;
                }
                return null;
            } catch (Exception e) {
                log.error("{} - Error retrieving type subscriptions: {}", tenant, e.getMessage(), e);
                throw new RuntimeException("Failed to retrieve type subscriptions: " + e.getMessage(), e);
            }
        }));
    }

    /**
     * Get subscription response for devices
     */
    public NotificationSubscriptionResponse getSubscriptionsDevices(String tenant, String deviceId,
            String deviceSubscription) {

        if (tenant == null) {
            throw new IllegalArgumentException("Tenant cannot be null");
        }

        NotificationSubscriptionFilter filter = new NotificationSubscriptionFilter()
                .bySubscription(deviceSubscription != null ? deviceSubscription : Utils.STATIC_DEVICE_SUBSCRIPTION);

        if (deviceId != null) {
            GId id = new GId();
            id.setValue(deviceId);
            filter = filter.bySource(id);
        }
        filter = filter.byContext("mo");

        NotificationSubscriptionFilter finalFilter = filter;
        NotificationSubscriptionResponse.NotificationSubscriptionResponseBuilder responseBuilder = 
                NotificationSubscriptionResponse.builder();
        List<Device> devices = new ArrayList<>();

        subscriptionsService.runForTenant(tenant, () -> {
            try {
                Iterator<NotificationSubscriptionRepresentation> subIt = subscriptionAPI
                        .getSubscriptionsByFilter(finalFilter).get().allPages().iterator();

                while (subIt.hasNext()) {
                    // L7: byContext("mo") filter already guarantees context; check removed
                    processDeviceSubscription(tenant, subIt.next(), devices, responseBuilder);
                }
            } catch (Exception e) {
                log.error("{} - Error getting device subscriptions: {}", tenant, e.getMessage(), e);
            }
        });

        return responseBuilder.devices(devices).build();
    }

    /**
     * Get a single page of device subscriptions for the static/dynamic device tabs.
     */
    public NotificationSubscriptionResponse getSubscriptionsDevices(String tenant, String deviceId,
            String deviceSubscription, int currentPage, int pageSize, boolean withTotalPages) {
        return getSubscriptionsDevices(tenant, deviceId, deviceSubscription, currentPage, pageSize, withTotalPages,
                null);
    }

    /**
     * Get a single page of device subscriptions for the static/dynamic device tabs, optionally
     * restricted to devices whose id/name/type/group matches {@code search} (case-insensitive
     * substring). The notification-subscription API has no name predicate, so a search request
     * resolves every subscribed device (see {@link #searchAndPaginate}) instead of a single page.
     */
    public NotificationSubscriptionResponse getSubscriptionsDevices(String tenant, String deviceId,
            String deviceSubscription, int currentPage, int pageSize, boolean withTotalPages, String search) {

        if (tenant == null) {
            throw new IllegalArgumentException("Tenant cannot be null");
        }

        NotificationSubscriptionFilter filter = new NotificationSubscriptionFilter()
                .bySubscription(deviceSubscription != null ? deviceSubscription : Utils.STATIC_DEVICE_SUBSCRIPTION);

        if (deviceId != null) {
            GId id = new GId();
            id.setValue(deviceId);
            filter = filter.bySource(id);
        }
        filter = filter.byContext("mo");

        if (search != null && !search.isBlank()) {
            return searchAndPaginate(tenant, filter, search, currentPage, pageSize);
        }

        return getSubscriptionsDevicesPaged(tenant, filter, currentPage, pageSize, withTotalPages);
    }

    /**
     * Fetch a single page of "mo"-context device subscriptions for the given filter, enrich each
     * into a Device, and return a paged response. Shared by the device tabs and the group tab.
     */
    private NotificationSubscriptionResponse getSubscriptionsDevicesPaged(String tenant,
            NotificationSubscriptionFilter filter, int currentPage, int pageSize, boolean withTotalPages) {

        int effectivePageSize = pageSize > 0 ? pageSize : 30;
        int requestedPage = Math.max(1, currentPage);

        NotificationSubscriptionFilter finalFilter = filter;
        NotificationSubscriptionResponse.NotificationSubscriptionResponseBuilder responseBuilder =
                NotificationSubscriptionResponse.builder();
        List<Device> devices = new ArrayList<>();
        AtomicReference<PageStatisticsRepresentation> statsRef = new AtomicReference<>();

        subscriptionsService.runForTenant(tenant, () -> {
            try {
                List<QueryParam> params = new ArrayList<>();
                params.add(new QueryParam(() -> PagedCollectionResource.PAGE_NUMBER_KEY,
                        String.valueOf(requestedPage)));
                if (withTotalPages) {
                    params.add(new QueryParam(() -> "withTotalPages", "true"));
                }

                PagedNotificationSubscriptionCollectionRepresentation page = subscriptionAPI
                        .getSubscriptionsByFilter(finalFilter)
                        .get(effectivePageSize, params.toArray(new QueryParam[0]));

                for (NotificationSubscriptionRepresentation nsr : page.getSubscriptions()) {
                    processDeviceSubscription(tenant, nsr, devices, responseBuilder);
                }
                statsRef.set(page.getPageStatistics());
            } catch (Exception e) {
                log.error("{} - Error getting paged device subscriptions: {}", tenant, e.getMessage(), e);
            }
        });

        PageStatisticsRepresentation stats = statsRef.get();
        int effectivePage = stats != null ? stats.getCurrentPage() : requestedPage;
        Integer totalPages = stats != null ? stats.getTotalPages() : null;
        Long totalElements = stats != null ? stats.getTotalElements() : null;

        // If totals are known use them; otherwise infer "more" from a full page.
        boolean hasNext = totalPages != null
                ? effectivePage < totalPages
                : devices.size() >= effectivePageSize;

        return responseBuilder
                .devices(devices)
                .paging(NotificationSubscriptionResponse.Paging.builder()
                        .currentPage(effectivePage)
                        .pageSize(effectivePageSize)
                        .totalPages(totalPages)
                        .totalElements(totalElements)
                        .hasNext(hasNext)
                        .build())
                .build();
    }

    /**
     * Resolves every subscription matching {@code filter} (there is no name/text predicate on the
     * notification-subscription API, so this cannot be pushed down to a single page), keeps the
     * devices whose id/name/type/group matches {@code search}, then slices the requested page out of
     * the filtered result. Mirrors the standard Cumulocity device-list search: plain text is a
     * case-insensitive substring match (no regex needed — "Robot" finds "Multi Robot-001"); {@code *}
     * / {@code ?} glob wildcards are only engaged when the search text actually contains one (see
     * {@link #buildSearchMatcher}).
     */
    private NotificationSubscriptionResponse searchAndPaginate(String tenant, NotificationSubscriptionFilter filter,
            String search, int currentPage, int pageSize) {

        int effectivePageSize = pageSize > 0 ? pageSize : 30;
        int requestedPage = Math.max(1, currentPage);
        Predicate<String> searchMatcher = buildSearchMatcher(search);

        NotificationSubscriptionResponse.NotificationSubscriptionResponseBuilder responseBuilder =
                NotificationSubscriptionResponse.builder();
        List<Device> allDevices = new ArrayList<>();

        subscriptionsService.runForTenant(tenant, () -> {
            try {
                Iterator<NotificationSubscriptionRepresentation> subIt = subscriptionAPI
                        .getSubscriptionsByFilter(filter).get().allPages().iterator();

                while (subIt.hasNext()) {
                    processDeviceSubscription(tenant, subIt.next(), allDevices, responseBuilder);
                }
            } catch (Exception e) {
                log.error("{} - Error searching device subscriptions: {}", tenant, e.getMessage(), e);
            }
        });

        List<Device> matches = allDevices.stream()
                .filter(d -> matchesSearch(d, searchMatcher))
                .collect(Collectors.toList());

        int totalElements = matches.size();
        int fromIndex = Math.min((requestedPage - 1) * effectivePageSize, totalElements);
        int toIndex = Math.min(fromIndex + effectivePageSize, totalElements);
        List<Device> pageDevices = matches.subList(fromIndex, toIndex);

        return responseBuilder
                .devices(pageDevices)
                .paging(NotificationSubscriptionResponse.Paging.builder()
                        .currentPage(requestedPage)
                        .pageSize(effectivePageSize)
                        .totalPages((int) Math.ceil((double) totalElements / effectivePageSize))
                        .totalElements((long) totalElements)
                        .hasNext(toIndex < totalElements)
                        .build())
                .build();
    }

    private boolean matchesSearch(Device device, Predicate<String> searchMatcher) {
        return matches(device.getId(), searchMatcher)
                || matches(device.getName(), searchMatcher)
                || matches(device.getType(), searchMatcher)
                || (device.getGroups() != null
                        && device.getGroups().stream().anyMatch(g -> matches(g, searchMatcher)));
    }

    private boolean matches(String value, Predicate<String> searchMatcher) {
        return value != null && searchMatcher.test(value);
    }

    /**
     * Builds a case-insensitive matcher mirroring the standard Cumulocity device-list search: plain
     * text is a straight substring check (no regex engine involved), while {@code *} (any number of
     * characters) and {@code ?} (a single character) are recognized as glob wildcards only when the
     * search text actually contains one.
     */
    private Predicate<String> buildSearchMatcher(String search) {
        if (search.indexOf('*') < 0 && search.indexOf('?') < 0) {
            String needle = search.toLowerCase();
            return value -> value.toLowerCase().contains(needle);
        }

        StringBuilder regex = new StringBuilder(".*");
        for (int i = 0; i < search.length(); i++) {
            char c = search.charAt(i);
            if (c == '*') {
                regex.append(".*");
            } else if (c == '?') {
                regex.append('.');
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        regex.append(".*");
        Pattern pattern = Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        return value -> pattern.matcher(value).matches();
    }

    /**
     * Get subscriptions by device group
     */
    public NotificationSubscriptionResponse getSubscriptionsByDeviceGroup(String tenant) {
        if (tenant == null) {
            throw new IllegalArgumentException("Tenant cannot be null");
        }

        NotificationSubscriptionFilter filter = new NotificationSubscriptionFilter()
                .bySubscription(Utils.MANAGEMENT_SUBSCRIPTION)
                .byContext("mo");

        NotificationSubscriptionResponse.NotificationSubscriptionResponseBuilder responseBuilder = 
                NotificationSubscriptionResponse.builder();
        List<Device> devices = new ArrayList<>();

        subscriptionsService.runForTenant(tenant, () -> {
            try {
                Iterator<NotificationSubscriptionRepresentation> subIt = subscriptionAPI
                        .getSubscriptionsByFilter(filter).get().allPages().iterator();

                while (subIt.hasNext()) {
                    // L7: byContext("mo") filter already guarantees context; check removed
                    processDeviceSubscription(tenant, subIt.next(), devices, responseBuilder);
                }
            } catch (Exception e) {
                log.error("{} - Error getting group subscriptions: {}", tenant, e.getMessage(), e);
            }
        });

        return responseBuilder.devices(devices).build();
    }

    /**
     * Get a single page of group ("management") device subscriptions for the group tab.
     */
    public NotificationSubscriptionResponse getSubscriptionsByDeviceGroup(String tenant,
            int currentPage, int pageSize, boolean withTotalPages) {

        if (tenant == null) {
            throw new IllegalArgumentException("Tenant cannot be null");
        }

        NotificationSubscriptionFilter filter = new NotificationSubscriptionFilter()
                .bySubscription(Utils.MANAGEMENT_SUBSCRIPTION)
                .byContext("mo");

        return getSubscriptionsDevicesPaged(tenant, filter, currentPage, pageSize, withTotalPages);
    }

    /**
     * Get subscriptions by device type
     */
    public NotificationSubscriptionResponse getSubscriptionsByDeviceType(String tenant) {
        if (tenant == null) {
            throw new IllegalArgumentException("Tenant cannot be null");
        }

        NotificationSubscriptionResponse response = NotificationSubscriptionResponse.builder().build();

        try {
            Future<NotificationSubscriptionRepresentation> future = getNotificationSubscriptionForDeviceType(tenant);
            NotificationSubscriptionRepresentation nsr = future.get(30, TimeUnit.SECONDS);

            if (nsr != null && nsr.getSubscriptionFilter() != null) {
                String filterString = nsr.getSubscriptionFilter().getTypeFilter();
                log.debug("{} - Retrieved type subscription with filter: {}", tenant, filterString);

                if (filterString != null) {
                    List<String> types = new ArrayList<>(dynamic.mapper.notification.Utils.parseTypesFromFilter(filterString));
                    response = NotificationSubscriptionResponse.builder()
                            .types(types)
                            .subscriptionName(nsr.getSubscription())
                            .subscriptionId(nsr.getId().getValue())
                            .status(NotificationSubscriptionResponse.SubscriptionStatus.ACTIVE)
                            .build();
                }
            } else {
                log.info("{} - No type subscription found", tenant);
                response = NotificationSubscriptionResponse.builder()
                        .types(new ArrayList<>())
                        .status(NotificationSubscriptionResponse.SubscriptionStatus.INACTIVE)
                        .build();
            }
        } catch (Exception e) {
            log.error("{} - Error retrieving type subscriptions: {}", tenant, e.getMessage(), e);
            response = NotificationSubscriptionResponse.builder()
                    .types(new ArrayList<>())
                    .status(NotificationSubscriptionResponse.SubscriptionStatus.ERROR)
                    .build();
        }

        return response;
    }

    // === Private Helper Methods ===

    private void processDeviceSubscription(String tenant, NotificationSubscriptionRepresentation nsr,
            List<Device> devices,
            NotificationSubscriptionResponse.NotificationSubscriptionResponseBuilder responseBuilder) {

        if (!isValidSubscription(nsr)) {
            log.warn("{} - Invalid subscription representation", tenant);
            return;
        }

        log.debug("{} - Processing subscription {}", tenant, nsr.getId().getValue());

        Device device = new Device();
        device.setId(nsr.getSource().getId().getValue());

        try {
            ManagedObjectRepresentation mor = configurationRegistry.getC8yAgent()
                    .getManagedObjectForId(tenant, device.getId(), false, true);
            if (mor != null) {
                device.setName(mor.getName());
                device.setType(mor.getType());
                if (mor.getAssetParents() != null && mor.getAssetParents().getReferences() != null) {
                    List<String> groups = mor.getAssetParents().getReferences().stream()
                            .filter(ref -> ref.getManagedObject() != null
                                    && ref.getManagedObject().getName() != null)
                            .map(ref -> ref.getManagedObject().getName())
                            .collect(Collectors.toList());
                    device.setGroups(groups);
                }
            } else {
                log.warn("{} - Device {} in subscription does not exist; scheduling async cleanup of stale subscription {}",
                        tenant, device.getId(), nsr.getId().getValue());
                // M10: don't mutate state inside a read path — schedule the deletion asynchronously
                final NotificationSubscriptionRepresentation staleNsr = nsr;
                final String deviceIdForLog = device.getId();
                virtualThreadPool.submit(() -> {
                    try {
                        subscriptionsService.runForTenant(tenant, () -> subscriptionAPI.delete(staleNsr));
                        log.info("{} - Deleted stale subscription {} for non-existent device {}",
                                tenant, staleNsr.getId().getValue(), deviceIdForLog);
                    } catch (Exception deleteEx) {
                        log.warn("{} - Failed to delete stale subscription {} for device {}: {}",
                                tenant, staleNsr.getId().getValue(), deviceIdForLog, deleteEx.getMessage());
                    }
                });
                return;
            }
        } catch (Exception e) {
            log.warn("{} - Error retrieving device {}: {}", tenant, device.getId(), e.getMessage());
        }

        devices.add(device);

        // Set API and subscription name from first valid subscription
        if (nsr.getSubscriptionFilter() != null &&
                nsr.getSubscriptionFilter().getApis() != null &&
                !nsr.getSubscriptionFilter().getApis().isEmpty()) {
            try {
                API api = API.fromString(nsr.getSubscriptionFilter().getApis().get(0));
                responseBuilder.api(api);
                responseBuilder.subscriptionName(nsr.getSubscription());
            } catch (Exception e) {
                log.warn("{} - Error parsing API from subscription filter: {}", tenant, e.getMessage());
            }
        }
    }

    private Boolean isValidSubscription(NotificationSubscriptionRepresentation sub) {
        return sub != null &&
                sub.getSource() != null &&
                sub.getSource().getId() != null;
    }
}
