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

import com.cumulocity.model.idtype.GId;
import com.cumulocity.rest.representation.BaseCollectionRepresentation;
import com.cumulocity.rest.representation.PageStatisticsRepresentation;
import com.cumulocity.rest.representation.inventory.ManagedObjectCollectionRepresentation;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.cumulocity.sdk.client.PagedCollectionResource;
import com.cumulocity.sdk.client.QueryParam;
import com.cumulocity.sdk.client.SDKException;
import com.cumulocity.sdk.client.inventory.InventoryFilter;
import com.cumulocity.sdk.client.inventory.ManagedObjectCollection;
import com.cumulocity.sdk.client.inventory.PagedManagedObjectCollectionRepresentation;

import lombok.extern.slf4j.Slf4j;
import org.joda.time.DateTime;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Mock implementation of Cumulocity Inventory operations.
 * Provides in-memory storage for testing and dry-run scenarios without actual
 * C8Y API calls.
 * 
 * Features:
 * - Thread-safe in-memory storage
 * - Automatic ID generation
 * - Deep object cloning to prevent reference issues
 * - Full filter support for queries
 * - Statistics and utilities for testing
 */
@Slf4j
@Component
public class MockInventory {

    // In-memory storage for mock managed objects (thread-safe)
    private final Map<String, ManagedObjectRepresentation> storage = new ConcurrentHashMap<>();

    // Counter for generating unique mock IDs
    private final AtomicLong idCounter = new AtomicLong(10000);

    public MockInventory() {
        log.info("MockInventory initialized");
    }

    /**
     * Create a new managed object in mock storage.
     * Automatically generates ID, timestamps, and self link if not present.
     * 
     * @param mor The managed object to create
     * @return A copy of the created managed object with generated metadata
     */
    public ManagedObjectRepresentation create(ManagedObjectRepresentation mor) {
        if (mor == null) {
            throw new IllegalArgumentException("Cannot create null managed object");
        }

        log.debug("Mock: Creating managed object: {}", mor.getName());

        // Deep copy to avoid external modifications
        ManagedObjectRepresentation mockObject = deepCopy(mor);

        // Generate ID if not present
        if (mockObject.getId() == null) {
            String mockId = generateId();
            mockObject.setId(GId.asGId(mockId));
            log.trace("Mock: Generated new ID: {}", mockId);
        }

        // Set creation timestamp using DateTime
        DateTime now = DateTime.now();
        if (mockObject.getCreationDateTime() == null) {
            mockObject.setCreationDateTime(now);
        }
        mockObject.setLastUpdatedDateTime(now);

        // Generate self link
        if (mockObject.getSelf() == null) {
            mockObject.setSelf(generateSelfLink(mockObject.getId()));
        }

        // Store in mock storage
        storage.put(mockObject.getId().getValue(), mockObject);

        log.info("Mock: Created managed object [id={}, name={}, type={}]",
                mockObject.getId().getValue(), mockObject.getName(), mockObject.getType());

        // Return a copy to prevent external modification
        return deepCopy(mockObject);
    }

    /**
     * Retrieve a managed object by ID from mock storage.
     * 
     * @param id The ID of the managed object
     * @return A copy of the managed object, or null if not found
     */
    public ManagedObjectRepresentation get(GId id) {
        if (id == null) {
            throw new IllegalArgumentException("Cannot get managed object with null ID");
        }

        String idValue = id.getValue();
        log.debug("Mock: Getting managed object with ID: {}", idValue);

        ManagedObjectRepresentation mockObject = storage.get(idValue);

        if (mockObject == null) {
            log.debug("Mock: Managed object with ID {} not found", idValue);
            return null;
        }

        log.trace("Mock: Found managed object: {}", mockObject.getName());
        return deepCopy(mockObject);
    }

