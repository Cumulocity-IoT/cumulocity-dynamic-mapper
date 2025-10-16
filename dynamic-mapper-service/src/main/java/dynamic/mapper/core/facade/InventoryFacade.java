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

package dynamic.mapper.core.facade;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.cumulocity.model.idtype.GId;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.cumulocity.sdk.client.inventory.InventoryApi;
import com.cumulocity.sdk.client.inventory.InventoryFilter;
import com.cumulocity.sdk.client.inventory.ManagedObjectCollection;

import dynamic.mapper.core.mock.MockInventory;
import lombok.extern.slf4j.Slf4j;

/**
 * Facade for Inventory operations that routes calls to either real C8Y API or mock implementation.
 * The routing decision is based on the testing flag and sendPayload flag in the ProcessingContext.
 * 
 * Rules:
 * - If testing flag is true: use real C8Y API (for integration tests)
 * - If sendPayload is true: use real C8Y API (for production)
 * - Otherwise: use mock implementation (for dry-run/validation)
 */
@Slf4j
@Service
public class InventoryFacade {

    @Autowired
    private MockInventory inventoryMock;

    @Autowired
    private InventoryApi inventoryApi;


    /**
     * Create a managed object with boolean flag.
     * Routes to real API if testing is true, otherwise uses mock.
     * 
     * @param mor The managed object to create
     * @param testing Flag indicating if this is a test scenario
     * @return The created managed object representation
     */
    public ManagedObjectRepresentation create(ManagedObjectRepresentation mor, Boolean testing) {
        if (Boolean.FALSE.equals(testing)) {
            log.debug("Creating managed object via real C8Y API (testing mode): {}", mor.getName());
            return inventoryApi.create(mor);
        } else {
            log.debug("Creating managed object via mock: {}", mor.getName());
            return inventoryMock.create(mor);
        }
    }


    /**
     * Get a managed object by ID with boolean flag.
     * Routes to real API if testing is true, otherwise uses mock.
     * 
     * @param id The ID of the managed object
     * @param testing Flag indicating if this is a test scenario
     * @return The managed object representation, or null if not found
     */
    public ManagedObjectRepresentation get(GId id, Boolean testing) {
        if (Boolean.FALSE.equals(testing)) {
            log.debug("Getting managed object via real C8Y API (testing mode): {}", id);
            return inventoryApi.get(id);
        } else {
            log.debug("Getting managed object via mock: {}", id);
            return inventoryMock.get(id);
        }
    }


    /**
     * Delete a managed object by ID with boolean flag.
     * Routes to real API if testing is true, otherwise uses mock.
     * 
     * @param id The ID of the managed object to delete
     * @param testing Flag indicating if this is a test scenario
     */
    public void delete(GId id, Boolean testing) {
        if (Boolean.FALSE.equals(testing)) {
            log.debug("Deleting managed object via real C8Y API (testing mode): {}", id);
            inventoryApi.delete(id);
        } else {
            log.debug("Deleting managed object via mock: {}", id);
            inventoryMock.delete(id);
        }
    }

    /**
     * Update a managed object with boolean flag.
     * Routes to real API if testing is true, otherwise uses mock.
     * 
     * @param mor The managed object to update
     * @param testing Flag indicating if this is a test scenario
     * @return The updated managed object representation
     */
    public ManagedObjectRepresentation update(ManagedObjectRepresentation mor, Boolean testing) {
        if (Boolean.FALSE.equals(testing)) {
            log.debug("Updating managed object via real C8Y API (testing mode): {}", mor.getId());
            return inventoryApi.update(mor);
        } else {
            log.debug("Updating managed object via mock: {}", mor.getId());
            return inventoryMock.update(mor);
        }
    }


    /**
     * Get managed objects by filter with boolean flag.
     * Routes to real API if testing is true, otherwise uses mock.
     * 
     * @param inventoryFilter The filter to apply
     * @param testing Flag indicating if this is a test scenario
     * @return Collection of managed objects matching the filter
     */
    public ManagedObjectCollection getManagedObjectsByFilter(InventoryFilter inventoryFilter, Boolean testing) {
        if (Boolean.FALSE.equals(testing)) {
            log.debug("Getting managed objects by filter via real C8Y API (testing mode)");
            return inventoryApi.getManagedObjectsByFilter(inventoryFilter);
        } else {
            log.debug("Getting managed objects by filter via mock");
            return inventoryMock.getManagedObjectsByFilter(inventoryFilter);
        }
    }


    /**
     * Get the mock inventory instance (useful for testing and setup).
     * 
     * @return The mock inventory instance
     */
    public MockInventory getMockInventory() {
        return inventoryMock;
    }

    /**
     * Get the real inventory API instance (useful for advanced use cases).
     * 
     * @return The real inventory API instance
     */
    public InventoryApi getInventoryApi() {
        return inventoryApi;
    }

    /**
     * Clear mock storage (useful for test cleanup).
     */
    public void clearMockStorage() {
        log.info("Clearing mock inventory storage");
        inventoryMock.clear();
    }

    /**
     * Get mock storage statistics (useful for debugging).
     * 
     * @return Map containing storage statistics
     */
    public java.util.Map<String, Object> getMockStatistics() {
        return inventoryMock.getStatistics();
    }

    /**
     * Pre-populate mock storage with test data.
     * 
     * @param mor The managed object to add to mock storage
     */
    public void addMockObject(ManagedObjectRepresentation mor) {
        log.debug("Adding mock object: {}", mor.getName());
        inventoryMock.addMockObject(mor);
    }

    /**
     * Check if an object exists in mock storage.
     * 
     * @param id The ID to check
     * @return true if object exists in mock storage
     */
    public boolean existsInMock(GId id) {
        return inventoryMock.exists(id);
    }


    public void clearInventoryCache() {
       inventoryMock.clear();
    }
}