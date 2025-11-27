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
package dynamic.mapper.processor.flow;

import org.graalvm.polyglot.Value;

/**
 * Flow context for JavaScript execution
 */
public interface DataPrepContext {

    String WARNINGS = "_WARNINGS_";
    String LOGS = "_LOGS_";

    /**
     * Sets a value in the context's state.
     * 
     * @param key   The key for the state item.
     * @param value The value to set for the given key.
     */
    void setState(String key, Value value);

    /**
     * Retrieves a value from the context's state.
     * 
     * @param key The key of the state item to retrieve.
     * @return The value associated with the key, or null if not found.
     */
    Value getState(String key);

    /**
     * Retrieves all values from the context's state.
     * 
     * @return The value associated with all keys, or null if not found.
     */
    Value getStateAll();

    /**
     * Retrieves the keys from the context's state.
     * 
     * @return The keys stored in the the context's state, or null if not found.
     */
    Value getStateKeySet();

    /**
     * Retrieves the entire configuration map for the context.
     * 
     * @return A Value containing the context's configuration as a JS object.
     */
    Value getConfig();

    /**
     * Lookup DTM Asset properties
     * 
     * @param assetId The asset ID to lookup.
     * @return A Value containing the asset properties as a JS object.
     */
    Value getDTMAsset(String assetId);

    /**
     * Lookup Inventory Device properties
     * 
     * @param deviceId The device ID to lookup.
     * @return A Value containing the device properties as a JS object.
     */
    Value getManagedObjectByDeviceId(String deviceId);

    /**
     * Lookup Inventory Device properties by external id
     * 
     * @param externalId The externalId Id to lookup.
     * @return A Value containing the device properties as a JS object.
     */
    Value getManagedObject(ExternalId externalId);


        /**
     * Lookup Inventory Device properties by external id
     * 
     * @param externalIdValue A Value object containing externalId and type properties
     * @return A Value containing the device properties as a JS object.
     */
    Value getManagedObject(Value externalIdValue);

    /**
     * Log message
     * 
     * @param message Message to log
     * 
     */
    public void addLogMessage(String message);

    /**
     * Testing cycle indicator
     * 
     * @return Is context used in a testing cycle
     * 
     */
    public Boolean getTesting();

    void clearState();
}