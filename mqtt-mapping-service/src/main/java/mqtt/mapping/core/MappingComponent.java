package mqtt.mapping.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.cumulocity.model.idtype.GId;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.cumulocity.sdk.client.inventory.InventoryApi;
import com.cumulocity.sdk.client.inventory.InventoryFilter;
import com.cumulocity.sdk.client.inventory.ManagedObjectCollection;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import mqtt.mapping.model.Mapping;
import mqtt.mapping.model.MappingServiceRepresentation;
import mqtt.mapping.model.MappingStatus;
import mqtt.mapping.model.MappingRepresentation;
import mqtt.mapping.model.ValidationError;

@Slf4j
@Component
public class MappingComponent {

    private Map<String, MappingStatus> statusMapping = new HashMap<String, MappingStatus>();

    private Set<Mapping> dirtyMappings = new HashSet<Mapping>();

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private InventoryApi inventoryApi;

    @Getter
    private MappingServiceRepresentation mappingServiceRepresentation;

    public void removeStatusMapping(String ident) {
        statusMapping.remove(ident);
    }

    private void initializeMappingStatus() {
        mappingServiceRepresentation.getMappingStatus().forEach(ms -> {
            statusMapping.put(ms.ident, ms);
        });

    }

    public void initializeMappingComponent(MappingServiceRepresentation mappingServiceRepresentation) {
        this.mappingServiceRepresentation = mappingServiceRepresentation;
        initializeMappingStatus();
    }

    public void sendStatusMapping() {
        // avoid sending empty monitoring events
        if (statusMapping.values().size() > 0 && mappingServiceRepresentation != null) {
            log.debug("Sending monitoring: {}", statusMapping.values().size());
            Map<String, Object> service = new HashMap<String, Object>();
            MappingStatus[] array = statusMapping.values().toArray(new MappingStatus[0]);
            service.put(MappingServiceRepresentation.MAPPING_STATUS_FRAGMENT, array);
            ManagedObjectRepresentation updateMor = new ManagedObjectRepresentation();
            updateMor.setId(mappingServiceRepresentation.getId());
            updateMor.setAttrs(service);
            this.inventoryApi.update(updateMor);
        } else {
            log.debug("Ignoring mapping monitoring: {}", statusMapping.values().size());
        }
    }

    public void sendStatusService(ServiceStatus serviceStatus) {
        if ((statusMapping.values().size() > 0) && mappingServiceRepresentation != null) {
            log.debug("Sending status configuration: {}", serviceStatus);
            Map<String, String> entry = Map.of("status", serviceStatus.getStatus().name());
            Map<String, Object> service = new HashMap<String, Object>();
            service.put(MappingServiceRepresentation.SERVICE_STATUS_FRAGMENT, entry);
            ManagedObjectRepresentation updateMor = new ManagedObjectRepresentation();
            updateMor.setId(mappingServiceRepresentation.getId());
            updateMor.setAttrs(service);
            this.inventoryApi.update(updateMor);
        } else {
            log.debug("Ignoring status monitoring: {}", serviceStatus);
        }
    }

    public MappingStatus getMappingStatus(Mapping m, boolean unspecified) {
        String topic = "#";
        String key = "UNSPECIFIED";
        String ident = "#";
        if (!unspecified) {
            topic = m.subscriptionTopic;
            key = m.id;
            ident = m.ident;
        }
        MappingStatus ms = statusMapping.get(ident);
        if (ms == null) {
            log.info("Adding: {}", key);
            ms = new MappingStatus(key, ident, topic, 0, 0, 0, 0);
            statusMapping.put(ident, ms);
        }
        return ms;
    }

    public List<MappingStatus> getMappingStatus() {
        return new ArrayList<MappingStatus>(statusMapping.values());
    }

    public List<MappingStatus> resetMappingStatus() {
        ArrayList<MappingStatus> msl = new ArrayList<MappingStatus>(statusMapping.values());
        msl.forEach(ms -> ms.reset());
        return msl;
    }