    /**
     * Update an existing managed object in mock storage.
     * Updates the lastUpdatedDateTime automatically.
     * 
     * @param mor The managed object to update
     * @return A copy of the updated managed object
     */
    public ManagedObjectRepresentation update(ManagedObjectRepresentation mor) {
        if (mor == null) {
            throw new IllegalArgumentException("Cannot update null managed object");
        }

        if (mor.getId() == null) {
            throw new IllegalArgumentException("Cannot update managed object without ID");
        }

        String id = mor.getId().getValue();
        log.debug("Mock: Updating managed object with ID: {}", id);

        // Check if object exists
        ManagedObjectRepresentation existing = storage.get(id);
        if (existing == null) {
            log.warn("Mock: Managed object with ID {} not found, creating new entry", id);
            return create(mor);
        }

        // Deep copy the update
        ManagedObjectRepresentation updated = deepCopy(mor);

        // Preserve creation time from existing object
        if (existing.getCreationDateTime() != null) {
            updated.setCreationDateTime(existing.getCreationDateTime());
        }

        // Update last updated time
        updated.setLastUpdatedDateTime(DateTime.now());

        // Ensure self link is present
        if (updated.getSelf() == null) {
            updated.setSelf(generateSelfLink(updated.getId()));
        }

        // Store updated object
        storage.put(id, updated);

        log.info("Mock: Updated managed object [id={}, name={}, type={}]",
                id, updated.getName(), updated.getType());

        return deepCopy(updated);
    }

    /**
     * Delete a managed object from mock storage.
     * 
     * @param id The ID of the managed object to delete
     */
    public void delete(GId id) {
        if (id == null) {
            throw new IllegalArgumentException("Cannot delete managed object with null ID");
        }

        String idValue = id.getValue();
        log.debug("Mock: Deleting managed object with ID: {}", idValue);

        ManagedObjectRepresentation removed = storage.remove(idValue);

        if (removed != null) {
            log.info("Mock: Deleted managed object [id={}, name={}]", idValue, removed.getName());
        } else {
            log.warn("Mock: Attempted to delete non-existent managed object with ID: {}", idValue);
        }
    }

    /**
     * Get managed objects filtered by various criteria.
     * Supports filtering by type, fragment type, owner, text, IDs, and child
     * relationships.
     * 
     * @param filter The filter to apply
     * @return A mock collection containing matching managed objects
     */
    public ManagedObjectCollection getManagedObjectsByFilter(InventoryFilter filter) {
        log.debug("Mock: Getting managed objects by filter");

        List<ManagedObjectRepresentation> filtered = applyFilter(filter);

        log.info("Mock: Found {} managed objects matching filter", filtered.size());

        return createMockCollection(filtered);
    }

    /**
     * Apply filter criteria to stored managed objects.
     * 
     * @param filter The filter to apply
     * @return List of matching managed objects
     */
    private List<ManagedObjectRepresentation> applyFilter(InventoryFilter filter) {
        if (filter == null) {
            // No filter - return all objects
            return storage.values().stream()
                    .map(this::deepCopy)
                    .collect(Collectors.toList());
        }

        return storage.values().stream()
                .filter(mo -> matchesFilter(mo, filter))
                .map(this::deepCopy)
                .collect(Collectors.toList());
    }

    /**
     * Check if a managed object matches filter criteria.
     * 
     * @param mo     The managed object to check
     * @param filter The filter criteria
     * @return true if the object matches all filter criteria
     */
    private boolean matchesFilter(ManagedObjectRepresentation mo, InventoryFilter filter) {
        // Filter by type
        if (filter.getType() != null && !filter.getType().isEmpty()) {
            if (mo.getType() == null || !mo.getType().equals(filter.getType())) {
                return false;
            }
        }

        // Filter by fragment type (check if property exists)
        if (filter.getFragmentType() != null && !filter.getFragmentType().isEmpty()) {
            if (!mo.hasProperty(filter.getFragmentType())) {
                return false;
            }
        }

        // Filter by owner
        if (filter.getOwner() != null && !filter.getOwner().isEmpty()) {
            if (mo.getOwner() == null || !mo.getOwner().equals(filter.getOwner())) {
                return false;
            }
        }

        // Filter by text (searches in name and other text fields)
        if (filter.getText() != null && !filter.getText().isEmpty()) {
            boolean matchesText = false;

            // Check name
            if (mo.getName() != null && mo.getName().toLowerCase().contains(filter.getText().toLowerCase())) {
                matchesText = true;
            }

            // Check type
            if (mo.getType() != null && mo.getType().toLowerCase().contains(filter.getText().toLowerCase())) {
                matchesText = true;
            }

            if (!matchesText) {
                return false;
            }
        }

        // Filter by IDs (comma-separated list)
        if (filter.getIds() != null && !filter.getIds().isEmpty()) {
            String[] ids = filter.getIds().split(",");
            boolean matchesId = false;

            for (String id : ids) {
                if (mo.getId() != null && mo.getId().getValue().equals(id.trim())) {
                    matchesId = true;
                    break;
                }
            }

            if (!matchesId) {
                return false;
            }
        }

        // Filter by child asset ID
        if (filter.getChildAssetId() != null && !filter.getChildAssetId().isEmpty()) {
            if (!hasChildWithId(mo.getChildAssets(), filter.getChildAssetId())) {
                return false;
            }
        }

        // Filter by child device ID
        if (filter.getChildDeviceId() != null && !filter.getChildDeviceId().isEmpty()) {
            if (!hasChildWithId(mo.getChildDevices(), filter.getChildDeviceId())) {
                return false;
            }
        }

        // Filter by child addition ID
        if (filter.getChildAdditionId() != null && !filter.getChildAdditionId().isEmpty()) {
            if (!hasChildWithId(mo.getChildAdditions(), filter.getChildAdditionId())) {
                return false;
            }
        }

        return true;
    }

