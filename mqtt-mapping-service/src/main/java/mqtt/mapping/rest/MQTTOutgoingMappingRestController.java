package mqtt.mapping.rest;

import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import lombok.extern.slf4j.Slf4j;
import mqtt.mapping.core.C8YAgent;
import mqtt.mapping.model.C8YAPISubscription;
import mqtt.mapping.model.Device;
import mqtt.mapping.notification.C8YAPISubscriber;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import javax.validation.Valid;
import java.util.List;

@Slf4j
@RestController
public class MQTTOutgoingMappingRestController {

    @Autowired
    C8YAgent c8yAgent;

    @Autowired
    C8YAPISubscriber c8yApiSubscriber;

    @RequestMapping(value = "/subscription/create", method = RequestMethod.POST, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> subscriptionCreate(@Valid @RequestBody C8YAPISubscription subscription) {
        try {
            for (Device device : subscription.getDevices()) {
                ManagedObjectRepresentation mor = c8yAgent.getManagedObjectForId(device.getId());
                if (mor != null) {
                    c8yApiSubscriber.subscribeDevice(mor, subscription.getApi());
                } else {
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Managed Object with id "+device.getId()+ " not found" );
                }
            }
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getLocalizedMessage());
        }
        return ResponseEntity.ok().build();
    }

    @RequestMapping(value = "/subscription/delete", method = RequestMethod.POST, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> subscriptionDelete(@Valid @RequestBody C8YAPISubscription subscription) {
        try {
            for (Device device : subscription.getDevices()) {
                ManagedObjectRepresentation mor = c8yAgent.getManagedObjectForId(device.getId());
                if (mor != null) {
                    c8yApiSubscriber.unsubscribeDevice(mor, subscription.getApi());
                } else {
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Managed Object with id "+device.getId()+ " not found" );
                }
            }
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getLocalizedMessage());
        }
        return ResponseEntity.ok().build();
    }
}
