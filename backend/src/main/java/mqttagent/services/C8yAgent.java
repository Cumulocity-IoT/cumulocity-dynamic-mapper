package mqttagent.services;

import c8y.IsDevice;
import com.cumulocity.microservice.subscription.model.MicroserviceSubscriptionAddedEvent;
import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;
import com.cumulocity.model.Agent;
import com.cumulocity.model.ID;
import com.cumulocity.model.measurement.MeasurementValue;
import com.cumulocity.rest.representation.alarm.AlarmRepresentation;
import com.cumulocity.rest.representation.event.EventRepresentation;
import com.cumulocity.rest.representation.identity.ExternalIDRepresentation;
import com.cumulocity.rest.representation.inventory.ManagedObjectReferenceRepresentation;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.cumulocity.rest.representation.measurement.MeasurementRepresentation;
import com.cumulocity.sdk.client.SDKException;
import com.cumulocity.sdk.client.alarm.AlarmApi;
import com.cumulocity.sdk.client.devicecontrol.DeviceControlApi;
import com.cumulocity.sdk.client.event.EventApi;
import com.cumulocity.sdk.client.identity.IdentityApi;
import com.cumulocity.sdk.client.inventory.InventoryApi;
import com.cumulocity.sdk.client.measurement.MeasurementApi;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.TimeZone;

@Service
public class C8yAgent {

    private final Logger logger = LoggerFactory.getLogger(C8yAgent.class);

    @Autowired
    private EventApi eventApi;

    @Autowired
    private InventoryApi inventoryApi;

    @Autowired
    private IdentityApi identityApi;

    @Autowired
    private MeasurementApi measurementApi;

    @Autowired
    private AlarmApi alarmApi;

    @Autowired
    private DeviceControlApi deviceControlApi;

    @Autowired
    private MicroserviceSubscriptionsService subscriptionsService;

    @Autowired
    private MQTTClient mqttClient;

    private ManagedObjectRepresentation agentMOR;

    private final String AGENT_ID = "MQTT_AGENT";
    private final String AGENT_NAME = "Generic MQTT Agent";
    public String tenant = null;

    @EventListener
    public void init(MicroserviceSubscriptionAddedEvent event) {
        this.tenant = event.getCredentials().getTenant();
        logger.info("Event received for Tenant {}", tenant);
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"));

        /* Connecting to Cumulocity */
        subscriptionsService.runForTenant(tenant, () -> {
            ExternalIDRepresentation agentIdRep = null;
            try {
                agentIdRep = getExternalId(AGENT_ID, null);
            } catch (Exception e) {
                logger.error(e.getMessage());
            }
            if (agentIdRep != null) {
                logger.info("Agent with ID {} already exists {}", AGENT_ID, agentIdRep);
                this.agentMOR = agentIdRep.getManagedObject();
            } else {
                ID id = new ID();
                id.setType("c8y_Serial");
                id.setValue(AGENT_ID);

                ManagedObjectRepresentation agent = new ManagedObjectRepresentation();
                agent.setName(AGENT_NAME);
                agent.set(new Agent());
                agent.set(new IsDevice());
                this.agentMOR = inventoryApi.create(agent);
                logger.info("Agent has been created with ID {}", agentMOR.getId());
                ExternalIDRepresentation externalAgentId = new ExternalIDRepresentation();
                externalAgentId.setType("c8y_Serial");
                externalAgentId.setExternalId(AGENT_ID);
                externalAgentId.setManagedObject(this.agentMOR);

                try {
                    identityApi.create(externalAgentId);
                } catch (SDKException e) {
                    logger.error(e.getMessage());
                }
                logger.info("ExternalId created: {}", externalAgentId.getExternalId());
            }
        });
        /* Connecting to MQTT Client */
        /* TODO When no tenant options provided the microservice will not start unless unsubscribed + subscribed
        We should add logic to fetch tenant options regulary e.g. all 60 seconds until they could be retrieved or on REST Request when configuration is set
        */
        try {
            mqttClient.init();
            mqttClient.reconnect();
            mqttClient.startReporting();
            /* Uncomment this if you want to subscribe on start on "#" */
            mqttClient.subscribe("#", null);
            mqttClient.subscribe("$SYS/#", null);
        } catch (Exception e) {
            logger.error("Error on MQTT Connection: ", e);
            mqttClient.reconnect();
        }
    }

