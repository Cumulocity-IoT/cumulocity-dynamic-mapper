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

package dynamic.mapper.core.mock;

import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;
import com.cumulocity.model.ID;
import com.cumulocity.model.idtype.GId;
import com.cumulocity.rest.representation.BaseCollectionRepresentation;
import com.cumulocity.rest.representation.PageStatisticsRepresentation;
import com.cumulocity.rest.representation.identity.ExternalIDCollectionRepresentation;
import com.cumulocity.rest.representation.identity.ExternalIDRepresentation;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.cumulocity.sdk.client.PagedCollectionResource;
import com.cumulocity.sdk.client.QueryParam;
import com.cumulocity.sdk.client.SDKException;
import com.cumulocity.sdk.client.identity.ExternalIDCollection;
import com.cumulocity.sdk.client.identity.PagedExternalIDCollectionRepresentation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Mock implementation of Cumulocity Identity operations.
 * Provides in-memory storage for external ID mappings without actual C8Y API
 * calls.
 * 
 * Features:
 * - Thread-safe storage of external ID to managed object mappings with tenant separation
 * - Bidirectional lookup (external ID to global ID and vice versa)
 * - Support for multiple external IDs per managed object
 * - Statistics and utilities for testing
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MockIdentity {

    /**
     * Storage structure: tenant -> "type:externalId" -> ExternalIDRepresentation
     * This allows quick lookup by external ID within tenant context
     */
    private final Map<String, Map<String, ExternalIDRepresentation>> externalIdStorage = new ConcurrentHashMap<>();

    /**
     * Reverse index: tenant -> GlobalId -> List of external IDs
     * This allows quick lookup of all external IDs for a managed object within tenant context
     */
    private final Map<String, Map<String, List<ExternalIDRepresentation>>> globalIdIndex = new ConcurrentHashMap<>();

    private final MicroserviceSubscriptionsService subscriptionsService;

    /**
     * Get or create external ID storage for a specific tenant.
     * 
     * @param tenant The tenant identifier
     * @return The external ID storage map for the tenant
     */
    private Map<String, ExternalIDRepresentation> getTenantExternalIdStorage(String tenant) {
        return externalIdStorage.computeIfAbsent(tenant, k -> new ConcurrentHashMap<>());
    }

    /**
     * Get or create global ID index for a specific tenant.
     * 
     * @param tenant The tenant identifier
     * @return The global ID index map for the tenant
     */
    private Map<String, List<ExternalIDRepresentation>> getTenantGlobalIdIndex(String tenant) {
        return globalIdIndex.computeIfAbsent(tenant, k -> new ConcurrentHashMap<>());
    }

    /**
     * Create a new external ID mapping.
     * Associates an external ID with a managed object.
     * 
     * @param externalIDRepresentation The external ID representation to create
     * @return The created external ID representation
     */
    public ExternalIDRepresentation create(ExternalIDRepresentation externalIDRepresentation) {
        String tenant = subscriptionsService.getTenant();
        
        if (tenant == null) {
            throw new IllegalStateException("No tenant context available");
        }

        if (externalIDRepresentation == null) {
            throw new IllegalArgumentException("Cannot create null external ID");
        }

        if (externalIDRepresentation.getExternalId() == null || externalIDRepresentation.getType() == null) {
            throw new IllegalArgumentException("External ID and type must not be null");
        }

        if (externalIDRepresentation.getManagedObject() == null) {
            throw new IllegalArgumentException("Managed object must not be null");
        }

        log.debug("{} - Mock: Creating external ID [type={}, id={}]",
                tenant,
                externalIDRepresentation.getType(),
                externalIDRepresentation.getExternalId());

        Map<String, ExternalIDRepresentation> tenantExternalIdStorage = getTenantExternalIdStorage(tenant);
        Map<String, List<ExternalIDRepresentation>> tenantGlobalIdIndex = getTenantGlobalIdIndex(tenant);

        // Create storage key
        String storageKey = createStorageKey(
                externalIDRepresentation.getType(),
                externalIDRepresentation.getExternalId());

        // Check if external ID already exists
        if (tenantExternalIdStorage.containsKey(storageKey)) {
            log.warn("{} - Mock: External ID [type={}, id={}] already exists, updating it",
                    tenant,
                    externalIDRepresentation.getType(),
                    externalIDRepresentation.getExternalId());
        }

        // Store the external ID
        ExternalIDRepresentation copy = deepCopy(externalIDRepresentation);
        tenantExternalIdStorage.put(storageKey, copy);

        // Update reverse index
        GId globalId = copy.getManagedObject().getId();
        if (globalId != null) {
            String globalIdKey = globalId.getValue();
            tenantGlobalIdIndex.computeIfAbsent(globalIdKey, k -> new ArrayList<>()).add(copy);

            // Remove duplicates in the list
            List<ExternalIDRepresentation> externalIds = tenantGlobalIdIndex.get(globalIdKey);
            tenantGlobalIdIndex.put(globalIdKey,
                    externalIds.stream()
                            .distinct()
                            .collect(Collectors.toList()));
        }

        log.info("{} - Mock: Created external ID [type={}, id={}, globalId={}]",
                tenant,
                copy.getType(),
                copy.getExternalId(),
                globalId != null ? globalId.getValue() : "null");

        return deepCopy(copy);
    }

    /**
     * Get an external ID by its type and value.
     * 
     * @param id The ID containing type and value
     * @return The external ID representation, or null if not found
     */
    public ExternalIDRepresentation getExternalId(ID id) {
        String tenant = subscriptionsService.getTenant();
        
        if (tenant == null) {
            throw new IllegalStateException("No tenant context available");
        }

        if (id == null) {
            throw new IllegalArgumentException("ID cannot be null");
        }

        if (id.getType() == null || id.getValue() == null) {
            throw new IllegalArgumentException("ID type and value must not be null");
        }

        String storageKey = createStorageKey(id.getType(), id.getValue());

        log.debug("{} - Mock: Getting external ID [type={}, id={}]", tenant, id.getType(), id.getValue());

        Map<String, ExternalIDRepresentation> tenantExternalIdStorage = getTenantExternalIdStorage(tenant);
        ExternalIDRepresentation result = tenantExternalIdStorage.get(storageKey);

        if (result == null) {
            log.debug("{} - Mock: External ID [type={}, id={}] not found", tenant, id.getType(), id.getValue());
            return null;
        }

        log.trace("{} - Mock: Found external ID mapping to global ID: {}",
                tenant, result.getManagedObject().getId().getValue());

        return deepCopy(result);
    }

    /**
     * Get an external ID of a specific type for a given global ID.
     * Returns the first matching external ID of the specified type.
     * 
     * @param gid The global ID
     * @return The external ID representation, or null if not found
     */
    public ExternalIDRepresentation getExternalIdsOfGlobalId(GId gid) {
        String tenant = subscriptionsService.getTenant();
        
        if (tenant == null) {
            throw new IllegalStateException("No tenant context available");
        }

        if (gid == null) {
            throw new IllegalArgumentException("Global ID cannot be null");
        }

        String globalIdKey = gid.getValue();

        log.debug("{} - Mock: Getting external IDs for global ID: {}", tenant, globalIdKey);

        Map<String, List<ExternalIDRepresentation>> tenantGlobalIdIndex = getTenantGlobalIdIndex(tenant);
        List<ExternalIDRepresentation> externalIds = tenantGlobalIdIndex.get(globalIdKey);

        if (externalIds == null || externalIds.isEmpty()) {
            log.debug("{} - Mock: No external IDs found for global ID: {}", tenant, globalIdKey);
            return null;
        }

        // Return first external ID (matches the behavior in IdentityFacade)
        ExternalIDRepresentation result = externalIds.get(0);

        log.trace("{} - Mock: Found {} external ID(s) for global ID: {}, returning first one [type={}, id={}]",
                tenant, externalIds.size(), globalIdKey, result.getType(), result.getExternalId());

        return deepCopy(result);
    }

    /**
     * Get all external IDs for a given global ID.
     * Returns a collection that can be iterated.
     * 
     * @param gid The global ID
     * @return Collection of external IDs
     */
    public ExternalIDCollection getExternalIdsCollectionOfGlobalId(GId gid) {
        String tenant = subscriptionsService.getTenant();
        
        if (tenant == null) {
            throw new IllegalStateException("No tenant context available");
        }

        if (gid == null) {
            throw new IllegalArgumentException("Global ID cannot be null");
        }

        String globalIdKey = gid.getValue();

        log.debug("{} - Mock: Getting external ID collection for global ID: {}", tenant, globalIdKey);

        Map<String, List<ExternalIDRepresentation>> tenantGlobalIdIndex = getTenantGlobalIdIndex(tenant);
        List<ExternalIDRepresentation> externalIds = tenantGlobalIdIndex.getOrDefault(globalIdKey, new ArrayList<>());

        log.info("{} - Mock: Found {} external ID(s) for global ID: {}", tenant, externalIds.size(), globalIdKey);

        // Return a mock collection
        return createMockExternalIDCollection(externalIds);
    }

    /**
     * Delete an external ID.
     * 
     * @param id The ID to delete
     */
    public void deleteExternalId(ID id) {
        String tenant = subscriptionsService.getTenant();
        
        if (tenant == null) {
            throw new IllegalStateException("No tenant context available");
        }

        if (id == null) {
            throw new IllegalArgumentException("ID cannot be null");
        }

        String storageKey = createStorageKey(id.getType(), id.getValue());

        log.debug("{} - Mock: Deleting external ID [type={}, id={}]", tenant, id.getType(), id.getValue());

        Map<String, ExternalIDRepresentation> tenantExternalIdStorage = getTenantExternalIdStorage(tenant);
        Map<String, List<ExternalIDRepresentation>> tenantGlobalIdIndex = getTenantGlobalIdIndex(tenant);

        ExternalIDRepresentation removed = tenantExternalIdStorage.remove(storageKey);

        if (removed != null) {
            // Update reverse index
            GId globalId = removed.getManagedObject().getId();
            if (globalId != null) {
                String globalIdKey = globalId.getValue();
                List<ExternalIDRepresentation> externalIds = tenantGlobalIdIndex.get(globalIdKey);
                if (externalIds != null) {
                    externalIds.removeIf(ext -> ext.getType().equals(id.getType()) &&
                            ext.getExternalId().equals(id.getValue()));

                    // Remove entry if list is empty
                    if (externalIds.isEmpty()) {
                        tenantGlobalIdIndex.remove(globalIdKey);
                    }
                }
            }

            log.info("{} - Mock: Deleted external ID [type={}, id={}]", tenant, id.getType(), id.getValue());
        } else {
            log.warn("{} - Mock: Attempted to delete non-existent external ID [type={}, id={}]",
                    tenant, id.getType(), id.getValue());
        }
    }

    /**
     * Delete all external IDs for a given global ID.
     * 
     * @param gid The global ID
     */
    public void deleteExternalIdsOfGlobalId(GId gid) {
        String tenant = subscriptionsService.getTenant();
        
        if (tenant == null) {
            throw new IllegalStateException("No tenant context available");
        }

        if (gid == null) {
            throw new IllegalArgumentException("Global ID cannot be null");
        }

        String globalIdKey = gid.getValue();

        log.debug("{} - Mock: Deleting all external IDs for global ID: {}", tenant, globalIdKey);

        Map<String, ExternalIDRepresentation> tenantExternalIdStorage = getTenantExternalIdStorage(tenant);
        Map<String, List<ExternalIDRepresentation>> tenantGlobalIdIndex = getTenantGlobalIdIndex(tenant);

        List<ExternalIDRepresentation> externalIds = tenantGlobalIdIndex.remove(globalIdKey);

        if (externalIds != null) {
            // Remove from main storage
            for (ExternalIDRepresentation externalId : externalIds) {
                String storageKey = createStorageKey(externalId.getType(), externalId.getExternalId());
                tenantExternalIdStorage.remove(storageKey);
            }

            log.info("{} - Mock: Deleted {} external ID(s) for global ID: {}", 
                    tenant, externalIds.size(), globalIdKey);
        } else {
            log.warn("{} - Mock: No external IDs found for global ID: {}", tenant, globalIdKey);
        }
    }

    /**
     * Create a storage key for external ID lookup.
     * 
     * @param type  The external ID type
     * @param value The external ID value
     * @return A unique storage key
     */
    private String createStorageKey(String type, String value) {
        return type + ":" + value;
    }

    /**
     * Create a deep copy of an external ID representation.
     * 
     * @param original The original representation
     * @return A deep copy
     */
    private ExternalIDRepresentation deepCopy(ExternalIDRepresentation original) {
        if (original == null) {
            return null;
        }

        ExternalIDRepresentation copy = new ExternalIDRepresentation();
        copy.setType(original.getType());
        copy.setExternalId(original.getExternalId());
        copy.setSelf(original.getSelf());

        // Copy managed object reference
        if (original.getManagedObject() != null) {
            ManagedObjectRepresentation morCopy = new ManagedObjectRepresentation();
            morCopy.setId(original.getManagedObject().getId());
            morCopy.setName(original.getManagedObject().getName());
            morCopy.setType(original.getManagedObject().getType());
            morCopy.setSelf(original.getManagedObject().getSelf());
            copy.setManagedObject(morCopy);
        }

        return copy;
    }

    /**
     * Create a mock ExternalIDCollection from a list of external IDs.
     * Provides full pagination support similar to Cumulocity's real collection.
     * 
     * @param externalIds The list of external IDs
     * @return A mock collection with pagination support
     */
    private ExternalIDCollection createMockExternalIDCollection(List<ExternalIDRepresentation> externalIds) {

        return new ExternalIDCollection() {
            private final List<ExternalIDRepresentation> items = new ArrayList<>(externalIds);
            private static final int DEFAULT_PAGE_SIZE = 5;

            @Override
            public PagedExternalIDCollectionRepresentation get(QueryParam... queryParams) throws SDKException {
                log.debug("Mock: Getting external ID collection with default page size");
                return get(DEFAULT_PAGE_SIZE, queryParams);
            }

            @Override
            public PagedExternalIDCollectionRepresentation get(int pageSize, QueryParam... queryParams)
                    throws SDKException {
                log.debug("Mock: Getting external ID collection with pageSize={}", pageSize);

                // Create base collection for first page
                ExternalIDCollectionRepresentation baseCollection = createBaseExternalIdCollection(1, pageSize);

                // Wrap in paged representation
                PagedExternalIDCollectionRepresentation paged = new PagedExternalIDCollectionRepresentation(
                        baseCollection,
                        createMockExternalIdCollectionResource());

                log.trace("Mock: Created paged external ID collection with {} items",
                        baseCollection.getExternalIds().size());

                return paged;
            }

            @Override
            public PagedExternalIDCollectionRepresentation getNextPage(
                    BaseCollectionRepresentation collectionRepresentation) throws SDKException {

                if (collectionRepresentation == null) {
                    throw new SDKException("Collection representation cannot be null");
                }

                PageStatisticsRepresentation stats = collectionRepresentation.getPageStatistics();
                if (stats == null) {
                    log.warn("Mock: No statistics found in collection, returning first page");
                    return get(DEFAULT_PAGE_SIZE);
                }

                int currentPage = stats.getCurrentPage() != 0 ? stats.getCurrentPage() : 1;
                int pageSize = stats.getPageSize() != 0 ? stats.getPageSize() : DEFAULT_PAGE_SIZE;
                int totalPages = stats.getTotalPages() != 0 ? stats.getTotalPages() : 1;

                // Check if there's a next page
                if (currentPage >= totalPages) {
                    log.debug("Mock: Already on last page {}, returning same page", currentPage);
                    ExternalIDCollectionRepresentation baseCollection = createBaseExternalIdCollection(
                            currentPage, pageSize);
                    return new PagedExternalIDCollectionRepresentation(
                            baseCollection,
                            createMockExternalIdCollectionResource());
                }

                int nextPage = currentPage + 1;
                log.debug("Mock: Getting next page {} with pageSize={}", nextPage, pageSize);

                return getPage(collectionRepresentation, nextPage, pageSize);
            }

            @Override
            public PagedExternalIDCollectionRepresentation getPreviousPage(
                    BaseCollectionRepresentation collectionRepresentation) throws SDKException {

                if (collectionRepresentation == null) {
                    throw new SDKException("Collection representation cannot be null");
                }

                PageStatisticsRepresentation stats = collectionRepresentation.getPageStatistics();
                if (stats == null) {
                    log.warn("Mock: No statistics found in collection, returning first page");
                    return get(DEFAULT_PAGE_SIZE);
                }

                int currentPage = stats.getCurrentPage() != 0 ? stats.getCurrentPage() : 1;
                int pageSize = stats.getPageSize() != 0 ? stats.getPageSize() : DEFAULT_PAGE_SIZE;

                // Check if there's a previous page
                if (currentPage <= 1) {
                    log.debug("Mock: Already on first page, returning same page");
                    ExternalIDCollectionRepresentation baseCollection = createBaseExternalIdCollection(1, pageSize);
                    return new PagedExternalIDCollectionRepresentation(
                            baseCollection,
                            createMockExternalIdCollectionResource());
                }

                int previousPage = currentPage - 1;
                log.debug("Mock: Getting previous page {} with pageSize={}", previousPage, pageSize);

                return getPage(collectionRepresentation, previousPage, pageSize);
            }

            @Override
            public PagedExternalIDCollectionRepresentation getPage(
                    BaseCollectionRepresentation collectionRepresentation,
                    int pageNumber) throws SDKException {

                PageStatisticsRepresentation stats = collectionRepresentation != null
                        ? collectionRepresentation.getPageStatistics()
                        : null;
                int pageSize = stats != null && stats.getPageSize() != 0
                        ? stats.getPageSize()
                        : DEFAULT_PAGE_SIZE;

                log.debug("Mock: Getting external ID page {}, pageSize {}", pageNumber, pageSize);

                return getPage(collectionRepresentation, pageNumber, pageSize);
            }

            @Override
            public PagedExternalIDCollectionRepresentation getPage(
                    BaseCollectionRepresentation collectionRepresentation,
                    int pageNumber,
                    int pageSize) throws SDKException {

                log.debug("Mock: Getting external ID page {} with pageSize={}", pageNumber, pageSize);

                // Validate page size
                int actualPageSize = pageSize > 0 ? pageSize : DEFAULT_PAGE_SIZE;

                // Create base collection for requested page
                ExternalIDCollectionRepresentation baseCollection = createBaseExternalIdCollection(
                        pageNumber, actualPageSize);

                // Wrap in paged representation
                PagedExternalIDCollectionRepresentation paged = new PagedExternalIDCollectionRepresentation(
                        baseCollection,
                        createMockExternalIdCollectionResource());

                log.trace("Mock: Created page {} with {} items",
                        pageNumber, baseCollection.getExternalIds().size());

                return paged;
            }

            /**
             * Create a base ExternalIDCollectionRepresentation for a specific page.
             */
            private ExternalIDCollectionRepresentation createBaseExternalIdCollection(
                    int pageNumber, int pageSize) {

                // Validate inputs
                int actualPageNumber = Math.max(1, pageNumber);
                int actualPageSize = pageSize > 0 ? pageSize : DEFAULT_PAGE_SIZE;

                // Calculate pagination
                int totalPages = (int) Math.ceil((double) items.size() / actualPageSize);
                int startIndex = (actualPageNumber - 1) * actualPageSize;

                // Check if page number is valid
                if (startIndex >= items.size() && items.size() > 0) {
                    log.warn("Mock: Page {} exceeds available data, using last page", actualPageNumber);
                    actualPageNumber = totalPages > 0 ? totalPages : 1;
                    startIndex = Math.max(0, (actualPageNumber - 1) * actualPageSize);
                }

                // Get page items
                int endIndex = Math.min(startIndex + actualPageSize, items.size());
                List<ExternalIDRepresentation> pageItems = startIndex < items.size()
                        ? new ArrayList<>(items.subList(startIndex, endIndex))
                        : new ArrayList<>();

                // Create collection representation
                ExternalIDCollectionRepresentation collection = new ExternalIDCollectionRepresentation();
                collection.setExternalIds(pageItems);

                // Set pagination metadata
                PageStatisticsRepresentation stats = new PageStatisticsRepresentation();
                stats.setPageSize(actualPageSize);
                stats.setCurrentPage(actualPageNumber);
                stats.setTotalPages(totalPages);
                collection.setPageStatistics(stats);

                // Set self link
                collection.setSelf(String.format("/identity/externalIds?pageSize=%d&currentPage=%d",
                        actualPageSize, actualPageNumber));

                // Set next link if there are more pages
                if (actualPageNumber < totalPages) {
                    collection.setNext(String.format("/identity/externalIds?pageSize=%d&currentPage=%d",
                            actualPageSize, actualPageNumber + 1));
                }

                // Set previous link if not on first page
                if (actualPageNumber > 1) {
                    collection.setPrev(String.format("/identity/externalIds?pageSize=%d&currentPage=%d",
                            actualPageSize, actualPageNumber - 1));
                }

                return collection;
            }

            /**
             * Create a mock PagedCollectionResource for external IDs.
             */
            private PagedCollectionResource<ExternalIDRepresentation, ExternalIDCollectionRepresentation> createMockExternalIdCollectionResource() {

                return new PagedCollectionResource<ExternalIDRepresentation, ExternalIDCollectionRepresentation>() {

                    @Override
                    public ExternalIDCollectionRepresentation get(QueryParam... queryParams) throws SDKException {
                        log.debug("Mock: ExternalIdCollectionResource.get() called with default page size");
                        return createBaseExternalIdCollection(1, DEFAULT_PAGE_SIZE);
                    }

                    @Override
                    public ExternalIDCollectionRepresentation get(int pageSize, QueryParam... queryParams)
                            throws SDKException {
                        log.debug("Mock: ExternalIdCollectionResource.get() called with pageSize={}", pageSize);
                        return createBaseExternalIdCollection(1, pageSize);
                    }

                    @Override
                    public ExternalIDCollectionRepresentation getNextPage(
                            BaseCollectionRepresentation collectionRepresentation) throws SDKException {

                        if (collectionRepresentation == null) {
                            throw new SDKException("Collection representation cannot be null");
                        }

                        PageStatisticsRepresentation stats = collectionRepresentation.getPageStatistics();
                        if (stats == null) {
                            log.warn("Mock: No statistics found, returning first page");
                            return createBaseExternalIdCollection(1, DEFAULT_PAGE_SIZE);
                        }

                        int currentPage = stats.getCurrentPage() != 0 ? stats.getCurrentPage() : 1;
                        int pageSize = stats.getPageSize() != 0 ? stats.getPageSize() : DEFAULT_PAGE_SIZE;
                        int totalPages = stats.getTotalPages() != 0 ? stats.getTotalPages() : 1;

                        if (currentPage >= totalPages) {
                            return createBaseExternalIdCollection(currentPage, pageSize);
                        }

                        int nextPage = currentPage + 1;
                        return createBaseExternalIdCollection(nextPage, pageSize);
                    }

                    @Override
                    public ExternalIDCollectionRepresentation getPreviousPage(
                            BaseCollectionRepresentation collectionRepresentation) throws SDKException {

                        if (collectionRepresentation == null) {
                            throw new SDKException("Collection representation cannot be null");
                        }

                        PageStatisticsRepresentation stats = collectionRepresentation.getPageStatistics();
                        if (stats == null) {
                            return createBaseExternalIdCollection(1, DEFAULT_PAGE_SIZE);
                        }

                        int currentPage = stats.getCurrentPage() != 0 ? stats.getCurrentPage() : 1;
                        int pageSize = stats.getPageSize() != 0 ? stats.getPageSize() : DEFAULT_PAGE_SIZE;

                        if (currentPage <= 1) {
                            return createBaseExternalIdCollection(1, pageSize);
                        }

                        int previousPage = currentPage - 1;
                        return createBaseExternalIdCollection(previousPage, pageSize);
                    }

                    @Override
                    public ExternalIDCollectionRepresentation getPage(
                            BaseCollectionRepresentation collectionRepresentation,
                            int pageNumber) throws SDKException {

                        PageStatisticsRepresentation stats = collectionRepresentation != null
                                ? collectionRepresentation.getPageStatistics()
                                : null;
                        int pageSize = stats != null && stats.getPageSize() != 0
                                ? stats.getPageSize()
                                : DEFAULT_PAGE_SIZE;

                        return createBaseExternalIdCollection(pageNumber, pageSize);
                    }

                    @Override
                    public ExternalIDCollectionRepresentation getPage(
                            BaseCollectionRepresentation collectionRepresentation,
                            int pageNumber,
                            int pageSize) throws SDKException {

                        int actualPageSize = pageSize > 0 ? pageSize : DEFAULT_PAGE_SIZE;
                        return createBaseExternalIdCollection(pageNumber, actualPageSize);
                    }
                };
            }
        };
    }

    // ===== Testing and Utility Methods =====

    /**
     * Clear all external ID mappings for the current tenant.
     * Useful for test cleanup.
     */
    public void clear() {
        String tenant = subscriptionsService.getTenant();
        
        if (tenant == null) {
            throw new IllegalStateException("No tenant context available");
        }

        Map<String, ExternalIDRepresentation> tenantExternalIdStorage = getTenantExternalIdStorage(tenant);
        Map<String, List<ExternalIDRepresentation>> tenantGlobalIdIndex = getTenantGlobalIdIndex(tenant);

        int externalIdCount = tenantExternalIdStorage.size();
        int globalIdCount = tenantGlobalIdIndex.size();

        tenantExternalIdStorage.clear();
        tenantGlobalIdIndex.clear();

        log.info("{} - Mock: Cleared {} external ID(s) and {} global ID index entries",
                tenant, externalIdCount, globalIdCount);
    }

    /**
     * Clear all external ID mappings for all tenants.
     * Use with caution - typically only for test cleanup.
     */
    public void clearAll() {
        int totalExternalIds = externalIdStorage.values().stream()
                .mapToInt(Map::size)
                .sum();
        int totalGlobalIds = globalIdIndex.values().stream()
                .mapToInt(Map::size)
                .sum();

        externalIdStorage.clear();
        globalIdIndex.clear();

        log.info("Mock: Cleared {} external ID(s) and {} global ID index entries from all tenants",
                totalExternalIds, totalGlobalIds);
    }

    /**
     * Check if an external ID exists.
     * 
     * @param id The ID to check
     * @return true if the external ID exists
     */
    public boolean exists(ID id) {
        String tenant = subscriptionsService.getTenant();
        
        if (tenant == null) {
            throw new IllegalStateException("No tenant context available");
        }

        if (id == null || id.getType() == null || id.getValue() == null) {
            return false;
        }

        String storageKey = createStorageKey(id.getType(), id.getValue());
        Map<String, ExternalIDRepresentation> tenantExternalIdStorage = getTenantExternalIdStorage(tenant);
        return tenantExternalIdStorage.containsKey(storageKey);
    }

    /**
     * Check if a global ID has any external IDs.
     * 
     * @param gid The global ID to check
     * @return true if the global ID has external IDs
     */
    public boolean hasExternalIds(GId gid) {
        String tenant = subscriptionsService.getTenant();
        
        if (tenant == null) {
            throw new IllegalStateException("No tenant context available");
        }

        if (gid == null) {
            return false;
        }

        Map<String, List<ExternalIDRepresentation>> tenantGlobalIdIndex = getTenantGlobalIdIndex(tenant);
        List<ExternalIDRepresentation> externalIds = tenantGlobalIdIndex.get(gid.getValue());
        return externalIds != null && !externalIds.isEmpty();
    }

    /**
     * Get count of stored external IDs for the current tenant.
     * 
     * @return The number of external IDs
     */
    public int getExternalIdCount() {
        String tenant = subscriptionsService.getTenant();
        
        if (tenant == null) {
            throw new IllegalStateException("No tenant context available");
        }

        Map<String, ExternalIDRepresentation> tenantExternalIdStorage = getTenantExternalIdStorage(tenant);
        return tenantExternalIdStorage.size();
    }

    /**
     * Get total count of external IDs across all tenants.
     * 
     * @return The total number of external IDs
     */
    public int getTotalExternalIdCount() {
        return externalIdStorage.values().stream()
                .mapToInt(Map::size)
                .sum();
    }

    /**
     * Get count of global IDs that have external IDs for the current tenant.
     * 
     * @return The number of global IDs
     */
    public int getGlobalIdCount() {
        String tenant = subscriptionsService.getTenant();
        
        if (tenant == null) {
            throw new IllegalStateException("No tenant context available");
        }

        Map<String, List<ExternalIDRepresentation>> tenantGlobalIdIndex = getTenantGlobalIdIndex(tenant);
        return tenantGlobalIdIndex.size();
    }

    /**
     * Get total count of global IDs across all tenants.
     * 
     * @return The total number of global IDs
     */
    public int getTotalGlobalIdCount() {
        return globalIdIndex.values().stream()
                .mapToInt(Map::size)
                .sum();
    }

    /**
     * Get all external IDs for the current tenant (for testing).
     * 
     * @return List of all external IDs
     */
    public List<ExternalIDRepresentation> getAllExternalIds() {
        String tenant = subscriptionsService.getTenant();
        
        if (tenant == null) {
            throw new IllegalStateException("No tenant context available");
        }

        Map<String, ExternalIDRepresentation> tenantExternalIdStorage = getTenantExternalIdStorage(tenant);
        return tenantExternalIdStorage.values().stream()
                .map(this::deepCopy)
                .collect(Collectors.toList());
    }

    /**
     * Get external IDs by type for the current tenant (for testing).
     * 
     * @param type The type to filter by
     * @return List of matching external IDs
     */
    public List<ExternalIDRepresentation> getExternalIdsByType(String type) {
        String tenant = subscriptionsService.getTenant();
        
        if (tenant == null) {
            throw new IllegalStateException("No tenant context available");
        }

        Map<String, ExternalIDRepresentation> tenantExternalIdStorage = getTenantExternalIdStorage(tenant);
        return tenantExternalIdStorage.values().stream()
                .filter(ext -> type.equals(ext.getType()))
                .map(this::deepCopy)
                .collect(Collectors.toList());
    }

    /**
     * Get statistics about mock storage for the current tenant.
     * 
     * @return Map containing storage statistics
     */
    public Map<String, Object> getStatistics() {
        String tenant = subscriptionsService.getTenant();
        
        if (tenant == null) {
            throw new IllegalStateException("No tenant context available");
        }

        Map<String, ExternalIDRepresentation> tenantExternalIdStorage = getTenantExternalIdStorage(tenant);
        Map<String, List<ExternalIDRepresentation>> tenantGlobalIdIndex = getTenantGlobalIdIndex(tenant);

        Map<String, Object> stats = new HashMap<>();
        stats.put("tenant", tenant);
        stats.put("totalExternalIds", tenantExternalIdStorage.size());
        stats.put("totalGlobalIds", tenantGlobalIdIndex.size());

        // Count by type
        Map<String, Long> typeCount = tenantExternalIdStorage.values().stream()
                .collect(Collectors.groupingBy(
                        ExternalIDRepresentation::getType,
                        Collectors.counting()));
        stats.put("externalIdsByType", typeCount);

        // Average external IDs per global ID
        double avgExternalIdsPerGlobalId = tenantGlobalIdIndex.isEmpty() ? 0.0
                : (double) tenantExternalIdStorage.size() / tenantGlobalIdIndex.size();
        stats.put("avgExternalIdsPerGlobalId", avgExternalIdsPerGlobalId);

        log.debug("{} - Mock: Generated statistics: {}", tenant, stats);
        return stats;
    }

    /**
     * Get statistics for all tenants.
     * 
     * @return Map containing storage statistics for all tenants
     */
    public Map<String, Object> getAllStatistics() {
        Map<String, Object> allStats = new HashMap<>();
        
        allStats.put("totalTenants", externalIdStorage.size());
        allStats.put("totalExternalIds", getTotalExternalIdCount());
        allStats.put("totalGlobalIds", getTotalGlobalIdCount());
        
        Map<String, Integer> externalIdsByTenant = new HashMap<>();
        externalIdStorage.forEach((tenant, tenantStorage) -> {
            externalIdsByTenant.put(tenant, tenantStorage.size());
        });
        allStats.put("externalIdsByTenant", externalIdsByTenant);
        
        Map<String, Integer> globalIdsByTenant = new HashMap<>();
        globalIdIndex.forEach((tenant, tenantIndex) -> {
            globalIdsByTenant.put(tenant, tenantIndex.size());
        });
        allStats.put("globalIdsByTenant", globalIdsByTenant);
        
        log.debug("Mock: Generated all-tenant statistics: {}", allStats);
        return allStats;
    }

    /**
     * Get a summary of the mock identity state for the current tenant.
     * 
     * @return Human-readable summary string
     */
    public String getSummary() {
        String tenant = subscriptionsService.getTenant();
        
        if (tenant == null) {
            throw new IllegalStateException("No tenant context available");
        }

        Map<String, Object> stats = getStatistics();
        return String.format("MockIdentity[tenant=%s, externalIds=%d, globalIds=%d, types=%s]",
                stats.get("tenant"),
                stats.get("totalExternalIds"),
                stats.get("totalGlobalIds"),
                stats.get("externalIdsByType"));
    }

    /**
     * Get a list of all tenants with mock data.
     * 
     * @return Set of tenant identifiers
     */
    public Set<String> getTenants() {
        Set<String> tenants = new HashSet<>();
        tenants.addAll(externalIdStorage.keySet());
        tenants.addAll(globalIdIndex.keySet());
        return tenants;
    }
}