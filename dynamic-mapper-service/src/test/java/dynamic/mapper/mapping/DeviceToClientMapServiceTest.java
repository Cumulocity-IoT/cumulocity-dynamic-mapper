package dynamic.mapper.mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;
import com.cumulocity.model.idtype.GId;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import dynamic.mapper.core.ServiceRegistry;
import dynamic.mapper.core.facade.InventoryFacade;
import dynamic.mapper.model.device.DeviceToClientMapRepresentation;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class DeviceToClientMapServiceTest {

    private static final String TENANT = "t12345";
    private static final String MAP_ID = "42";
    private static final String FRAGMENT = DeviceToClientMapRepresentation.DEVICE_TO_CLIENT_MAP_FRAGMENT;

    private InventoryFacade inventoryApi;
    private ServiceRegistry serviceRegistry;
    private DeviceToClientMapService service;

    @BeforeEach
    void setUp() {
        inventoryApi = mock(InventoryFacade.class);
        serviceRegistry = mock(ServiceRegistry.class);
        MicroserviceSubscriptionsService subscriptions = mock(MicroserviceSubscriptionsService.class);
        Mockito.doAnswer(inv -> {
            ((Runnable) inv.getArgument(1)).run();
            return null;
        }).when(subscriptions).runForTenant(eq(TENANT), any(Runnable.class));
        when(serviceRegistry.getDeviceToClientMapId(TENANT)).thenReturn(MAP_ID);
        service = new DeviceToClientMapService(inventoryApi, serviceRegistry, subscriptions);
    }

    private void stored(Map<String, String> persisted) {
        ManagedObjectRepresentation mor = new ManagedObjectRepresentation();
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(FRAGMENT, persisted);
        mor.setAttrs(attrs);
        when(inventoryApi.get(GId.asGId(MAP_ID), false)).thenReturn(mor);
    }

    @Test
    void skipsWriteWhenStoredMapIsUnchanged() {
        stored(Map.of("1", "clientA"));
        when(serviceRegistry.getAllClientRelations(TENANT)).thenReturn(Map.of("1", "clientA"));

        service.sendToInventory(TENANT);

        verify(inventoryApi, never()).update(any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void sendsRemovedDevicesAsNullSoTheInventoryMergeDropsThem() {
        stored(Map.of("1", "clientA", "2", "clientB"));
        when(serviceRegistry.getAllClientRelations(TENANT)).thenReturn(Map.of("1", "clientA"));

        service.sendToInventory(TENANT);

        ArgumentCaptor<ManagedObjectRepresentation> captor = ArgumentCaptor.forClass(ManagedObjectRepresentation.class);
        verify(inventoryApi).update(captor.capture(), eq(false));
        Map<String, Object> sent = (Map<String, Object>) captor.getValue().getAttrs().get(FRAGMENT);
        assertEquals("clientA", sent.get("1"));
        assertTrue(sent.containsKey("2"));
        assertNull(sent.get("2"));
    }

    @Test
    void skipsWhenMapManagedObjectIsNotInitialized() {
        when(serviceRegistry.getDeviceToClientMapId(TENANT)).thenReturn(null);

        service.sendToInventory(TENANT);

        verify(inventoryApi, never()).update(any(), any());
    }
}
