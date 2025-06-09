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
import java.util.concurrent.TimeUnit;

@Slf4j
public class ValuePool {

    private static final int MIN_POOL_VALUE_SIZE = 5; // Pre-populate with minimum values
    private static final long BORROW_TIMEOUT_MS = 5000; // 5 seconds timeout for borrowing

    private final BlockingQueue<PooledValue> pool;
    private final AtomicInteger currentSize;
    private final AtomicInteger totalCreated; // Track total created (including borrowed ones)
    private final AtomicInteger idGenerator;
    private final Engine graalEngine;
    private final String sharedCode;
    private final String systemCode;
    private final Mapping mapping;
    private final String tenant;
    private final HostAccess hostAccess;
    private final Set<Context> createdContexts;
    private final ConcurrentHashMap<Value, Integer> valueToIdMap;
    private final ConcurrentHashMap<Value, String> valueToThreadMap; // Track which thread created each value
    private Context.Builder graalContextBuilder;
    private volatile boolean destroyed = false;
    private int maxPoolValueSize;

    private ValuePool(String tenant, Engine graalEngine, HostAccess hostAccess, String sharedCode, String systemCode,
            Mapping mapping, int maxPoolValueSize) {
        this.pool = new LinkedBlockingQueue<>(maxPoolValueSize);
        this.currentSize = new AtomicInteger(0);
        this.totalCreated = new AtomicInteger(0);
        this.idGenerator = new AtomicInteger(0);
        this.graalEngine = graalEngine;
        this.sharedCode = sharedCode;
        this.systemCode = systemCode;
        this.hostAccess = hostAccess;
        this.mapping = mapping;
        this.tenant = tenant;
        this.createdContexts = ConcurrentHashMap.newKeySet();
        this.valueToIdMap = new ConcurrentHashMap<>();
        this.valueToThreadMap = new ConcurrentHashMap<>();
        this.maxPoolValueSize = maxPoolValueSize;

        log.info("{} - ValuePool initialized with max size: {}, min size: {}", 
                 tenant, maxPoolValueSize, MIN_POOL_VALUE_SIZE);
        
        // Pre-populate the pool
        initializePool();
    }

    /**
     * Inner class to hold Value and its associated Context
     */
    private static class PooledValue {
        private final Value value;
        private final Context context;
        private final int id;
        private final String createdByThread;

        public PooledValue(Value value, Context context, int id, String createdByThread ){
            this.value = value;
            this.context = context;
            this.id = id;
            this.createdByThread = createdByThread;
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

        public String getCreatedByThread() {
            return createdByThread;
        }
    }

    /**
     * Factory method to create a ValuePool instance
     */
    public static ValuePool create(String tenant, Engine graalEngine, HostAccess hostAccess, String sharedCode,
            String systemCode, Mapping mapping, int maxPoolValueSize) {
        log.debug("{} - Creating new ValuePool with engine: {}", tenant, graalEngine);
        return new ValuePool(tenant, graalEngine, hostAccess, sharedCode, systemCode, mapping, maxPoolValueSize);
    }

    /**
     * Pre-populate the pool with minimum number of values
     */
    private void initializePool() {
        log.info("{} - Pre-populating pool with {} values", tenant, MIN_POOL_VALUE_SIZE);
        for (int i = 0; i < MIN_POOL_VALUE_SIZE; i++) {
            try {
                PooledValue pooledValue = createNewPooledValue();
                pool.offer(pooledValue);
                currentSize.incrementAndGet();
            } catch (Exception e) {
                log.error("{} - Failed to pre-populate pool at index {}", tenant, i, e);
                break; // Stop pre-population on first failure
            }
        }
        log.info("{} - Pool pre-populated with {} values", tenant, currentSize.get());
    }

