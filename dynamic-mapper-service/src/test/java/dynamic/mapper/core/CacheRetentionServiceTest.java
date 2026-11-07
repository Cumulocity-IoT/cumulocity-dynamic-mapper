package dynamic.mapper.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.configuration.ServiceConfigurationService;
import dynamic.mapper.processor.flow.FlowStateStore;

class CacheRetentionServiceTest {

    private static final List<String> RETENTION_MAPS = List.of("inboundExternalIdRetentionStart",
            "outboundExternalIdRetentionStart", "inventoryRetentionStart");

    private final CacheManager cacheManager = mock(CacheManager.class);
    private final ServiceConfigurationService configService = mock(ServiceConfigurationService.class);
    private final CacheRetentionService service = new CacheRetentionService(cacheManager, configService,
            mock(MicroserviceSubscriptionsService.class), mock(FlowStateStore.class));

    @SuppressWarnings("unchecked")
    private Map<String, Instant> retentionMap(String field) throws Exception {
        Field f = CacheRetentionService.class.getDeclaredField(field);
        f.setAccessible(true);
        return (Map<String, Instant>) f.get(service);
    }

    @Test
    void removeTenantForgetsTheRetentionTimestampsOfThatTenantOnly() throws Exception {
        for (String field : RETENTION_MAPS) {
            retentionMap(field).put("t1", Instant.now());
            retentionMap(field).put("t2", Instant.now());
        }

        service.removeTenant("t1");

        for (String field : RETENTION_MAPS) {
            assertFalse(retentionMap(field).containsKey("t1"), field);
            assertTrue(retentionMap(field).containsKey("t2"), field);
        }
    }

    @Test
    void initializeTenantStartsTheRetentionClockOfAllCaches() throws Exception {
        service.initializeTenant(new TenantInitContext("t1", null, null, null, null));

        for (String field : RETENTION_MAPS) {
            assertTrue(retentionMap(field).containsKey("t1"), field);
        }
    }

    @Test
    void clearsOnlyTheCacheWhoseRetentionHasElapsed() throws Exception {
        ServiceConfiguration config = new ServiceConfiguration();
        config.setInboundExternalIdCacheRetention(1);
        config.setOutboundExternalIdCacheRetention(1);
        config.setInventoryCacheRetention(0); // disabled
        when(configService.getServiceConfiguration("t1")).thenReturn(config);
        when(cacheManager.getSizeInboundExternalIdCache("t1")).thenReturn(7);
        // inbound is two days old, outbound is fresh, inventory has retention 0
        retentionMap("inboundExternalIdRetentionStart").put("t1", Instant.now().minus(2, ChronoUnit.DAYS));
        retentionMap("outboundExternalIdRetentionStart").put("t1", Instant.now());
        retentionMap("inventoryRetentionStart").put("t1", Instant.now().minus(30, ChronoUnit.DAYS));

        service.cleanupCachesForTenant("t1");

        verify(cacheManager).clearInboundExternalIdCache("t1", false, 7);
        verify(cacheManager, never()).clearOutboundExternalIdCache(anyString(), eq(false), anyInt());
        verify(cacheManager, never()).clearInventoryCache(anyString(), eq(false), anyInt());
        assertTrue(retentionMap("inboundExternalIdRetentionStart").get("t1")
                .isAfter(Instant.now().minus(1, ChronoUnit.MINUTES)), "clock restarts after clearing");
    }
}
