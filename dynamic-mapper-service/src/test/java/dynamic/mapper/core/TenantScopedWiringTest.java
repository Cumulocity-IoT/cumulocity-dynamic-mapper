package dynamic.mapper.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.core.annotation.Order;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.stereotype.Component;

import dynamic.mapper.connector.core.registry.ConnectorRegistry;
import dynamic.mapper.mapping.MappingService;
import dynamic.mapper.notification.NotificationSubscriber;
import java.lang.reflect.Modifier;

/**
 * {@link BootstrapService} relies on Spring injecting every {@link TenantScoped} bean in {@code @Order}.
 * A bean that implements the interface but is not a Spring component, or has no order, would be silently skipped
 * or run in an undefined position when a tenant unsubscribes.
 */
class TenantScopedWiringTest {

    private static List<Class<?>> implementers() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(TenantScoped.class));
        List<Class<?>> result = new ArrayList<>();
        for (BeanDefinition bd : scanner.findCandidateComponents("dynamic.mapper")) {
            Class<?> c = Class.forName(bd.getBeanClassName());
            if (!c.isInterface() && !Modifier.isAbstract(c.getModifiers())
                    && c.getEnclosingClass() == null) {
                result.add(c);
            }
        }
        return result;
    }

    @Test
    void everyImplementerIsASpringComponentWithAnOrder() throws Exception {
        List<Class<?>> classes = implementers();
        assertTrue(classes.size() >= 9, "expected the known implementers, found " + classes);
        for (Class<?> c : classes) {
            assertTrue(AnnotatedElementUtils.hasAnnotation(c, Component.class),
                    c.getSimpleName() + " implements TenantScoped but is not a Spring component");
            assertNotNull(AnnotatedElementUtils.findMergedAnnotation(c, Order.class),
                    c.getSimpleName() + " implements TenantScoped without @Order");
        }
    }

    @Test
    void knownImplementersArePresent() throws Exception {
        Set<String> names = implementers().stream().map(Class::getSimpleName).collect(Collectors.toSet());
        assertTrue(names.containsAll(Set.of("ConnectorRegistry", "NotificationSubscriber", "MappingService",
                "ExtensionInboundRegistry", "ExtensionManager", "GraalVMContextService", "ProcessingModeService",
                "CacheManager", "ServiceRegistry")), "missing implementers, found " + names);
    }

    @Test
    void orderPutsConnectorsFirstAndTheRegistryLast() throws Exception {
        List<Class<?>> sorted = new ArrayList<>(implementers());
        sorted.sort(AnnotationAwareOrderComparator.INSTANCE);

        assertEquals(ConnectorRegistry.class, sorted.get(0));
        assertEquals(NotificationSubscriber.class, sorted.get(1));
        assertEquals(MappingService.class, sorted.get(2));
        assertEquals(ServiceRegistry.class, sorted.get(sorted.size() - 1),
                "credentials and configuration are removed last");
    }

    @Test
    void springSortsInjectedBeansByOrder() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.registerBean("late", LateBean.class);
            ctx.registerBean("early", EarlyBean.class);
            ctx.refresh();
            // the same mechanism BootstrapService's List<TenantScoped> constructor parameter relies on
            List<TenantScoped> injected = ctx.getBeanProvider(TenantScoped.class).orderedStream().toList();
            assertEquals(List.of(EarlyBean.class, LateBean.class),
                    injected.stream().map(Object::getClass).collect(Collectors.toList()));
        }
    }

    @Order(TenantScoped.ORDER_CONNECTORS)
    static class EarlyBean implements TenantScoped {
        @Override
        public void initializeTenant(TenantInitContext ctx) { }
        @Override
        public void removeTenant(String tenant) { }
    }

    @Order(TenantScoped.ORDER_REGISTRY)
    static class LateBean implements TenantScoped {
        @Override
        public void initializeTenant(TenantInitContext ctx) { }
        @Override
        public void removeTenant(String tenant) { }
    }
}