    /**
     * Borrow a Value from the pool. If no Value is available, try to create a new one or wait.
     */
    public Value borrow() throws InterruptedException {
        if (destroyed) {
            throw new IllegalStateException(tenant + " - ValuePool has been destroyed");
        }

        PooledValue pooledValue = pool.poll();

        if (pooledValue == null) {
            // Try to create a new one if we haven't reached the maximum total
            if (totalCreated.get() < maxPoolValueSize * 2) { // Allow some overflow beyond pool size
                log.info("{} - No Value available in pool, creating new one. Total created: {}", 
                         tenant, totalCreated.get());
                pooledValue = createNewPooledValue();
            } else {
                // Wait for a value to become available
                log.info("{} - Pool exhausted, waiting for available Value. Total created: {}", 
                         tenant, totalCreated.get());
                pooledValue = pool.poll(BORROW_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                
                if (pooledValue == null) {
                    throw new RuntimeException(String.format(
                        "%s - Timeout waiting for available Value after %d ms", tenant, BORROW_TIMEOUT_MS));
                }
                currentSize.decrementAndGet();
            }
        } else {
            currentSize.decrementAndGet();
            log.info("{} - Borrowed Value [ID: {}] from pool. Current pool size: {}", 
                     tenant, pooledValue.getId(), currentSize.get());
        }

        // Track the borrowing thread
        String currentThread = Thread.currentThread().getName();
        valueToThreadMap.put(pooledValue.getValue(), currentThread);
        
        log.debug("{} - Value [ID: {}] borrowed by thread: {}, created by thread: {}", 
                  tenant, pooledValue.getId(), currentThread, pooledValue.getCreatedByThread());

        return pooledValue.getValue();
    }

    /**
     * Non-blocking version of borrow
     */
    public Value tryBorrow() {
        if (destroyed) {
            throw new IllegalStateException(tenant + " - ValuePool has been destroyed");
        }

        PooledValue pooledValue = pool.poll();

        if (pooledValue == null) {
            // Try to create a new one if we haven't reached the maximum total
            if (totalCreated.get() < maxPoolValueSize * 2) {
                log.info("{} - No Value available in pool, creating new one. Total created: {}", 
                         tenant, totalCreated.get());
                pooledValue = createNewPooledValue();
            } else {
                log.warn("{} - No Value available and maximum total reached", tenant);
                return null;
            }
        } else {
            currentSize.decrementAndGet();
            log.info("{} - Borrowed Value [ID: {}] from pool. Current pool size: {}", 
                     tenant, pooledValue.getId(), currentSize.get());
        }

        // Track the borrowing thread
        String currentThread = Thread.currentThread().getName();
        valueToThreadMap.put(pooledValue.getValue(), currentThread);
        
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

        if (destroyed) {
            log.debug("{} - Pool destroyed, closing returned Value context", tenant);
            closeValueContextByValue(value);
            return;
        }

        // Get the ID for this value
        Integer valueId = valueToIdMap.get(value);
        String idInfo = valueId != null ? " [ID: " + valueId + "]" : " [ID: unknown]";
        String borrowingThread = valueToThreadMap.remove(value);

        // Find the context associated with this value
        Context valueContext = null;
        try {
            valueContext = value.getContext();
        } catch (Exception e) {
            log.warn("{} - Could not get context from Value{}, discarding", tenant, idInfo, e);
            valueToIdMap.remove(value);
            totalCreated.decrementAndGet();
            return;
        }

        // Only return to pool if we created this context and there's space
        if (createdContexts.contains(valueContext) && currentSize.get() < maxPoolValueSize) {
            PooledValue pooledValue = new PooledValue(value, valueContext, valueId != null ? valueId : -1, 
                                                     borrowingThread != null ? borrowingThread : "unknown");
            boolean added = pool.offer(pooledValue);
            if (added) {
                currentSize.incrementAndGet();
                log.info("{} - Returned Value{} to pool. Current pool size: {}", 
                         tenant, idInfo, currentSize.get());
            } else {
                log.warn("{} - Failed to return Value{} to pool, closing context", tenant, idInfo);
                closeValueContext(valueContext);
                valueToIdMap.remove(value);
                totalCreated.decrementAndGet();
            }
        } else {
            log.debug("{} - Pool is full or context not managed by pool, closing returned Value{} context", 
                      tenant, idInfo);
            closeValueContext(valueContext);
            valueToIdMap.remove(value);
            totalCreated.decrementAndGet();
        }
    }

    /**
     * Invalidate the pool by closing all Value contexts and clearing the pool
     */
    public void destroy() {
        destroyed = true;
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
                log.debug("{} - Context cannot be closed (not created by us or already closed)", tenant);
            } catch (Exception e) {
                log.warn("{} - Error closing remaining context during destroy", tenant, e);
            }
        }
        
        createdContexts.clear();
        valueToIdMap.clear();
        valueToThreadMap.clear();
        totalCreated.set(0);

        log.info("{} - ValuePool invalidated successfully", tenant);
    }

    /**
     * Get current pool size
     */
    public int getCurrentSize() {
        return currentSize.get();
    }

    /**
     * Get total created values
     */
    public int getTotalCreated() {
        return totalCreated.get();
    }

    /**
     * Check if pool is empty
     */
    public boolean isEmpty() {
        return currentSize.get() == 0;
    }