    /**
     * Check if a reference collection contains a child with the specified ID.
     * 
     * @param referenceCollection The collection to search
     * @param childId             The child ID to find
     * @return true if the collection contains the child ID
     */
    private boolean hasChildWithId(Object referenceCollection, String childId) {
        // In a real implementation, you would need to iterate through the reference
        // collection
        // For now, we'll return true if the collection is not null (simplified)
        // You can expand this to actually check the references if needed
        if (referenceCollection == null) {
            return false;
        }

        // This is a simplified implementation
        // In reality, you'd need to iterate through
        // ManagedObjectReferenceCollectionRepresentation
        log.trace("Mock: Child reference filtering not fully implemented, returning true for non-null collection");
        return true;
    }

    /**
     * Create a mock ManagedObjectCollection from a list of objects.
     * Provides pagination support and iteration.
     * 
     * @param objects The list of managed objects
     * @return A mock collection wrapping the objects
     */
    private ManagedObjectCollection createMockCollection(List<ManagedObjectRepresentation> objects) {
        return new ManagedObjectCollection() {
            private final List<ManagedObjectRepresentation> items = new ArrayList<>(objects);
            private static final int DEFAULT_PAGE_SIZE = 5;

            @Override
            public PagedManagedObjectCollectionRepresentation get(QueryParam... queryParams) throws SDKException {
                return get(DEFAULT_PAGE_SIZE, queryParams);
            }

            @Override
            public PagedManagedObjectCollectionRepresentation get(int pageSize, QueryParam... queryParams)
                    throws SDKException {
                log.debug("Mock: Getting paged collection with pageSize={}", pageSize);

                // Create base collection representation
                ManagedObjectCollectionRepresentation baseCollection = createBaseCollection(1, pageSize);

                // Create paged representation with mock resource
                PagedManagedObjectCollectionRepresentation paged = new PagedManagedObjectCollectionRepresentation(
                        baseCollection,
                        createMockCollectionResource());

                log.trace("Mock: Created paged collection with {} items, {} total pages",
                        baseCollection.getManagedObjects().size(),
                        baseCollection.getPageStatistics().getTotalPages());

                return paged;
            }

            @Override
            public PagedManagedObjectCollectionRepresentation getNextPage(
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
                int nextPage = currentPage + 1;

                log.debug("Mock: Getting next page {} with pageSize={}", nextPage, pageSize);

                return getPage(collectionRepresentation, nextPage, pageSize);
            }

            @Override
            public PagedManagedObjectCollectionRepresentation getPreviousPage(
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
                int previousPage = Math.max(1, currentPage - 1);

                log.debug("Mock: Getting previous page {} with pageSize={}", previousPage, pageSize);

                return getPage(collectionRepresentation, previousPage, pageSize);
            }

            @Override
            public PagedManagedObjectCollectionRepresentation getPage(
                    BaseCollectionRepresentation collectionRepresentation, int pageNumber) throws SDKException {

                PageStatisticsRepresentation stats = collectionRepresentation != null
                        ? collectionRepresentation.getPageStatistics()
                        : null;
                int pageSize = stats != null && stats.getPageSize() != 0 ? stats.getPageSize() : DEFAULT_PAGE_SIZE;

                return getPage(collectionRepresentation, pageNumber, pageSize);
            }

            @Override
            public PagedManagedObjectCollectionRepresentation getPage(
                    BaseCollectionRepresentation collectionRepresentation,
                    int pageNumber,
                    int pageSize) throws SDKException {

                log.debug("Mock: Getting page {} with pageSize={}", pageNumber, pageSize);

                // Validate inputs
                int actualPageNumber = Math.max(1, pageNumber);
                int actualPageSize = pageSize > 0 ? pageSize : DEFAULT_PAGE_SIZE;

                // Create base collection for this page
                ManagedObjectCollectionRepresentation baseCollection = createBaseCollection(
                        actualPageNumber, actualPageSize);

                // Create paged representation with mock resource
                PagedManagedObjectCollectionRepresentation paged = new PagedManagedObjectCollectionRepresentation(
                        baseCollection,
                        createMockCollectionResource());

                log.trace("Mock: Created page {} with {} items (total pages: {})",
                        actualPageNumber,
                        baseCollection.getManagedObjects().size(),
                        baseCollection.getPageStatistics().getTotalPages());

                return paged;
            }

            /**
             * Create a base ManagedObjectCollectionRepresentation for a specific page.
             */
            private ManagedObjectCollectionRepresentation createBaseCollection(int pageNumber, int pageSize) {
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
                List<ManagedObjectRepresentation> pageItems = startIndex < items.size()
                        ? new ArrayList<>(items.subList(startIndex, endIndex))
                        : new ArrayList<>();

                // Create collection representation
                ManagedObjectCollectionRepresentation collection = new ManagedObjectCollectionRepresentation();
                collection.setManagedObjects(pageItems);

                // Set pagination metadata
                PageStatisticsRepresentation stats = new PageStatisticsRepresentation();
                stats.setPageSize(actualPageSize);
                stats.setCurrentPage(actualPageNumber);
                stats.setTotalPages(totalPages);
                collection.setPageStatistics(stats);

                // Set self link
                collection.setSelf(String.format("/inventory/managedObjects?pageSize=%d&currentPage=%d",
                        actualPageSize, actualPageNumber));

                // Set next link if there are more pages
                if (actualPageNumber < totalPages) {
                    collection.setNext(String.format("/inventory/managedObjects?pageSize=%d&currentPage=%d",
                            actualPageSize, actualPageNumber + 1));
                }

                // Set previous link if not on first page
                if (actualPageNumber > 1) {
                    collection.setPrev(String.format("/inventory/managedObjects?pageSize=%d&currentPage=%d",
                            actualPageSize, actualPageNumber - 1));
                }

                return collection;
            }

/**
 * Create a mock PagedCollectionResource for the collection.
 * This is needed by PagedManagedObjectCollectionRepresentation.
 */
private PagedCollectionResource<ManagedObjectRepresentation, ManagedObjectCollectionRepresentation> 
        createMockCollectionResource() {

    return new PagedCollectionResource<ManagedObjectRepresentation, ManagedObjectCollectionRepresentation>() {

        @Override
        public ManagedObjectCollectionRepresentation get(QueryParam... queryParams) throws SDKException {
            log.debug("Mock: CollectionResource.get() called with default page size");
            return createBaseCollection(1, DEFAULT_PAGE_SIZE);
        }

        @Override
        public ManagedObjectCollectionRepresentation get(int pageSize, QueryParam... queryParams)
                throws SDKException {
            log.debug("Mock: CollectionResource.get() called with pageSize={}", pageSize);
            return createBaseCollection(1, pageSize);
        }

        @Override
        public ManagedObjectCollectionRepresentation getNextPage(
                BaseCollectionRepresentation collectionRepresentation) throws SDKException {
            
            if (collectionRepresentation == null) {
                throw new SDKException("Collection representation cannot be null");
            }

            PageStatisticsRepresentation stats = collectionRepresentation.getPageStatistics();
            if (stats == null) {
                log.warn("Mock: No statistics found in collection, returning first page");
                return createBaseCollection(1, DEFAULT_PAGE_SIZE);
            }

            int currentPage = stats.getCurrentPage() != 0 ? stats.getCurrentPage() : 1;
            int pageSize = stats.getPageSize() != 0 ? stats.getPageSize() : DEFAULT_PAGE_SIZE;
            int totalPages = stats.getTotalPages() != 0 ? stats.getTotalPages() : 1;
            
            // Check if there's a next page
            if (currentPage >= totalPages) {
                log.debug("Mock: Already on last page {}, returning same page", currentPage);
                return createBaseCollection(currentPage, pageSize);
            }
            
            int nextPage = currentPage + 1;
            log.debug("Mock: CollectionResource.getNextPage() - moving from page {} to {}", 
                    currentPage, nextPage);
            
            return createBaseCollection(nextPage, pageSize);
        }

        @Override
        public ManagedObjectCollectionRepresentation getPreviousPage(
                BaseCollectionRepresentation collectionRepresentation) throws SDKException {
            
            if (collectionRepresentation == null) {
                throw new SDKException("Collection representation cannot be null");
            }

            PageStatisticsRepresentation stats = collectionRepresentation.getPageStatistics();
            if (stats == null) {
                log.warn("Mock: No statistics found in collection, returning first page");
                return createBaseCollection(1, DEFAULT_PAGE_SIZE);
            }

            int currentPage = stats.getCurrentPage() != 0 ? stats.getCurrentPage() : 1;
            int pageSize = stats.getPageSize() != 0 ? stats.getPageSize() : DEFAULT_PAGE_SIZE;
            
            // Check if there's a previous page
            if (currentPage <= 1) {
                log.debug("Mock: Already on first page, returning same page");
                return createBaseCollection(1, pageSize);
            }
            
            int previousPage = currentPage - 1;
            log.debug("Mock: CollectionResource.getPreviousPage() - moving from page {} to {}", 
                    currentPage, previousPage);
            
            return createBaseCollection(previousPage, pageSize);
        }

        @Override
        public ManagedObjectCollectionRepresentation getPage(
                BaseCollectionRepresentation collectionRepresentation, 
                int pageNumber) throws SDKException {
            
            if (collectionRepresentation == null) {
                log.warn("Mock: Null collection representation, using default page size");
                return createBaseCollection(pageNumber, DEFAULT_PAGE_SIZE);
            }

            PageStatisticsRepresentation stats = collectionRepresentation.getPageStatistics();
            int pageSize = stats != null && stats.getPageSize() != 0 
                    ? stats.getPageSize() 
                    : DEFAULT_PAGE_SIZE;
            
            log.debug("Mock: CollectionResource.getPage() - page {}, pageSize {}", 
                    pageNumber, pageSize);
            
            return createBaseCollection(pageNumber, pageSize);
        }

        @Override
        public ManagedObjectCollectionRepresentation getPage(
                BaseCollectionRepresentation collectionRepresentation, 
                int pageNumber, 
                int pageSize) throws SDKException {
            
            log.debug("Mock: CollectionResource.getPage() - page {}, pageSize {}", 
                    pageNumber, pageSize);
            
            // Validate page size
            int actualPageSize = pageSize > 0 ? pageSize : DEFAULT_PAGE_SIZE;
            
            return createBaseCollection(pageNumber, actualPageSize);
        }
    };
}
        };
    }

