package mqtt.mapping.core;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.cumulocity.model.ID;
import com.cumulocity.rest.representation.identity.ExternalIDRepresentation;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.cumulocity.sdk.client.identity.IdentityApi;

import lombok.extern.slf4j.Slf4j;
import mqtt.mapping.core.mock.MockIdentity;
import mqtt.mapping.processor.ProcessingContext;

@Slf4j
@Service
public class IdentityFacade {

    @Autowired
    private MockIdentity identityMock;

    @Autowired
    private IdentityApi identityApi;

    public ExternalIDRepresentation create(ManagedObjectRepresentation mor, ID id, ProcessingContext<?> context) {
        ExternalIDRepresentation externalIDRepresentation = new ExternalIDRepresentation();
        externalIDRepresentation.setType(id.getType());
        externalIDRepresentation.setExternalId(id.getValue());
        externalIDRepresentation.setManagedObject(mor);
        if (context == null || context.isSendPayload()) {
            return identityApi.create(externalIDRepresentation);
        } else {
            return identityMock.create(externalIDRepresentation);
        }
    }

    public ExternalIDRepresentation getExternalId(ID externalID, ProcessingContext<?> context) {
        if (context == null || context.isSendPayload()) {
            return identityApi.getExternalId(externalID);
        } else {
            return identityMock.getExternalId(externalID);
        }
    }

}
