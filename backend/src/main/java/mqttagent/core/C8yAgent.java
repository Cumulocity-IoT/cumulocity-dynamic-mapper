package mqttagent.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import javax.annotation.PreDestroy;

import org.joda.time.DateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

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
import com.cumulocity.sdk.client.event.EventApi;
import com.cumulocity.sdk.client.identity.IdentityApi;
import com.cumulocity.sdk.client.inventory.InventoryApi;
import com.cumulocity.sdk.client.inventory.InventoryFilter;
import com.cumulocity.sdk.client.measurement.MeasurementApi;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import c8y.IsDevice;
import lombok.extern.slf4j.Slf4j;
import mqttagent.configuration.ConfigurationService;
import mqttagent.configuration.MQTTConfiguration;
import mqttagent.model.MQTTMapping;
import mqttagent.model.MQTTMappingsRepresentation;
import mqttagent.service.MQTTClient;
import mqttagent.service.MQTTMappingsConverter;

@Slf4j
@Service
public class C8yAgent {

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
    private MicroserviceSubscriptionsService subscriptionsService;

    @Autowired
    private MQTTClient mqttClient;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MQTTMappingsConverter converterService;

    @Autowired
    private ConfigurationService configurationService;

    private ManagedObjectRepresentation agentMOR;

    private final String AGENT_ID = "MQTT_AGENT";
    private final String AGENT_NAME = "Generic MQTT Agent";
    private final String MQTT_MAPPING_TYPE = "c8y_mqttMapping";
    private final String MQTT_MAPPING_FRAGMENT = "c8y_mqttMapping";
    public String tenant = null;

    @EventListener
    public void init(MicroserviceSubscriptionAddedEvent event) {

        // TODO handle what happens if multiple tenants subscribe to this microservice
        tenant = event.getCredentials().getTenant();
        log.info("Event received for Tenant {}", tenant);
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"));

        /* Connecting to Cumulocity */
        subscriptionsService.runForTenant(tenant, () -> {
            // register agent
            ExternalIDRepresentation agentIdRep = null;
            try {
                agentIdRep = getExternalId(AGENT_ID, null);
            } catch (Exception e) {
                log.error(e.getMessage());
            }
            if (agentIdRep != null) {
                log.info("Agent with ID {} already exists {}", AGENT_ID, agentIdRep);
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
                log.info("Agent has been created with ID {}", agentMOR.getId());
                ExternalIDRepresentation externalAgentId = new ExternalIDRepresentation();
                externalAgentId.setType("c8y_Serial");
                externalAgentId.setExternalId(AGENT_ID);
                externalAgentId.setManagedObject(this.agentMOR);

                try {
                    identityApi.create(externalAgentId);
                } catch (SDKException e) {
                    log.error(e.getMessage());
                }
                log.info("ExternalId created: {}", externalAgentId.getExternalId());
            }

            // test if managedObject mqttMapping exists
            InventoryFilter inventoryFilter = new InventoryFilter();
            inventoryFilter.byType(MQTT_MAPPING_TYPE);
            List<ManagedObjectRepresentation> mol = inventoryApi.getManagedObjectsByFilter(inventoryFilter).get()
                    .getManagedObjects();
            if (mol.size() == 0) {
                // create new managedObject
                ManagedObjectRepresentation moMQTTMapping = new ManagedObjectRepresentation();
                moMQTTMapping.setType(MQTT_MAPPING_TYPE);
                // moMQTTMapping.set([], MQTT_MAPPING_FRAGMENT);
                moMQTTMapping.setProperty(MQTT_MAPPING_FRAGMENT, new ArrayList<>());
                moMQTTMapping.setName("MQTT-Mapping");
                moMQTTMapping = inventoryApi.create(moMQTTMapping);
                log.info("Created new MQTT-Mapping: {}, {}", moMQTTMapping.getId().getValue(), moMQTTMapping.getId());
            }
        });
        /* Connecting to MQTT Client */
        /*
         * TODO When no tenant options provided the microservice will not start unless
         * unsubscribed + subscribed
         * We should add logic to fetch tenant options regulary e.g. all 60 seconds
         * until they could be retrieved or on REST Request when configuration is set
         */
        try {
            mqttClient.init();
            mqttClient.reconnect();
            mqttClient.runHouskeeping();
            /* Uncomment this if you want to subscribe on start on "#" */
            mqttClient.subscribe("#", null);
            mqttClient.subscribe("$SYS/#", null);
        } catch (Exception e) {
            log.error("Error on MQTT Connection: ", e);
            mqttClient.reconnect();
        }
    }

    @PreDestroy
    private void stop() {
        if (mqttClient != null) {
            mqttClient.disconnect();
        }
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
        log.info("Created " + eventType + " Measurement: " + measure.getSelf() + " for device: " + mor.getSelf());
        return measure;
    }

    public ExternalIDRepresentation getExternalId(String externalId, String type) {
        if (type == null) {
            type = "c8y_Serial";
        }
        ID id = new ID();
        id.setType(type);
        id.setValue(externalId);
        ExternalIDRepresentation[] extIds = { null };
        subscriptionsService.runForTenant(tenant, () -> {
            try {
                extIds[0] = identityApi.getExternalId(id);
            } catch (SDKException e) {
                log.info("External ID {} not found", externalId);
            }
        });

        return extIds[0];
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
        MeasurementRepresentation mr = new MeasurementRepresentation();
        subscriptionsService.runForTenant(tenant, () -> {
            try {
                mr.set(mvMap, name);
                mr.setType(type);
                mr.setSource(mor);
                mr.setDateTime(dateTime);
                log.debug("Creating Measurement {}", mr);
                MeasurementRepresentation mrn = measurementApi.create(mr);
                mr.setId(mrn.getId());
            } catch (SDKException e) {
                log.error("Error creating Measurement", e);
            }
        });
        return mr;
    }