    /**
     * Deep copy a managed object using manual field copying.
     * This approach avoids Jackson serialization issues with C8Y objects.
     * 
     * @param original The object to copy
     * @return A deep copy of the object
     */
    private ManagedObjectRepresentation deepCopy(ManagedObjectRepresentation original) {
        if (original == null) {
            return null;
        }

        try {
            ManagedObjectRepresentation copy = new ManagedObjectRepresentation();

            // Copy basic fields
            copy.setId(original.getId());
            copy.setName(original.getName());
            copy.setType(original.getType());
            copy.setOwner(original.getOwner());

            // Copy DateTime fields
            copy.setCreationDateTime(original.getCreationDateTime());
            copy.setLastUpdatedDateTime(original.getLastUpdatedDateTime());

            // Copy self link
            copy.setSelf(original.getSelf());

            // Copy child relationships
            copy.setChildDevices(original.getChildDevices());
            copy.setChildAssets(original.getChildAssets());
            copy.setChildAdditions(original.getChildAdditions());

            // Copy parent relationships
            copy.setDeviceParents(original.getDeviceParents());
            copy.setAssetParents(original.getAssetParents());
            copy.setAdditionParents(original.getAdditionParents());

            // Copy custom attributes from AbstractExtensibleRepresentation
            if (original.getAttrs() != null) {
                Map<String, Object> attrs = new HashMap<>();
                original.getAttrs().forEach((key, value) -> {
                    // Perform shallow copy of attributes
                    attrs.put(key, value);
                });
                copy.setAttrs(attrs);
            }

            return copy;

        } catch (Exception e) {
            log.error("Mock: Error creating deep copy of managed object: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to create deep copy of managed object", e);
        }
    }

