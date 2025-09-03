package dynamic.mapper.processor.flow;

import org.graalvm.polyglot.Value;

/**
 * Flow context for JavaScript execution
 */
public interface FlowContext {
    
    /**
     * Sets a value in the context's state.
     * @param key The key for the state item.
     * @param value The value to set for the given key.
     */
    void setState(String key, Value value);
    
    /**
     * Retrieves a value from the context's state.
     * @param key The key of the state item to retrieve.
     * @return The value associated with the key, or null if not found.
     */
    Value getState(String key);
    
    /**
     * Retrieves the entire configuration map for the context.
     * @return A Value containing the context's configuration as a JS object.
     */
    Value getConfig();
    
    /**
     * Log a message.
     * @param msg The message to log.
     */
    void logMessage(Value msg);
    
    /**
     * Lookup DTM Asset properties
     * @param assetId The asset ID to lookup.
     * @return A Value containing the asset properties as a JS object.
     */
    Value lookupDTMAssetProperties(String assetId);
}