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

@Slf4j
public class ValuePool {

    private static final int MAX_POOL_VALUE_SIZE = 10; // You can adjust this value as needed

    private final BlockingQueue<Value> pool;
    private final AtomicInteger currentSize;
    private final Engine graalEngine;
    private final String sharedCode;
    private final String systemCode;
    private final Mapping mapping;
    private final HostAccess hostAccess;
    private Context.Builder graalContextBuilder;

    private ValuePool(Engine graalEngine, HostAccess hostAccess, String sharedCode, String systemCode,
            Mapping mapping) {
        this.pool = new LinkedBlockingQueue<>(MAX_POOL_VALUE_SIZE);
        this.currentSize = new AtomicInteger(0);
        this.graalEngine = graalEngine;
        this.sharedCode = sharedCode;
        this.systemCode = systemCode;
        this.hostAccess = hostAccess;
        this.mapping = mapping;

        log.info("ValuePool initialized with max size: {}", MAX_POOL_VALUE_SIZE);
    }

    /**
     * Factory method to create a ValuePool instance
     */
    public static ValuePool create(Engine graalEngine, HostAccess hostAccess, String sharedCode, String systemCode,
            Mapping mapping) {
        log.debug("Creating new ValuePool with engine: {}", graalEngine);
        return new ValuePool(graalEngine, hostAccess, sharedCode, systemCode, mapping);
    }

    /**
     * Borrow a Value from the pool. If no Value is available, create a new one.
     */
    public Value borrow() {
        Value value = pool.poll();

        if (value == null) {
            log.debug("No Value available in pool, creating new one");
            value = createNewValue();
        } else {
            currentSize.decrementAndGet();
            log.debug("Borrowed Value from pool. Current pool size: {}", currentSize.get());
        }

        return value;
    }

    /**
     * Release a Value to the pool if there's space available
     */
    public void release(Value value) {
        if (value == null) {
            log.warn("Attempted to return null Value to pool");
            return;
        }

        if (currentSize.get() < MAX_POOL_VALUE_SIZE) {
            boolean added = pool.offer(value);
            if (added) {
                currentSize.incrementAndGet();
                log.debug("Returned Value to pool. Current pool size: {}", currentSize.get());
            } else {
                log.warn("Failed to return Value to pool, closing context");
                closeValueContext(value);
            }
        } else {
            log.debug("Pool is full, closing returned Value context");
            closeValueContext(value);
        }
    }

    /**
     * Invalidate the pool by closing all Value contexts and clearing the pool
     */
    public void destroy() {
        log.info("Invalidating ValuePool, closing {} contexts", currentSize.get());

        Value value;
        while ((value = pool.poll()) != null) {
            closeValueContext(value);
            currentSize.decrementAndGet();
        }

        log.info("ValuePool invalidated successfully");
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
     * Create a new Value instance
     */
    private Value createNewValue() {
        try {
            log.debug("Creating new polyglot context and evaluating code");

            Context graalContext = createGraalContext();

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

            log.debug("Successfully created new Value");
            return sourceValue;

        } catch (Exception e) {
            log.error("Failed to create new Value", e);
            throw new RuntimeException("Failed to create polyglot Value", e);
        }
    }

    private Context createGraalContext()
            throws Exception {
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
     * Safely close a Value's context
     */
    private void closeValueContext(Value value) {
        try {
            if (value != null && value.getContext() != null) {
                value.getContext().close();
                log.debug("Successfully closed Value context");
            }
        } catch (Exception e) {
            log.error("Error closing Value context", e);
        }
    }
}