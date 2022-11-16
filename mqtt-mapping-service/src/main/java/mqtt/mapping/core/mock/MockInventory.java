package mqtt.mapping.core.mock;

import org.springframework.stereotype.Service;

import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class MockInventory {

    public ManagedObjectRepresentation create(ManagedObjectRepresentation mor) {
        return null;
    }

    public ManagedObjectRepresentation update(ManagedObjectRepresentation mor) {
        return null;
    }

}
