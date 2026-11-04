package dynamic.mapper.core;

/**
 * A bean that holds per-tenant state or runs per-tenant activity.
 *
 * <p>{@link BootstrapService} discovers all implementations and, when a tenant unsubscribes, calls them in two
 * phases: first {@link #stopTenant} on every bean, then {@link #removeTenant} on every bean. "Stop before
 * remove" therefore holds by construction, whatever the order within a phase. Within a phase beans run in
 * {@code @Order} (use the {@code ORDER_*} constants), so state that other beans still read while being removed
 * (credentials, configuration) goes last. A failing bean is logged and does not skip the others.
 *
 * <p>Naming: {@code stopTenant} = end running activity, keep state; {@code removeTenant} = drop the state and
 * release what it holds (threads, classloaders, engines); {@code clear*} = empty the state but keep the entry.
 */
public interface TenantScoped {

    /** Connector clients and their housekeeping. */
    int ORDER_CONNECTORS = 100;
    /** Notification 2.0 connections, tokens and subscriptions. */
    int ORDER_NOTIFICATIONS = 200;
    /** Mappings, status, deployment map, flow state. */
    int ORDER_MAPPINGS = 300;
    /** Java extensions and their classloaders. */
    int ORDER_EXTENSIONS = 400;
    /** GraalVM engine, sources and context pool. */
    int ORDER_GRAALVM = 500;
    /** Caches and other lookups that hold the tenant's credentials or identities. */
    int ORDER_CACHES = 600;
    /** Credentials, service configuration, representations: removed last, others may still read them. */
    int ORDER_REGISTRY = 900;

    /** Ends running activity (threads, connections) but keeps the state. Also used on application shutdown. */
    default void stopTenant(String tenant) {
    }

    /** Drops the tenant's state and releases what it holds. Runs after {@link #stopTenant} of all beans. */
    void removeTenant(String tenant);
}
