package mqtt.mapping.rest;

import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;

import lombok.extern.slf4j.Slf4j;
import mqtt.mapping.core.C8YAgent;
import mqtt.mapping.model.C8YAPISubscription;
import mqtt.mapping.model.Device;
import mqtt.mapping.notification.C8YAPISubscriber;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import javax.validation.Valid;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@RestController
public class MQTTOutboundMappingRestController {

    @Autowired
    C8YAgent c8yAgent;

    @Autowired
    C8YAPISubscriber c8yApiSubscriber;

    @Value("${APP.outputMappingEnabled}")
    private boolean outputMappingEnabled;

    @RequestMapping(value = "/subscription", method = RequestMethod.POST, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> subscriptionCreate(@Valid @RequestBody C8YAPISubscription subscription) {
        if (!outputMappingEnabled)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Output Mapping is disabled!");
        try {
            for (Device device : subscription.getDevices()) {
                ManagedObjectRepresentation mor = c8yAgent.getManagedObjectForId(device.getId());
                if (mor != null) {
                    c8yApiSubscriber.subscribeDevice(mor, subscription.getApi());
                } else {
                    log.warn("Could not subscribe device with id "+device.getId()+ ". Device does not exists!" );
                    //throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Managed Object with id "+device.getId()+ " not found" );
                }
            }
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getLocalizedMessage());
        }
        return ResponseEntity.ok().build();
    }

    @RequestMapping(value = "/subscription", method = RequestMethod.PUT, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> subscriptionUpdate(@Valid @RequestBody C8YAPISubscription subscription) {
        if (!outputMappingEnabled)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Output Mapping is disabled!");
        try {
            //List<NotificationSubscriptionRepresentation> deviceSubscriptions = c8yApiSubscriber.getNotificationSubscriptions(null, null).get();
            C8YAPISubscription c8ySubscription = c8yApiSubscriber.getDeviceSubscriptions(null, null);
            //3 cases -
            // 1. Device exists in subscription and active subscription --> Do nothing
            // 2. Device exists in subscription and does not have an active subscription --> create subscription
            // 3. Device exists not in subscription and does have an active subscription --> delete subscription
            List<Device> toBeRemovedSub = new ArrayList<>();
            List<Device> toBeCreatedSub = new ArrayList<>();

            c8ySubscription.getDevices().forEach(device -> toBeRemovedSub.add(device));
            subscription.getDevices().forEach(device -> toBeCreatedSub.add(device));


            subscription.getDevices().forEach(device -> toBeRemovedSub.removeIf(x -> x.getId().equals(device.getId())));
            c8ySubscription.getDevices().forEach(entity -> toBeCreatedSub.removeIf(x -> x.getId().equals(entity.getId())));

            for (Device device : toBeCreatedSub) {
                ManagedObjectRepresentation mor = c8yAgent.getManagedObjectForId(device.getId());
                if (mor != null) {
                    try {
                        c8yApiSubscriber.subscribeDevice(mor, subscription.getApi());
                    } catch (Exception e) {
                        throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getLocalizedMessage());
                    }
                } else {
                    log.warn("Could not subscribe device with id "+device.getId()+ ". Device does not exists!" );
                }
            }

            for (Device device : toBeRemovedSub) {
                ManagedObjectRepresentation mor = c8yAgent.getManagedObjectForId(device.getId());
                if (mor != null) {
                    try {
                        c8yApiSubscriber.unsubscribeDevice(mor);
                    } catch (Exception e) {
                        throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getLocalizedMessage());
                    }
                } else {
                    log.warn("Could not unsubscribe device with id "+device.getId()+ ". Device does not exists!" );
                }
            }

        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getLocalizedMessage());
        }
        return ResponseEntity.ok().build();
    }

    @RequestMapping(value = "/subscriptions", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<C8YAPISubscription> subscriptionsGet(@RequestParam(required = false) String deviceId, @RequestParam(required = false) String subscriptionName) {
        if (!outputMappingEnabled)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Output Mapping is disabled!");
        try {
            C8YAPISubscription c8YAPISubscription = c8yApiSubscriber.getDeviceSubscriptions(deviceId, subscriptionName);
            return ResponseEntity.ok(c8YAPISubscription);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getLocalizedMessage());
        }
    }

    @RequestMapping(value = "/subscription/{deviceId}", method = RequestMethod.DELETE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> subscriptionDelete(@PathVariable String deviceId) {
        if (!outputMappingEnabled)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Output Mapping is disabled!");
        try {
                ManagedObjectRepresentation mor = c8yAgent.getManagedObjectForId(deviceId);
                if (mor != null) {
                    c8yApiSubscriber.unsubscribeDevice(mor);
                } else {
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Could not delete subscription for device with id "+deviceId+ ". Device not found" );
                }
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getLocalizedMessage());
        }
        return ResponseEntity.ok().build();
    }
}
