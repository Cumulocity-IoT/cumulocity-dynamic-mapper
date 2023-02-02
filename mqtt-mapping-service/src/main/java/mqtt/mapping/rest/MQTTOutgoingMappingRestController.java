package mqtt.mapping.rest;

import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import lombok.extern.slf4j.Slf4j;
import mqtt.mapping.configuration.ConfigurationConnection;
import mqtt.mapping.core.C8YAgent;
import mqtt.mapping.model.Device;
import mqtt.mapping.model.DeviceList;
import mqtt.mapping.model.Mapping;
import mqtt.mapping.notification.OperationSubscriber;
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
    OperationSubscriber operationSubscriber;

    @RequestMapping(value = "/registerDevicesForOperations", method = RequestMethod.POST, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> registerDevice(@Valid @RequestBody DeviceList devices) {
        try {
            for (Device device : devices.getDeviceList()) {
                ManagedObjectRepresentation mor = c8yAgent.getManagedObjectForId(device.getId());
                operationSubscriber.subscribeDevice(mor);
            }
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getLocalizedMessage());
        }
        return ResponseEntity.ok().build();
    }
}
