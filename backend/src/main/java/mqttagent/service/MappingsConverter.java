package mqttagent.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.fasterxml.jackson.databind.ObjectMapper;

import mqttagent.model.MappingsRepresentation;

@Component
public class MappingsConverter {

    @Autowired
    private ObjectMapper objectMapper;
    
    public MappingsRepresentation asMappings(final ManagedObjectRepresentation managedObject) {
        final MappingsRepresentation result = objectMapper.convertValue(managedObject, MappingsRepresentation.class);
        if (result.getDynamicProperties() != null) {
            result.getDynamicProperties().remove("self");
            result.getDynamicProperties().remove("selfDecoded");

            result.getDynamicProperties().remove("creationDateTime");
            result.getDynamicProperties().remove("creationTime");
            result.getDynamicProperties().remove("lastUpdatedDateTime");
            result.getDynamicProperties().remove("lastUpdated");

            result.getDynamicProperties().remove("additionParents");
            result.getDynamicProperties().remove("childAdditions");
            result.getDynamicProperties().remove("assetParents");
            result.getDynamicProperties().remove("deviceParents");
            result.getDynamicProperties().remove("childAssets");
            result.getDynamicProperties().remove("childDevices");

            result.getDynamicProperties().remove("owner");
        }
        return result;
    }
}
