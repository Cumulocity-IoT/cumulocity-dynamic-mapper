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
     * Log a message.
     * 
     * @param msg The message to log.
     */
    void logMessage(Value msg);

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

    void clearState();
}