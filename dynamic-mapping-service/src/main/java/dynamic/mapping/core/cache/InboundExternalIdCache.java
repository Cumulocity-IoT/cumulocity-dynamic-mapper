package dynamic.mapping.core.cache;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import com.cumulocity.model.ID;
import com.cumulocity.rest.representation.identity.ExternalIDRepresentation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class InboundExternalIdCache {

	//private final LRUMap<ID, ExternalIDRepresentation> cache;
	private final Map<ID, ExternalIDRepresentation> cache;

	private Gauge cacheSizeGauge = null;

	// Constructor with default cache size
	public InboundExternalIdCache(String tenant) {
		this(1000, tenant); // Default size of 1000
	}

	// Constructor with custom cache size
	public InboundExternalIdCache(int cacheSize,  String tenant) {
		//Making it thread-safe
		this.cache = Collections.synchronizedMap(new LinkedHashMap<ID, ExternalIDRepresentation>() {
			//Removing oldest entries
			@Override
			protected boolean removeEldestEntry(Map.Entry<ID, ExternalIDRepresentation> eldest) {
				return size() > cacheSize;
			}
		});
		Tags tag = Tags.of("tenant", tenant);
		this.cacheSizeGauge = Gauge.builder("dynmapper_inbound_identity_cache_size", this.cache, Map::size)
				.tags(tag)
				.register(Metrics.globalRegistry);
	}

	public Gauge getCacheSizeGauge() {
		return cacheSizeGauge;
	}

	// Method to get ID by external ID
	public ExternalIDRepresentation getIdByExternalId(ID key) {
		return cache.get(key);
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