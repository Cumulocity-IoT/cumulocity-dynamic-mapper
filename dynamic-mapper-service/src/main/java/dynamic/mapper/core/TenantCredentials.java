package dynamic.mapper.core;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Marks tenants whose platform credentials are already revoked.
 *
 * <p>When a tenant unsubscribes the microservice, the platform deletes its service user BEFORE the
 * microservice learns about it. Every Cumulocity call made during the tenant cleanup then fails with
 * {@code 401 Bad credentials}. {@link BootstrapService} marks the tenant at the start of the unsubscribe
 * cleanup and clears the mark on the next subscribe; cleanup code that only talks to the platform
 * (status events, token unsubscribes, inventory updates) checks {@link #isRevoked} and skips the call.
 */
public final class TenantCredentials {

    private static final Set<String> REVOKED = ConcurrentHashMap.newKeySet();

    private TenantCredentials() {
    }

    public static void markRevoked(String tenant) {
        if (tenant != null) {
            REVOKED.add(tenant);
        }
    }

    public static void clearRevoked(String tenant) {
        if (tenant != null) {
            REVOKED.remove(tenant);
        }
    }

    /** Test support: static state must not leak between tests. */
    static void clearAll() {
        REVOKED.clear();
    }

    public static boolean isRevoked(String tenant) {
        return tenant != null && REVOKED.contains(tenant);
    }
}
