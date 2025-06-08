package dynamic.mapping.processor;

import lombok.extern.slf4j.Slf4j;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import dynamic.mapping.model.Mapping;

import java.util.Base64;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class ValuePool {

    private static final int MAX_POOL_VALUE_SIZE = 150; // You can adjust this value as needed

    private final BlockingQueue<PooledValue> pool;
    private final AtomicInteger currentSize;
    private final AtomicInteger idGenerator; // For generating unique IDs
    private final Engine graalEngine;
    private final String sharedCode;
    private final String systemCode;
    private final Mapping mapping;
    private final String tenant;
    private final HostAccess hostAccess;
    private final Set<Context> createdContexts;
    private final ConcurrentHashMap<Value, Integer> valueToIdMap; // Map Values to their IDs
    private Context.Builder graalContextBuilder;

    private ValuePool(String tenant, Engine graalEngine, HostAccess hostAccess, String sharedCode, String systemCode,
            Mapping mapping) {
        this.pool = new LinkedBlockingQueue<>(MAX_POOL_VALUE_SIZE);
        this.currentSize = new AtomicInteger(0);
        this.idGenerator = new AtomicInteger(0);
        this.graalEngine = graalEngine;
        this.sharedCode = sharedCode;
        this.systemCode = systemCode;
        this.hostAccess = hostAccess;
        this.mapping = mapping;
        this.tenant = tenant;
        this.createdContexts = ConcurrentHashMap.newKeySet();
        this.valueToIdMap = new ConcurrentHashMap<>();

        log.info("{} - ValuePool initialized with max size: {}", tenant, MAX_POOL_VALUE_SIZE);
    }

    /**
     * Inner class to hold Value and its associated Context
     */
    private static class PooledValue {
        private final Value value;
        private final Context context;
        private final int id;

        public PooledValue(Value value, Context context, int id) {
            this.value = value;
            this.context = context;
            this.id = id;
        }

        public Value getValue() {
            return value;
        }
        
        public Context getContext() {
            return context;
        }

        public int getId() {
            return id;
        }
    }

    /**
     * Factory method to create a ValuePool instance
     */
    public static ValuePool create(String tenant, Engine graalEngine, HostAccess hostAccess, String sharedCode,
            String systemCode,
            Mapping mapping) {
        log.debug("{} - Creating new ValuePool with engine: {}", tenant, graalEngine);
        return new ValuePool(tenant, graalEngine, hostAccess, sharedCode, systemCode, mapping);
    }

    /**
     * Borrow a Value from the pool. If no Value is available, create a new one.
     */
    public Value borrow() {
        PooledValue pooledValue = pool.poll();

        if (pooledValue == null) {
            log.info("{} - No Value available in pool, creating new one", tenant);
            pooledValue = createNewPooledValue();
        } else {
            currentSize.decrementAndGet();
            log.info("{} - Borrowed Value [ID: {}] from pool. Current pool size: {}", 
                     tenant, pooledValue.getId(), currentSize.get());
        }

        return pooledValue.getValue();
    }

    /**
     * Release a Value to the pool if there's space available
     */
    public void release(Value value) {
        if (value == null) {
            log.warn("{} - Attempted to return null Value to pool", tenant);
            return;
        }

        // Get the ID for this value
        Integer valueId = valueToIdMap.get(value);
        String idInfo = valueId != null ? " [ID: " + valueId + "]" : " [ID: unknown]";

        // Find the context associated with this value
        Context valueContext = null;
        try {
            valueContext = value.getContext();
        } catch (Exception e) {
            log.warn("{} - Could not get context from Value{}, discarding", tenant, idInfo, e);
            valueToIdMap.remove(value);
            return;
        }

        // Only return to pool if we created this context and there's space
        if (createdContexts.contains(valueContext) && currentSize.get() < MAX_POOL_VALUE_SIZE) {
            PooledValue pooledValue = new PooledValue(value, valueContext, valueId != null ? valueId : -1);
            boolean added = pool.offer(pooledValue);
            if (added) {
                currentSize.incrementAndGet();
                log.info("{} - Returned Value{} to pool. Current pool size: {}", 
                         tenant, idInfo, currentSize.get());
            } else {
                log.warn("{} - Failed to return Value{} to pool, closing context", tenant, idInfo);
                closeValueContext(valueContext);
                valueToIdMap.remove(value);
            }
        } else {
            log.debug("{} - Pool is full or context not managed by pool, closing returned Value{} context", 
                      tenant, idInfo);
            closeValueContext(valueContext);
            valueToIdMap.remove(value);
        }
    }

    /**
     * Invalidate the pool by closing all Value contexts and clearing the pool
     */
    public void destroy() {
        log.info("{} - Invalidating ValuePool, closing {} contexts", tenant, currentSize.get());

        PooledValue pooledValue;
        while ((pooledValue = pool.poll()) != null) {
            log.debug("{} - Closing PooledValue [ID: {}] during destroy", tenant, pooledValue.getId());
            closeValueContext(pooledValue.getContext());
            valueToIdMap.remove(pooledValue.getValue());
            currentSize.decrementAndGet();
        }

        // Close any remaining contexts we created
        for (Context context : createdContexts) {
            try {
                context.close();
                log.debug("{} - Closed remaining context during destroy", tenant);
            } catch (IllegalStateException e) {
                // Context was obtained via Context.get() or already closed
                log.debug("{} - Context cannot be closed (not created by us or already closed)", tenant);
            } catch (Exception e) {
                log.warn("{} - Error closing remaining context during destroy", tenant, e);
            }
        }
        createdContexts.clear();
        valueToIdMap.clear();

        log.info("{} - ValuePool invalidated successfully", tenant);
    }

    /**
     * Get current pool size
     */
    public int getCurrentSize() {
        return currentSize.get();
    }

    /**
     * Check if pool is empty
     */
    public boolean isEmpty() {
        return currentSize.get() == 0;
    }

    /**
     * Create a new PooledValue instance
     */
    private PooledValue createNewPooledValue() {
        try {
            int newId = idGenerator.incrementAndGet();
            log.debug("{} - Creating new polyglot context and evaluating code [ID: {}]", tenant, newId);

            Context graalContext = createGraalContext();
            
            // Track this context so we know we can close it
            createdContexts.add(graalContext);

            String identifier = Mapping.EXTRACT_FROM_SOURCE + "_" + mapping.identifier;
            Value bindings = graalContext.getBindings("js");

            byte[] decodedBytes = Base64.getDecoder().decode(mapping.code);
            String decodedCode = new String(decodedBytes);
            String decodedCodeAdapted = decodedCode.replaceFirst(
                    Mapping.EXTRACT_FROM_SOURCE,
                    identifier);
            Source source = Source.newBuilder("js", decodedCodeAdapted, identifier +
                    ".js")
                    .buildLiteral();
            graalContext.eval(source);
            Value sourceValue = bindings
                    .getMember(identifier);

            if (sharedCode != null) {
                byte[] decodedSharedCodeBytes = Base64.getDecoder().decode(sharedCode);
                String decodedSharedCode = new String(decodedSharedCodeBytes);
                Source sharedSource = Source.newBuilder("js", decodedSharedCode,
                        "sharedCode.js")
                        .buildLiteral();
                graalContext.eval(sharedSource);
            }

            if (systemCode != null) {
                byte[] decodedSystemCodeBytes = Base64.getDecoder().decode(systemCode);
                String decodedSystemCode = new String(decodedSystemCodeBytes);
                Source systemSource = Source.newBuilder("js", decodedSystemCode,
                        "systemCode.js")
                        .buildLiteral();
                graalContext.eval(systemSource);
            }

            // Map the value to its ID for future reference
            valueToIdMap.put(sourceValue, newId);

            log.debug("{} - Successfully created new PooledValue [ID: {}]", tenant, newId);
            return new PooledValue(sourceValue, graalContext, newId);

        } catch (Exception e) {
            log.error("{} - Failed to create new PooledValue", tenant, e);
            throw new RuntimeException(String.format("%s - Failed to create polyglot Value", tenant), e);
        }
    }

    private Context createGraalContext() throws Exception {
        if (graalContextBuilder == null)
            graalContextBuilder = Context.newBuilder("js");

        Context graalContext = graalContextBuilder
                .engine(graalEngine)
                // .option("engine.WarnInterpreterOnly", "false")
                .allowHostAccess(hostAccess)
                .allowHostClassLookup(className ->
                // Allow only the specific SubstitutionContext class
                className.equals("dynamic.mapping.processor.model.SubstitutionContext")
                        || className.equals("dynamic.mapping.processor.model.SubstitutionResult")
                        || className.equals("dynamic.mapping.processor.model.SubstituteValue")
                        || className.equals("dynamic.mapping.processor.model.SubstituteValue$TYPE")
                        || className.equals("dynamic.mapping.processor.model.RepairStrategy")
                        // Allow base collection classes needed for return values
                        || className.equals("java.util.ArrayList") ||
                        className.equals("java.util.HashMap"))
                .build();
        return graalContext;
    }

    /**
     * Safely close a Value's context only if we created it
     */
    private void closeValueContext(Context context) {
        if (context == null) {
            return;
        }
        
        try {
            // Only close contexts that we created and are tracking
            if (createdContexts.contains(context)) {
                context.close();
                log.debug("{} - Successfully closed Value context", tenant);
                createdContexts.remove(context);
            } else {
                log.debug("{} - Context not managed by this pool, skipping close", tenant);
            }
        } catch (IllegalStateException e) {
            // Context was obtained via Context.get() or already closed
            log.debug("{} - Context cannot be closed (not created by us or already closed)", tenant);
            createdContexts.remove(context);
        } catch (Exception e) {
            log.error("{} - Error closing Value context", tenant, e);
            // Remove from tracking even if close failed
            createdContexts.remove(context);
        }
    }
}