package dynamic.mapping.core.cache;

import org.apache.commons.collections4.map.LRUMap;

import com.cumulocity.model.ID;
import com.cumulocity.rest.representation.identity.ExternalIDRepresentation;

public class InboundExternalIdCache {

	private final LRUMap<ID, ExternalIDRepresentation> cache;

	// Constructor with default cache size
	public InboundExternalIdCache() {
		this(1000); // Default size of 1000
	}

	// Constructor with custom cache size
	public InboundExternalIdCache(int cacheSize) {
		this.cache = new LRUMap<>(cacheSize);
	}

	// Method to get ID by external ID
	public ExternalIDRepresentation getIdByExternalId(ID key) {
		return cache.get(key, true);
	}

	// Method to put a new entry in the cache
	public void putIdForExternalId(ID key, ExternalIDRepresentation id) {
		cache.put(key, id);
	}

	// Method to remove an entry from the cache
	public void removeIdForExternalId(ID key) {
		cache.remove(key);
	}

	// Method to clear the entire cache
	public void clearCache() {
		cache.clear();
	}

	// Method to get the current size of the cache
	public int getCacheSize() {
		return cache.size();
	}
}