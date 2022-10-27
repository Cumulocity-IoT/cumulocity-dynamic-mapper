package mqtt.mapping.core;

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
import org.svenson.JSONParser;

import com.cumulocity.microservice.subscription.model.MicroserviceSubscriptionAddedEvent;
import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;
import com.cumulocity.model.Agent;
import com.cumulocity.model.ID;
import com.cumulocity.model.JSONBase;
import com.cumulocity.model.measurement.MeasurementValue;
import com.cumulocity.rest.representation.alarm.AlarmRepresentation;
import com.cumulocity.rest.representation.event.EventRepresentation;
import com.cumulocity.rest.representation.identity.ExternalIDRepresentation;
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
import mqtt.mapping.configuration.ConfigurationService;
import mqtt.mapping.configuration.ConnectionConfiguration;
import mqtt.mapping.configuration.ServiceConfiguration;
import mqtt.mapping.model.API;
import mqtt.mapping.model.Mapping;
import mqtt.mapping.model.MappingServiceRepresentation;
import mqtt.mapping.model.MappingStatus;
import mqtt.mapping.model.MappingsRepresentation;
import mqtt.mapping.processor.ProcessingException;
import mqtt.mapping.service.MQTTClient;
import mqtt.mapping.service.ServiceStatus;

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
    private ConfigurationService configurationService;
    
    private MappingServiceRepresentation mappingServiceRepresentation;
    
    private JSONParser jsonParser = JSONBase.getJSONParser();
    
    public String tenant = null;

    @EventListener
    public void initialize(MicroserviceSubscriptionAddedEvent event) {
        tenant = event.getCredentials().getTenant();
        log.info("Event received for Tenant {}", tenant);
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"));

        /* Connecting to Cumulocity */
        subscriptionsService.runForTenant(tenant, () -> {
            // register agent
            ExternalIDRepresentation agentIdRepresentation = null;
            ManagedObjectRepresentation agentRepresentation = null;
            try {
                agentIdRepresentation = getExternalId(MappingServiceRepresentation.AGENT_ID, null);
            } catch (Exception e) {
                log.error(e.getMessage());
            }
            if (agentIdRepresentation != null) {
                log.info("Agent with ID {} already exists {}", MappingServiceRepresentation.AGENT_ID,
                        agentIdRepresentation);
                agentRepresentation = agentIdRepresentation.getManagedObject();
            } else {
                agentRepresentation = new ManagedObjectRepresentation();
                agentRepresentation.setName(MappingServiceRepresentation.AGENT_NAME);
                agentRepresentation.set(new Agent());
                agentRepresentation.set(new IsDevice());
                agentRepresentation.setProperty(MappingServiceRepresentation.MAPPING_STATUS_FRAGMENT, new ArrayList<>());
                agentRepresentation = inventoryApi.create(agentRepresentation);
                log.info("Agent has been created with ID {}", agentRepresentation.getId());
                ExternalIDRepresentation externalAgentId = createExternalID(agentRepresentation,
                        MappingServiceRepresentation.AGENT_ID, "c8y_Serial");
                log.debug("ExternalId created: {}", externalAgentId.getExternalId());
            }
            agentRepresentation = inventoryApi.get(agentRepresentation.getId());
            mappingServiceRepresentation = objectMapper.convertValue(agentRepresentation,
                    MappingServiceRepresentation.class);
            mappingServiceRepresentation.getMappingStatus().forEach(m -> {
                mqttClient.initializeMappingStatus(m);
            });

            // test if managedObject mqttMapping exists
            ExternalIDRepresentation mappingsRepresentationMappingExtId = getExternalId(
                    MappingsRepresentation.MQTT_MAPPING_TYPE, "c8y_Serial");
            if (mappingsRepresentationMappingExtId == null) {
                // create new managedObject
                ManagedObjectRepresentation mor = new ManagedObjectRepresentation();
                mor.setType(MappingsRepresentation.MQTT_MAPPING_TYPE);
                // moMapping.set([], MQTT_MAPPING_FRAGMENT);
                mor.setProperty(MappingsRepresentation.MQTT_MAPPING_FRAGMENT, new ArrayList<>());
                mor.setName("MQTT-Mapping");
                mor = inventoryApi.create(mor);
                createExternalID(mor, MappingsRepresentation.MQTT_MAPPING_TYPE, "c8y_Serial");
                log.info("Created new MQTT-Mapping: {}, {}", mor.getId().getValue(), mor.getId());
            }
        });

        try {
            mqttClient.submitInitialize();
            mqttClient.submitConnect();
            mqttClient.runHouskeeping();
        } catch (Exception e) {
            log.error("Error on MQTT Connection: ", e);
            mqttClient.submitConnect();
        }
    }

    @PreDestroy
    private void stop() {
        if (mqttClient != null) {
            mqttClient.disconnect();
        }
    }

    public ExternalIDRepresentation createExternalID(ManagedObjectRepresentation mor, String externalId,
            String externalIdType) {
        /* Connecting to Cumulocity */
        ExternalIDRepresentation externalID = new ExternalIDRepresentation();
        externalID.setType(externalIdType);
        externalID.setExternalId(externalId);
        externalID.setManagedObject(mor);
        try {
            externalID = identityApi.create(externalID);
        } catch (SDKException e) {
            log.error(e.getMessage());
        }

        return externalID;

    }

    public MeasurementRepresentation storeMeasurement(ManagedObjectRepresentation mor,
            String eventType, DateTime timestamp, Map<String, Object> attributes, Map<String, Object> fragments)
            throws SDKException {

        MeasurementRepresentation measurementRepresentation = new MeasurementRepresentation();
        measurementRepresentation.setAttrs(attributes);
        measurementRepresentation.setSource(mor);
        measurementRepresentation.setType(eventType);
        measurementRepresentation.setDateTime(timestamp);

        // Step 3: Iterate over all fragments provided
        Iterator<Map.Entry<String, Object>> fragmentKeys = fragments.entrySet().iterator();
        while (fragmentKeys.hasNext()) {
            Map.Entry<String, Object> currentFragment = fragmentKeys.next();
            measurementRepresentation.set(currentFragment.getValue(), currentFragment.getKey());

        }
        measurementRepresentation = measurementApi.create(measurementRepresentation);
        return measurementRepresentation;
    }

    public ExternalIDRepresentation getExternalId(String externalId, String type) {
        if (type == null) {
            type = "c8y_Serial";
        }
        ID id = new ID();
        id.setType(type);
        id.setValue(externalId);
        ExternalIDRepresentation[] externalIDRepresentations = { null };
        subscriptionsService.runForTenant(tenant, () -> {
            try {
                externalIDRepresentations[0] = identityApi.getExternalId(id);
            } catch (SDKException e) {
                log.warn("External ID {} not found", externalId);
            }
        });
        return externalIDRepresentations[0];
    }

    public void unregisterDevice(String externalId) {
        ExternalIDRepresentation externalIDRepresentation = getExternalId(externalId, null);
        if (externalIDRepresentation != null) {
            inventoryApi.delete(externalIDRepresentation.getManagedObject().getId());
            identityApi.deleteExternalId(externalIDRepresentation);
        }
    }

    public void storeEvent(EventRepresentation event) {
        eventApi.createAsync(event).get();
    }

    public MappingServiceRepresentation getAgentMOR() {
        return mappingServiceRepresentation;
    }

    public void createIdentity(ExternalIDRepresentation externalIDGid) {
        identityApi.create(externalIDGid);
    }

    public ManagedObjectRepresentation createMO(ManagedObjectRepresentation mor) {
        return inventoryApi.create(mor);
    }

    public MeasurementRepresentation createMeasurement(String name, String type, ManagedObjectRepresentation mor,
            DateTime dateTime, HashMap<String, MeasurementValue> mvMap) {
        MeasurementRepresentation measurementRepresentation = new MeasurementRepresentation();
        subscriptionsService.runForTenant(tenant, () -> {
            try {
                measurementRepresentation.set(mvMap, name);
                measurementRepresentation.setType(type);
                measurementRepresentation.setSource(mor);
                measurementRepresentation.setDateTime(dateTime);
                log.debug("Creating Measurement {}", measurementRepresentation);
                MeasurementRepresentation mrn = measurementApi.create(measurementRepresentation);
                measurementRepresentation.setId(mrn.getId());
            } catch (SDKException e) {
                log.error("Error creating Measurement", e);
            }
        });
        return measurementRepresentation;
    }

    public AlarmRepresentation createAlarm(String severity, String message, String type, DateTime alarmTime,
            ManagedObjectRepresentation parentMor) {
        AlarmRepresentation[] alarmRepresentations = { new AlarmRepresentation() };
        subscriptionsService.runForTenant(tenant, () -> {
            alarmRepresentations[0].setSeverity(severity);
            alarmRepresentations[0].setSource(parentMor);
            alarmRepresentations[0].setText(message);
            alarmRepresentations[0].setDateTime(alarmTime);
            alarmRepresentations[0].setStatus("ACTIVE");
            alarmRepresentations[0].setType(type);

            alarmRepresentations[0] = this.alarmApi.create(alarmRepresentations[0]);
        });
        return alarmRepresentations[0];
    }

    public void createEvent(String message, String type, DateTime eventTime, ManagedObjectRepresentation parentMor) {
        EventRepresentation[] eventRepresentations = { new EventRepresentation() };
        subscriptionsService.runForTenant(tenant, () -> {
            eventRepresentations[0].setSource(parentMor != null ? parentMor : mappingServiceRepresentation);
            eventRepresentations[0].setText(message);
            eventRepresentations[0].setDateTime(eventTime);
            eventRepresentations[0].setType(type);
            this.eventApi.createAsync(eventRepresentations[0]);
        });
    }

    public ArrayList<Mapping> getMappings() {
        ArrayList<Mapping> result = new ArrayList<Mapping>();
        subscriptionsService.runForTenant(tenant, () -> {
            InventoryFilter inventoryFilter = new InventoryFilter();
            inventoryFilter.byType(MappingsRepresentation.MQTT_MAPPING_TYPE);
            ManagedObjectRepresentation mo = inventoryApi.getManagedObjectsByFilter(inventoryFilter).get()
                    .getManagedObjects().get(0);
            MappingsRepresentation mqttMo = objectMapper.convertValue(mo, MappingsRepresentation.class);
            log.debug("Found mappings: {}", mqttMo);
            result.addAll(mqttMo.getC8yMQTTMapping());
            log.info("Found mappings: {}", result.size());
        });
        return result;
    }

    public ConnectionConfiguration loadConnectionConfiguration() {
        ConnectionConfiguration[] results = { new ConnectionConfiguration() };
        subscriptionsService.runForTenant(tenant, () -> {
            results[0] = configurationService.loadConnectionConfiguration();
            //COMMENT OUT ONLY DEBUG
            //results[0].active = true;
            log.info("Found connection configuration: {}", results[0]);
        });
        return results[0];
    }

    public void saveConnectionConfiguration(ConnectionConfiguration configuration) {
        subscriptionsService.runForTenant(tenant, () -> {
            try {
                configurationService.saveConnectionConfiguration(configuration);
                log.debug("Saved connection configuration");
            } catch (JsonProcessingException e) {
                log.error("JsonProcessingException configuration: {}", e);
                throw new RuntimeException(e);
            }
        });
    }

    public ServiceConfiguration loadServiceConfiguration() {
        ServiceConfiguration[] results = { new ServiceConfiguration(false) };
        subscriptionsService.runForTenant(tenant, () -> {
            results[0] = configurationService.loadServiceConfiguration();
            log.info("Found service configuration: {}", results[0]);
        });
        return results[0];
    }

    public void saveServiceConfiguration(ServiceConfiguration configuration) {
        subscriptionsService.runForTenant(tenant, () -> {
            try {
                configurationService.saveServiceConfiguration(configuration);
                log.debug("Saved service configuration");
            } catch (JsonProcessingException e) {
                log.error("JsonProcessingException configuration: {}", e);
                throw new RuntimeException(e);
            }
        });
    }

    public void createMEA(API targetAPI, String payload) throws ProcessingException {
        String[] errors = { "" };
        subscriptionsService.runForTenant(tenant, () -> {
            try {
                if (targetAPI.equals(API.EVENT)) {
                    EventRepresentation eventRepresentation = objectMapper.readValue(payload,
                            EventRepresentation.class);
                    eventRepresentation = eventApi.create(eventRepresentation);
                    log.info("New event posted: {}", eventRepresentation);
                } else if (targetAPI.equals(API.ALARM)) {
                    AlarmRepresentation alarmRepresentation = objectMapper.readValue(payload,
                            AlarmRepresentation.class);
                    alarmRepresentation = alarmApi.create(alarmRepresentation);
                    log.info("New alarm posted: {}", alarmRepresentation);
                } else if (targetAPI.equals(API.MEASUREMENT)) {
                    // MeasurementRepresentation mr = objectMapper.readValue(payload,
                    // MeasurementRepresentation.class);
                    MeasurementRepresentation measurementRepresentation = jsonParser
                            .parse(MeasurementRepresentation.class, payload);
                    measurementRepresentation = measurementApi.create(measurementRepresentation);
                    log.info("New measurement posted: {}", measurementRepresentation);
                } else {
                    log.error("Not existing API!");
                }
            } catch (JsonProcessingException e) {
                log.error("Could not map payload: {} {}", targetAPI, payload);
                errors[0] = "Could not map payload: " + targetAPI + "/" + payload;
            } catch (SDKException s) {
                log.error("Could not sent payload to c8y: {} {} {}", targetAPI, payload, s);
                errors[0] = "Could not sent payload to c8y: " + targetAPI + "/" + payload + "/" + s;
            }
        });
        if (!errors[0].equals("")) {
            throw new ProcessingException(errors[0]);
        }
    }

    public void upsertDevice(String payload, String externalId, String externalIdType) throws ProcessingException {
        String[] errors = { "" };
        subscriptionsService.runForTenant(tenant, () -> {
            try {
                ExternalIDRepresentation extId = getExternalId(externalId, externalIdType);
                if (extId == null) {
                    // Device does not exist
                    ManagedObjectRepresentation mor = objectMapper.readValue(payload,
                            ManagedObjectRepresentation.class);
                    // append external id to name
                    mor.setName(mor.getName());
                    mor.set(new IsDevice());
                    mor = inventoryApi.create(mor);
                    log.info("New device created: {}", mor);
                    ExternalIDRepresentation externalAgentId = createExternalID(mor, externalId,
                            externalIdType);
                } else {
                    // Device exists - update needed
                    ManagedObjectRepresentation mor = objectMapper.readValue(payload,
                            ManagedObjectRepresentation.class);
                    mor.setId(extId.getManagedObject().getId());
                    inventoryApi.update(mor);
                    log.info("Device updated: {}", mor);
                }

            } catch (JsonProcessingException e) {
                log.error("Could not map payload: {}", payload);
                errors[0] = "Could not map payload: " + payload;
            } catch (SDKException s) {
                log.error("Could not sent payload to c8y: {} {}", payload, s);
                errors[0] = "Could not sent payload to c8y: " + payload + " " + s;
            }
        });
        if (!errors[0].equals("")) {
            throw new ProcessingException(errors[0]);
        }
    }

    public ManagedObjectRepresentation upsertDevice(String name, String type, String externalId,
            String externalIdType) {
        ManagedObjectRepresentation[] devices = { new ManagedObjectRepresentation() };
        subscriptionsService.runForTenant(tenant, () -> {
            devices[0].setName(name);
            devices[0].setType(type);
            devices[0].set(new IsDevice());
            devices[0] = inventoryApi.create(devices[0]);
            log.debug("New device created with ID {}", devices[0].getId());
            ExternalIDRepresentation externalIdRep = createExternalID(devices[0], externalId, externalIdType);
            log.debug("ExternalId created: {}", externalIdRep.getExternalId());
        });

        log.info("New device {} created with ID {}", devices[0], devices[0].getId());
        return devices[0];
    }

    public void saveMappings(List<Mapping> mappings) throws JsonProcessingException {
        subscriptionsService.runForTenant(tenant, () -> {
            InventoryFilter inventoryFilter = new InventoryFilter();
            inventoryFilter.byType(MappingsRepresentation.MQTT_MAPPING_TYPE);
            ManagedObjectRepresentation mor = inventoryApi.getManagedObjectsByFilter(inventoryFilter).get()
                    .getManagedObjects().get(0);
            ManagedObjectRepresentation updateMOR = new ManagedObjectRepresentation();
            updateMOR.setId(mor.getId());
            updateMOR.setProperty(MappingsRepresentation.MQTT_MAPPING_FRAGMENT, mappings);
            inventoryApi.update(updateMOR);
            log.debug("Updated Mapping after deletion!");
        });
    }

    public ConnectionConfiguration setConfigurationActive(boolean b) {
        ConnectionConfiguration[] configurations = { null };
        subscriptionsService.runForTenant(tenant, () -> {
            configurations[0] = configurationService.setConfigurationActive(b);
            log.debug("Saved configuration");
        });
        return configurations[0];
    }

    public Mapping getMapping(Long id) {
        Mapping[] mr = { null };
        subscriptionsService.runForTenant(tenant, () -> {
            InventoryFilter inventoryFilter = new InventoryFilter();
            inventoryFilter.byType(MappingsRepresentation.MQTT_MAPPING_TYPE);
            ManagedObjectRepresentation mo = inventoryApi.getManagedObjectsByFilter(inventoryFilter).get()
                    .getManagedObjects().get(0);
            MappingsRepresentation mappingsRepresentation = objectMapper.convertValue(mo, MappingsRepresentation.class);
            log.debug("Found Mapping {}", mappingsRepresentation);
            mappingsRepresentation.getC8yMQTTMapping().forEach((m) -> {
                if (m.id == id) {
                    mr[0] = m;
                }
            });
            log.info("Found Mapping {}", mr[0]);
        });
        return mr[0];
    }

    public void sendStatusMapping(String type, Map<String, MappingStatus> mappingStatus) {
        // avoid sending empty monitoring events
        if (mappingStatus.values().size() > 0) {
            log.debug("Sending monitoring: {}", mappingStatus.values().size());
            subscriptionsService.runForTenant(tenant, () -> {
                Map<String, Object> service = new HashMap<String, Object>();
                MappingStatus[] array = mappingStatus.values().toArray(new MappingStatus[0]);
                service.put(MappingServiceRepresentation.MAPPING_STATUS_FRAGMENT, array);
                ManagedObjectRepresentation updateMor = new ManagedObjectRepresentation();
                updateMor.setId(mappingServiceRepresentation.getId());
                updateMor.setAttrs(service);
                this.inventoryApi.update(updateMor);
            });
        } else {
            log.debug("Ignoring monitoring: {}", mappingStatus.values().size());
        }
    }

    public void sendStatusService(String type, ServiceStatus serviceStatus) {
        log.debug("Sending status configuration: {}", serviceStatus);
        subscriptionsService.runForTenant(tenant, () -> {
            Map<String, String> entry = Map.of("status", serviceStatus.getStatus().name());
            Map<String, Object> service = new HashMap<String, Object>();
            service.put(MappingServiceRepresentation.SERVICE_STATUS_FRAGMENT, entry);
            ManagedObjectRepresentation updateMor = new ManagedObjectRepresentation();
            updateMor.setId(mappingServiceRepresentation.getId());
            updateMor.setAttrs(service);
            this.inventoryApi.update(updateMor);
        });
    }
}