    public void setMappingDirty(Mapping mapping) {
        log.debug("Setting dirty: {}", mapping);
        dirtyMappings.add(mapping);
    }

    public Set<Mapping> getMappingDirty() {
        return dirtyMappings;
    }

    public void resetMappingDirty() {
        dirtyMappings = new HashSet<Mapping>();
    }

    public void saveMappings(List<Mapping> mappings) {
        mappings.forEach(m -> {
            MappingRepresentation mpr = new MappingRepresentation();
            mpr.setId(GId.asGId(m.id));
            mpr.setC8yMQTTMapping(m);
            inventoryApi.update(mpr);
        });
        log.debug("Saved mappings!");
    }

    public Mapping getMapping(String id) {
        Mapping result = null;
        ManagedObjectRepresentation mo = inventoryApi.get(GId.asGId(id));
        if (mo != null) {
            MappingRepresentation mappingsRepresentation = objectMapper.convertValue(mo, MappingRepresentation.class);
            result = mappingsRepresentation.getC8yMQTTMapping();
        }
        log.info("Found Mapping: {}", result.id);
        return result;
    }

    public String deleteMapping(String id) {
        inventoryApi.delete(GId.asGId(id));
        deleteMappingStatus(id);
        log.info("Deleted Mapping: {}", id);
        return id;
    }

    public List<Mapping> getMappings() {
        InventoryFilter inventoryFilter = new InventoryFilter();
        inventoryFilter.byType(MappingRepresentation.MQTT_MAPPING_TYPE);
        ManagedObjectCollection moc = inventoryApi.getManagedObjectsByFilter(inventoryFilter);
        List<Mapping> result = StreamSupport.stream(moc.get().allPages().spliterator(), false)
                .map(mo -> (objectMapper.convertValue(mo, MappingRepresentation.class)))
                .peek(m -> {
                    m.getC8yMQTTMapping().id = m.getId().getValue();
                })
                .map( mo -> mo.getC8yMQTTMapping())
                .collect(Collectors.toList());
        log.debug("Found Mappings {}", result);
        return result;
    }

    public Mapping updateMapping(Mapping mapping) {
        List<Mapping> mappings = getMappings();
        MappingRepresentation mr = new MappingRepresentation();
        Mapping result = null;
        List<ValidationError> errors = MappingRepresentation.isMappingValid(mappings, mapping);
        if (errors.size() == 0) {
            mapping.lastUpdate = System.currentTimeMillis();
            mr.setType(MappingRepresentation.MQTT_MAPPING_TYPE);
            mr.setC8yMQTTMapping(mapping);
            mr.setId(GId.asGId(mapping.id));
            inventoryApi.update(mr);
            result = mapping;
        } else {
            String errorList = errors.stream().map(e -> e.toString()).reduce("",
                    (res, error) -> res + "[ " + error + " ]");
            throw new RuntimeException("Validation errors:" + errorList);
        }
        return result;
    }

    public Mapping createMapping(Mapping mapping) {
        List<Mapping> mappings = getMappings();
        MappingRepresentation mr = new MappingRepresentation();
        Mapping result = null;
        List<ValidationError> errors = MappingRepresentation.isMappingValid(mappings, mapping);
        if (errors.size() == 0) {
            mapping.lastUpdate = System.currentTimeMillis();
            mr.setC8yMQTTMapping(mapping);
            mr.setId(GId.asGId(mapping.id));
            ManagedObjectRepresentation mor = inventoryApi.create(mr);
            result = mapping;
            result.id = mor.getId().getValue();
        } else {
            String errorList = errors.stream().map(e -> e.toString()).reduce("",
                    (res, error) -> res + "[ " + error + " ]");
            throw new RuntimeException("Validation errors:" + errorList);
        }
        return result;
    }

	private void deleteMappingStatus(String id) {
        statusMapping.remove(id);
	}
}