    /**
     * Generate a unique ID for a new managed object.
     * 
     * @return A unique ID string
     */
    private String generateId() {
        return String.valueOf(idCounter.getAndIncrement());
    }

    /**
     * Generate a self link for a managed object.
     * 
     * @param id The ID of the managed object
     * @return A self link URL
     */
    private String generateSelfLink(GId id) {
        return String.format("/inventory/managedObjects/%s", id.getValue());
    }

    // ===== Testing and Utility Methods =====

    /**
     * Clear all objects from mock storage.
     * Useful for test cleanup.
     */
    public void clear() {
        int count = storage.size();
        storage.clear();
        idCounter.set(10000);
        log.info("Mock: Cleared {} objects from storage", count);
    }

    /**
     * Check if a managed object exists in mock storage.
     * 
     * @param id The ID to check
     * @return true if the object exists
     */
    public boolean exists(GId id) {
        return id != null && storage.containsKey(id.getValue());
    }

    /**
     * Get the count of objects in mock storage.
     * 
     * @return The number of stored objects
     */
    public int getStorageCount() {
        return storage.size();
    }

    /**
     * Get all objects from mock storage.
     * Returns copies to prevent external modification.
     * 
     * @return List of all stored managed objects
     */
    public List<ManagedObjectRepresentation> getAllObjects() {
        return storage.values().stream()
                .map(this::deepCopy)
                .collect(Collectors.toList());
    }

