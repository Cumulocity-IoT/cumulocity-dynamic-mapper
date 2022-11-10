package mqtt.mapping.core.mock;

import org.springframework.stereotype.Service;

import com.cumulocity.model.ID;
import com.cumulocity.rest.representation.identity.ExternalIDRepresentation;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class MockIdentity {

    public ExternalIDRepresentation create(ExternalIDRepresentation externalID) {
        return null;
    }

    public ExternalIDRepresentation getExternalId(ID externalID) {
        return null;
    }

}
