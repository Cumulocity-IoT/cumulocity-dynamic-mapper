package dynamic.mapper.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.withSettings;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;

import dynamic.mapper.configuration.ExtensionConfiguration;
import dynamic.mapper.core.facade.InventoryFacade;
import dynamic.mapper.mapping.MappingRepository;
import dynamic.mapper.mapping.MappingService;
import dynamic.mapper.mapping.MappingValidator;
import dynamic.mapper.mapping.DeviceToClientMapService;
import dynamic.mapper.mapping.cache.MappingCacheManager;
import dynamic.mapper.mapping.deployment.DeploymentMapService;
import dynamic.mapper.mapping.resolver.MappingResolverService;
import dynamic.mapper.mapping.status.MappingStatusService;
import dynamic.mapper.mapping.MappingVersionService;
import dynamic.mapper.notification.NotificationSubscriber;
import dynamic.mapper.notification.OutboundDispatcherRegistry;
import dynamic.mapper.notification.service.NotificationConnectionManager;
import dynamic.mapper.notification.service.SubscriptionManager;
import dynamic.mapper.notification.service.TokenManager;
import dynamic.mapper.notification.service.DeviceDiscoveryService;
import dynamic.mapper.processor.flow.FlowStateStore;
import java.io.IOException;

/** {@code removeTenant} of the individual {@link TenantScoped} beans. */
class TenantRemovalImplementationsTest {

    private static final String TENANT = "t1";

    private static void set(Object target, String field, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    @SuppressWarnings("unchecked")
    private static <T> T get(Object target, String field) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        return (T) f.get(target);
    }