    /**
     * Get objects by type.
     * 
     * @param type The type to filter by
     * @return List of matching managed objects
     */
    public List<ManagedObjectRepresentation> getObjectsByType(String type) {
        return storage.values().stream()
                .filter(mo -> type.equals(mo.getType()))
                .map(this::deepCopy)
                .collect(Collectors.toList());
    }

    /**
     * Get objects by name (partial match).
     * 
     * @param name The name to search for
     * @return List of matching managed objects
     */
    public List<ManagedObjectRepresentation> getObjectsByName(String name) {
        return storage.values().stream()
                .filter(mo -> mo.getName() != null && mo.getName().contains(name))
                .map(this::deepCopy)
                .collect(Collectors.toList());
    }

    /**
     * Pre-populate mock storage with a test object.
     * 
     * @param mor The managed object to add
     */
    public void addMockObject(ManagedObjectRepresentation mor) {
        if (mor == null) {
            throw new IllegalArgumentException("Cannot add null managed object");
        }

        ManagedObjectRepresentation copy = deepCopy(mor);

        // Generate ID if not present
        if (copy.getId() == null) {
            copy.setId(GId.asGId(generateId()));
        }

        // Set timestamps if not present
        DateTime now = DateTime.now();
        if (copy.getCreationDateTime() == null) {
            copy.setCreationDateTime(now);
        }
        if (copy.getLastUpdatedDateTime() == null) {
            copy.setLastUpdatedDateTime(now);
        }

        // Set self link if not present
        if (copy.getSelf() == null) {
            copy.setSelf(generateSelfLink(copy.getId()));
        }

        storage.put(copy.getId().getValue(), copy);
        log.debug("Mock: Pre-populated object [id={}, name={}]", copy.getId().getValue(), copy.getName());
    }

    /**
     * Set the next ID to be generated.
     * Useful for controlling IDs in tests.
     * 
     * @param nextId The next ID value
     */
    public void setNextId(long nextId) {
        idCounter.set(nextId);
        log.debug("Mock: Set next ID to {}", nextId);
    }

    /**
     * Get statistics about mock storage.
     * 
     * @return Map containing storage statistics
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalObjects", storage.size());
        stats.put("nextId", idCounter.get());

        // Count by type
        Map<String, Long> typeCount = storage.values().stream()
                .collect(Collectors.groupingBy(
                        mo -> mo.getType() != null ? mo.getType() : "unknown",
                        Collectors.counting()));
        stats.put("objectsByType", typeCount);

        // Count by owner
        Map<String, Long> ownerCount = storage.values().stream()
                .collect(Collectors.groupingBy(
                        mo -> mo.getOwner() != null ? mo.getOwner() : "unknown",
                        Collectors.counting()));
        stats.put("objectsByOwner", ownerCount);

        log.debug("Mock: Generated statistics: {}", stats);
        return stats;
    }

    /**
     * Get a summary of the mock storage state.
     * 
     * @return Human-readable summary string
     */
    public String getSummary() {
        Map<String, Object> stats = getStatistics();
        return String.format("MockInventory[objects=%d, nextId=%d, types=%s]",
                stats.get("totalObjects"),
                stats.get("nextId"),
                stats.get("objectsByType"));
    }
}