    public AlarmRepresentation createAlarm(String severity, String message, String type, DateTime alarmTime,
            ManagedObjectRepresentation parentMor) {
        AlarmRepresentation[] ars = { new AlarmRepresentation() };
        subscriptionsService.runForTenant(tenant, () -> {
            ars[0].setSeverity(severity);
            ars[0].setSource(parentMor);
            ars[0].setText(message);
            ars[0].setDateTime(alarmTime);
            ars[0].setStatus("ACTIVE");
            ars[0].setType(type);

            ars[0] = this.alarmApi.create(ars[0]);
        });
        return ars[0];
    }

    public void createEvent(String message, String type, DateTime eventTime, ManagedObjectRepresentation parentMor) {
        EventRepresentation[] ers = { new EventRepresentation() };
        subscriptionsService.runForTenant(tenant, () -> {
            ers[0].setSource(parentMor != null ? parentMor : agentMOR);
            ers[0].setText(message);
            ers[0].setDateTime(eventTime);
            ers[0].setType(type);
            this.eventApi.createAsync(ers[0]);
        });
    }

    public ArrayList<MQTTMapping> getMappings() {
        ArrayList<MQTTMapping> result = new ArrayList<MQTTMapping>();
        subscriptionsService.runForTenant(tenant, () -> {
            InventoryFilter inventoryFilter = new InventoryFilter();
            inventoryFilter.byType(MQTT_MAPPING_TYPE);
            ManagedObjectRepresentation mo = inventoryApi.getManagedObjectsByFilter(inventoryFilter).get()
                    .getManagedObjects().get(0);
            MQTTMappingsRepresentation mqttMo = converterService.asMQTTMappings(mo);
            log.info("Found MQTTMapping {}", mqttMo);
            result.addAll(mqttMo.getC8yMQTTMapping());
            log.info("Found MQTTMapping {}", result.size());
        });
        return result;
    }

    public MQTTConfiguration loadConfiguration() {
        MQTTConfiguration[] results = { null };
        subscriptionsService.runForTenant(tenant, () -> {
            results[0] = configurationService.loadConfiguration();
            log.info("Found configuration {}", results[0]);
        });
        return results[0];
    }

    public void saveConfiguration(MQTTConfiguration configuration) {
        subscriptionsService.runForTenant(tenant, () -> {
            try {
                configurationService.saveConfiguration(configuration);
                log.info("Saved configuration");
            } catch (JsonProcessingException e) {
                log.error("JsonProcessingException configuration {}", e);
                throw new RuntimeException(e);
            }
        });
    }

    public void createC8Y_MEA(String targetAPI, String payload) {
        subscriptionsService.runForTenant(tenant, () -> {
            try {
                if (targetAPI.equals("event")) {
                    EventRepresentation er = objectMapper.readValue(payload, EventRepresentation.class);
                    er = eventApi.create(er);
                    log.info("New event posted: {}", er);
                } else if (targetAPI.equals("alarm")) {
                    AlarmRepresentation ar = objectMapper.readValue(payload, AlarmRepresentation.class);
                    ar = alarmApi.create(ar);
                    log.info("New alarm posted: {}", ar);
                } else if (targetAPI.equals("measurement")) {
                    MeasurementRepresentation mr = objectMapper.readValue(payload, MeasurementRepresentation.class);
                    mr = measurementApi.create(mr);
                    log.info("New measurement posted: {}", mr);
                } else {
                    log.error("Not existing API!");
                }
            } catch (JsonProcessingException e) {
                log.error("Could not map payload: {} {}", targetAPI, payload);
            } catch (SDKException s) {
                log.error("Could not sent payload to c8y: {} {} {}", targetAPI, payload, s);
            }
        });
    }

    public void saveMappings(List<MQTTMapping> mappings) throws JsonProcessingException {
        subscriptionsService.runForTenant(tenant, () -> {
            InventoryFilter inventoryFilter = new InventoryFilter();
            inventoryFilter.byType(MQTT_MAPPING_TYPE);
            ManagedObjectRepresentation mo = inventoryApi.getManagedObjectsByFilter(inventoryFilter).get()
                    .getManagedObjects().get(0);
            ManagedObjectRepresentation moUpdate = new ManagedObjectRepresentation();
            moUpdate.setId(mo.getId());
            moUpdate.setProperty(MQTT_MAPPING_FRAGMENT, mappings);
            inventoryApi.update(moUpdate);
            log.info("Updated MQTTMapping after deletion!");
        });
    }

    public MQTTConfiguration setConfigurationActive(boolean b) {
        MQTTConfiguration[] mcr = { null };
        subscriptionsService.runForTenant(tenant, () -> {
            mcr[0] = configurationService.setConfigurationActive(b);
            log.info("Saved configuration");
        });
        return mcr[0];
    }

    public MQTTMapping getMapping(Long id){
        MQTTMapping[] mr = { null };
        subscriptionsService.runForTenant(tenant, () -> {
            InventoryFilter inventoryFilter = new InventoryFilter();
            inventoryFilter.byType(MQTT_MAPPING_TYPE);
            ManagedObjectRepresentation mo = inventoryApi.getManagedObjectsByFilter(inventoryFilter).get()
                    .getManagedObjects().get(0);
            MQTTMappingsRepresentation mqttMo = converterService.asMQTTMappings(mo);
            log.info("Found MQTTMapping {}", mqttMo);
            mqttMo.getC8yMQTTMapping().forEach((m) ->{
                if ( m.id == id ){
                    mr[0] = m;
                }
            });
            log.info("Found MQTTMapping {}", mr[0]);
        });
        return mr[0];
    }
}
