package mqtt.mapping.core;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.cumulocity.model.idtype.GId;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.cumulocity.sdk.client.inventory.InventoryApi;
import com.cumulocity.sdk.client.inventory.InventoryFilter;
import com.cumulocity.sdk.client.inventory.ManagedObjectCollection;

import lombok.extern.slf4j.Slf4j;
import mqtt.mapping.core.mock.MockInventory;
import mqtt.mapping.processor.model.ProcessingContext;

@Slf4j
@Service
public class InventoryFacade {

    @Autowired
    private MockInventory inventoryMock;

    @Autowired
    private InventoryApi inventoryApi;

    public ManagedObjectRepresentation create(ManagedObjectRepresentation mor, ProcessingContext<?> context) {
        if (context == null || context.isSendPayload()) {
            return inventoryApi.create(mor);
        } else {
            return inventoryMock.create(mor);
        }
    }

    public ManagedObjectRepresentation get(GId id) {
        return inventoryApi.get(id);
    }

    public void delete(GId id) {
        inventoryApi.delete(id);
    }

    public ManagedObjectRepresentation update(ManagedObjectRepresentation mor, ProcessingContext<?> context) {
        if (context == null || context.isSendPayload()) {
            return inventoryApi.update(mor);
        } else {
            return inventoryMock.update(mor);
        }
    }

    public ManagedObjectCollection getManagedObjectsByFilter(InventoryFilter inventoryFilter) {
        return inventoryApi.getManagedObjectsByFilter(inventoryFilter);
    }
}