    @PreDestroy
    private void stop() {
        if (mqttClient != null) {
            mqttClient.disconnect();
        }
    }

    public String getTenant() {
        return this.tenant;
    }

    public MeasurementRepresentation storeMeasurement(ManagedObjectRepresentation mor,
            String eventType, DateTime timestamp, Map<String, Object> attributes, Map<String, Object> fragments)
            throws SDKException {

        MeasurementRepresentation measure = new MeasurementRepresentation();
        measure.setAttrs(attributes);
        measure.setSource(mor);
        measure.setType(eventType);
        measure.setDateTime(timestamp);

        // Step 3: Iterate over all fragments provided
        Iterator<Map.Entry<String, Object>> fragmentKeys = fragments.entrySet().iterator();
        while (fragmentKeys.hasNext()) {
            Map.Entry<String, Object> currentFragment = fragmentKeys.next();
            measure.set(currentFragment.getValue(), currentFragment.getKey());

        }
        measure = measurementApi.create(measure);
        logger.info("Created " + eventType + " Measurement: " + measure.getSelf() + " for device: " + mor.getSelf());
        return measure;
    }

    public ExternalIDRepresentation getExternalId(String externalId, String type) {
        if (type == null) {
            type = "c8y_Serial";
        }
        ID id = new ID();
        id.setType(type);
        id.setValue(externalId);
        ExternalIDRepresentation extId = null;
        try {
            extId = identityApi.getExternalId(id);
        } catch (SDKException e) {
            logger.info("External ID {} not found", externalId);
        }
        return extId;
    }

    public void unregisterDevice(String externalId) {
        ExternalIDRepresentation retExternalId = getExternalId(externalId, null);
        if (retExternalId != null) {
            inventoryApi.delete(retExternalId.getManagedObject().getId());
            identityApi.deleteExternalId(retExternalId);
        }
    }

    public void storeEvent(EventRepresentation event) {
        eventApi.createAsync(event).get();
    }

    public ManagedObjectRepresentation getAgentMOR() {
        return agentMOR;
    }

    public void createIdentity(ExternalIDRepresentation externalIDGid) {
        identityApi.create(externalIDGid);

    }

    public ManagedObjectRepresentation createMO(ManagedObjectRepresentation mor) {
        return inventoryApi.create(mor);
    }

    public void addChildDevice(ManagedObjectReferenceRepresentation child2Ref) {
        inventoryApi.getManagedObjectApi(agentMOR.getId()).addChildDevice(child2Ref);
    }

    public void addChildDevice(ManagedObjectReferenceRepresentation child2Ref,
            ManagedObjectRepresentation parent) {
        inventoryApi.getManagedObjectApi(parent.getId()).addChildAssets(child2Ref);
    }

    public MeasurementRepresentation createMeasurement(String name, String type, ManagedObjectRepresentation mor,
            DateTime dateTime, HashMap<String, MeasurementValue> mvMap) {
        try {
            MeasurementRepresentation measurementRepresentation = new MeasurementRepresentation();
            measurementRepresentation.set(mvMap, name);
            measurementRepresentation.setType(type);
            measurementRepresentation.setSource(mor);
            measurementRepresentation.setDateTime(dateTime);
            logger.debug("Creating Measurement {}", measurementRepresentation);
            return measurementApi.create(measurementRepresentation);
        } catch (SDKException e) {
            logger.error("Error creating Measurement", e);
            return null;
        }
    }

    public AlarmRepresentation createAlarm(String severity, String message, String type, DateTime alarmTime,
            ManagedObjectRepresentation parentMor) {
        AlarmRepresentation alarmRep = new AlarmRepresentation();
        alarmRep.setSeverity(severity);
        alarmRep.setSource(parentMor);
        alarmRep.setText(message);
        alarmRep.setDateTime(alarmTime);
        alarmRep.setStatus("ACTIVE");
        alarmRep.setType(type);

        alarmRep = this.alarmApi.create(alarmRep);
        return alarmRep;
    }

    public void createEvent(String message, String type, DateTime eventTime, ManagedObjectRepresentation parentMor) {
        EventRepresentation eventRep = new EventRepresentation();
        eventRep.setSource(parentMor != null ? parentMor : agentMOR);
        eventRep.setText(message);
        eventRep.setDateTime(eventTime);
        eventRep.setType(type);
        this.eventApi.createAsync(eventRep);
    }
}