    @Test
    void serviceRegistryRemovesCredentialsAndConfigurationLast() throws Exception {
        ServiceRegistry registry = mock(ServiceRegistry.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
        TenantRegistry tenantRegistry = mock(TenantRegistry.class);
        set(registry, "tenantRegistry", tenantRegistry);

        registry.removeTenant(TENANT);

        InOrder order = inOrder(tenantRegistry);
        order.verify(tenantRegistry).clearExternalIdCache(TENANT);
        order.verify(tenantRegistry).removeDeviceToClientState(TENANT);
        order.verify(tenantRegistry).removeMapperServiceRepresentation(TENANT);
        order.verify(tenantRegistry).removeServiceConfiguration(TENANT);
        order.verify(tenantRegistry).removeMicroserviceCredentials(TENANT);
    }

    @Test
    void cacheManagerRemovesAllThreeCaches() {
        CacheManager cacheManager = new CacheManager(null, null);
        cacheManager.initializeInboundExternalIdCache(TENANT, 10);
        cacheManager.initializeOutboundExternalIdCache(TENANT, 10);
        cacheManager.initializeInventoryCache(TENANT, 10);
        cacheManager.initializeInboundExternalIdCache("t2", 10);

        cacheManager.removeTenant(TENANT);

        assertNull(cacheManager.getInboundExternalIdCache(TENANT));
        assertNull(cacheManager.getOutboundExternalIdCache(TENANT));
        assertNull(cacheManager.getInventoryCache(TENANT));
        assertNotNull(cacheManager.getInboundExternalIdCache("t2"));
        cacheManager.removeTenant("t2");
    }

    @Test
    void notificationSubscriberUnsubscribesTokensBeforeDroppingTheirReferences() throws Exception {
        NotificationSubscriber subscriber = mock(NotificationSubscriber.class,
                withSettings().defaultAnswer(CALLS_REAL_METHODS));
        TokenManager tokenManager = mock(TokenManager.class);
        NotificationConnectionManager connectionManager = mock(NotificationConnectionManager.class);
        SubscriptionManager subscriptionManager = mock(SubscriptionManager.class);
        DeviceDiscoveryService discovery = mock(DeviceDiscoveryService.class);
        set(subscriber, "tokenManager", tokenManager);
        set(subscriber, "connectionManager", connectionManager);
        set(subscriber, "subscriptionManager", subscriptionManager);
        set(subscriber, "deviceDiscoveryService", discovery);
        OutboundDispatcherRegistry dispatcherRegistry = mock(OutboundDispatcherRegistry.class);
        set(subscriber, "dispatcherRegistry", dispatcherRegistry);

        subscriber.removeTenant(TENANT);

        verify(dispatcherRegistry).removeTenant(TENANT);
        InOrder order = inOrder(tokenManager);
        order.verify(tokenManager).unsubscribeDeviceSubscriber(TENANT);
        order.verify(tokenManager).unsubscribeDeviceGroupSubscriber(TENANT);
        order.verify(tokenManager).removeTenant(TENANT); // must come after both: they read the tokens
        verify(connectionManager).removeTenant(TENANT);
        verify(subscriptionManager).removeTenant(TENANT);
        verify(discovery).removeTenant(TENANT);
    }

    @Test
    void notificationSubscriberStillDropsItsStateWhenAnUnsubscribeFails() throws Exception {
        NotificationSubscriber subscriber = mock(NotificationSubscriber.class,
                withSettings().defaultAnswer(CALLS_REAL_METHODS));
        TokenManager tokenManager = mock(TokenManager.class);
        NotificationConnectionManager connectionManager = mock(NotificationConnectionManager.class);
        doThrow(new IllegalStateException("platform down")).when(tokenManager).unsubscribeDeviceSubscriber(TENANT);
        set(subscriber, "tokenManager", tokenManager);
        set(subscriber, "connectionManager", connectionManager);
        set(subscriber, "subscriptionManager", mock(SubscriptionManager.class));
        set(subscriber, "deviceDiscoveryService", mock(DeviceDiscoveryService.class));
        set(subscriber, "dispatcherRegistry", mock(OutboundDispatcherRegistry.class));

        subscriber.removeTenant(TENANT);

        verify(tokenManager).unsubscribeDeviceGroupSubscriber(TENANT);
        verify(tokenManager).removeTenant(TENANT);
        verify(connectionManager).removeTenant(TENANT);
    }

    @Test
    void mappingServiceRemovesAllItsSubServicesState() {
        MappingCacheManager cache = mock(MappingCacheManager.class);
        MappingStatusService status = mock(MappingStatusService.class);
        DeploymentMapService deployment = mock(DeploymentMapService.class);
        FlowStateStore flow = mock(FlowStateStore.class);
        MappingRepository repository = mock(MappingRepository.class);
        MappingService service = new MappingService(mock(InventoryFacade.class), repository, cache, status,
                mock(MappingResolverService.class), deployment, mock(DeviceToClientMapService.class),
                mock(ServiceRegistry.class), mock(MicroserviceSubscriptionsService.class),
                mock(MappingValidator.class), flow, mock(MappingVersionService.class));
        service.initializeTenant(TENANT);

        service.removeTenant(TENANT);

        verify(cache).removeTenant(TENANT);
        verify(status).removeTenant(TENANT);
        verify(deployment).removeTenant(TENANT);
        verify(flow).removeTenant(TENANT);
        verify(repository).clearReportedWarnings(TENANT);
    }

    @Test
    void extensionManagerClosesTheTenantsClassLoadersAndDropsItsLock() throws Exception {
        ExtensionManager manager = new ExtensionManager(null, null, new ExtensionInboundRegistry(),
                new ExtensionConfiguration());
        AtomicBoolean closed = new AtomicBoolean();
        URLClassLoader loader = new URLClassLoader(new URL[0]) {
            @Override
            public void close() throws IOException {
                closed.set(true);
                super.close();
            }
        };
        Class<?> holderClass = Class.forName("dynamic.mapper.core.ExtensionManager$ExtensionClassLoaderHolder");
        Constructor<?> ctor = holderClass.getDeclaredConstructor(URLClassLoader.class, File.class);
        ctor.setAccessible(true);
        Map<String, Object> loaders = new ConcurrentHashMap<>();
        loaders.put("ext", ctor.newInstance(loader, null));
        Map<String, Map<String, Object>> byTenant = get(manager, "tenantExtensionClassLoaders");
        byTenant.put(TENANT, loaders);
        Map<String, Object> locks = get(manager, "tenantLocks");
        locks.put(TENANT, new Object());

        manager.removeTenant(TENANT);

        assertTrue(closed.get(), "classloader must be closed on unsubscribe");
        assertFalse(byTenant.containsKey(TENANT));
        assertFalse(locks.containsKey(TENANT));
    }
}