    /**
     * Check if pool is destroyed
     */
    public boolean isDestroyed() {
        return destroyed;
    }

    /**
     * Create a new PooledValue instance
     */
    private PooledValue createNewPooledValue() {
        try {
            int newId = idGenerator.incrementAndGet();
            String currentThread = Thread.currentThread().getName();
            log.debug("{} - Creating new polyglot context and evaluating code [ID: {}] on thread: {}", 
                      tenant, newId, currentThread);

            Context graalContext = createGraalContext();
            
            // Track this context so we know we can close it
            createdContexts.add(graalContext);
            totalCreated.incrementAndGet();

            String identifier = Mapping.EXTRACT_FROM_SOURCE + "_" + mapping.identifier;
            Value bindings = graalContext.getBindings("js");

            byte[] decodedBytes = Base64.getDecoder().decode(mapping.code);
            String decodedCode = new String(decodedBytes);
            String decodedCodeAdapted = decodedCode.replaceFirst(
                    Mapping.EXTRACT_FROM_SOURCE,
                    identifier);
            Source source = Source.newBuilder("js", decodedCodeAdapted, identifier + ".js")
                    .buildLiteral();
            graalContext.eval(source);
            Value sourceValue = bindings.getMember(identifier);

            if (sharedCode != null) {
                byte[] decodedSharedCodeBytes = Base64.getDecoder().decode(sharedCode);
                String decodedSharedCode = new String(decodedSharedCodeBytes);
                Source sharedSource = Source.newBuilder("js", decodedSharedCode, "sharedCode.js")
                        .buildLiteral();
                graalContext.eval(sharedSource);
            }

            if (systemCode != null) {
                byte[] decodedSystemCodeBytes = Base64.getDecoder().decode(systemCode);
                String decodedSystemCode = new String(decodedSystemCodeBytes);
                Source systemSource = Source.newBuilder("js", decodedSystemCode, "systemCode.js")
                        .buildLiteral();
                graalContext.eval(systemSource);
            }

            // Map the value to its ID for future reference
            valueToIdMap.put(sourceValue, newId);

            log.debug("{} - Successfully created new PooledValue [ID: {}] on thread: {}", 
                      tenant, newId, currentThread);
            return new PooledValue(sourceValue, graalContext, newId, currentThread);

        } catch (Exception e) {
            log.error("{} - Failed to create new PooledValue", tenant, e);
            totalCreated.decrementAndGet(); // Rollback the increment
            throw new RuntimeException(String.format("%s - Failed to create polyglot Value", tenant), e);
        }
    }

    private Context createGraalContext() throws Exception {
        if (graalContextBuilder == null) {
            graalContextBuilder = Context.newBuilder("js");
        }

        Context graalContext = graalContextBuilder
                .engine(graalEngine)
                .allowHostAccess(hostAccess)
                .allowCreateThread(true) // Allow thread creation
                .allowNativeAccess(true) // Allow native access for better performance
                .option("js.ecmascript-version", "2022") // Use modern JS version
                .allowHostClassLookup(className ->
                    className.equals("dynamic.mapping.processor.model.SubstitutionContext")
                            || className.equals("dynamic.mapping.processor.model.SubstitutionResult")
                            || className.equals("dynamic.mapping.processor.model.SubstituteValue")
                            || className.equals("dynamic.mapping.processor.model.SubstituteValue$TYPE")
                            || className.equals("dynamic.mapping.processor.model.RepairStrategy")
                            || className.equals("java.util.ArrayList")
                            || className.equals("java.util.HashMap"))
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
            if (createdContexts.contains(context)) {
                context.close();
                log.debug("{} - Successfully closed Value context", tenant);
                createdContexts.remove(context);
            } else {
                log.debug("{} - Context not managed by this pool, skipping close", tenant);
            }
        } catch (IllegalStateException e) {
            log.debug("{} - Context cannot be closed (not created by us or already closed)", tenant);
            createdContexts.remove(context);
        } catch (Exception e) {
            log.error("{} - Error closing Value context", tenant, e);
            createdContexts.remove(context);
        }
    }

    /**
     * Close context by Value reference
     */
    private void closeValueContextByValue(Value value) {
        try {
            Context context = value.getContext();
            closeValueContext(context);
            valueToIdMap.remove(value);
            valueToThreadMap.remove(value);
            totalCreated.decrementAndGet();
        } catch (Exception e) {
            log.warn("{} - Error getting context from Value for closing", tenant, e);
        }
    }
}