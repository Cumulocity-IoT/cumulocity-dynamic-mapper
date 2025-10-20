package dynamic.mapper.processor;

import static com.dashjoin.jsonata.Jsonata.jsonata;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.springframework.beans.factory.annotation.Autowired;
import com.cumulocity.model.ID;

import dynamic.mapper.core.C8YAgent;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.model.API;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.processor.flow.CumulocityMessage;
import dynamic.mapper.processor.flow.CumulocitySource;
import dynamic.mapper.processor.flow.ExternalSource;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.util.Utils;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public abstract class CommonProcessor implements Processor {

    @Autowired
    private ConfigurationRegistry configurationRegistry;

    @Autowired
    private C8YAgent c8yAgent;

    public abstract void process(Exchange exchange) throws Exception;

    /**
     * Evaluates an inventory filter against cached inventory data
     */
    protected boolean evaluateInventoryFilter(String tenant, String filterExpression, String sourceId,
            Boolean testing) {
        try {
            Map<String, Object> cachedInventoryContent = configurationRegistry.getC8yAgent()
                    .getMOFromInventoryCache(tenant, sourceId, testing);
            List<String> keyList = new ArrayList<>(cachedInventoryContent.keySet());
            log.info("{} - For object {} found following fragments in inventory cache {}",
                    tenant, sourceId, keyList);
            var expression = jsonata(filterExpression);
            Object result = expression.evaluate(cachedInventoryContent);

            if (result != null && Utils.isNodeTrue(result)) {
                log.info("{} - Found valid inventory for filter {}",
                        tenant, filterExpression);
                return true;
            } else {
                log.debug("{} - Not matching inventory filter {} for source {}",
                        tenant, filterExpression, sourceId);
                return false;
            }
        } catch (Exception e) {
            log.debug("Inventory filter evaluation error for {}: {}", filterExpression, e.getMessage());
            return false;
        }
    }

    /**
     * Sets a value hierarchically in a map using dot notation
     * E.g., "source.id" will create nested maps: {"source": {"id": value}}
     */
    protected void setHierarchicalValue(Map<String, Object> map, String path, Object value) {
        String[] keys = path.split("\\.");
        Map<String, Object> current = map;

        // Navigate/create the hierarchy up to the last key
        for (int i = 0; i < keys.length - 1; i++) {
            String key = keys[i];
            if (!current.containsKey(key) || !(current.get(key) instanceof Map)) {
                current.put(key, new HashMap<String, Object>());
            }
            current = (Map<String, Object>) current.get(key);
        }

        // Set the value at the final key
        current.put(keys[keys.length - 1], value);
    }

    protected API getAPIFromCumulocityType(String cumulocityType) throws ProcessingException {
        switch (cumulocityType.toLowerCase()) {
            case "measurement":
                return API.MEASUREMENT;
            case "alarm":
                return API.ALARM;
            case "event":
                return API.EVENT;
            case "inventory":
            case "managedobject":
                return API.INVENTORY;
            case "operation":
                return API.OPERATION;
            default:
                throw new ProcessingException("Unknown cumulocity type: " + cumulocityType);
        }
    }

    /**
     * Determine default API from mapping
     */
    protected API determineDefaultAPI(Mapping mapping) {
        if (mapping.getTargetAPI() != null) {
            try {
                return mapping.getTargetAPI();
            } catch (Exception e) {
                log.warn("Unknown target API: {}, defaulting to MEASUREMENT", mapping.getTargetAPI());
            }
        }

        return API.MEASUREMENT; // Default
    }

    protected String resolveDeviceIdentifier(CumulocityMessage cumulocityMessage, ProcessingContext<?> context,
            String tenant) throws ProcessingException {

        // First try externalSource
        if (cumulocityMessage.getExternalSource() != null) {
            return resolveFromExternalSource(cumulocityMessage.getExternalSource(), context, tenant);
        }

        // Then try internalSource
        if (cumulocityMessage.getInternalSource() != null) {
            return resolveFromInternalSource(cumulocityMessage.getInternalSource());
        }

        // Fallback to mapping's generic device identifier
        return context.getMapping().getGenericDeviceIdentifier();
    }

    protected String resolveFromExternalSource(Object externalSourceObj, ProcessingContext<?> context,
            String tenant) throws ProcessingException {

        List<ExternalSource> externalSources = convertToExternalSourceList(externalSourceObj);

        if (externalSources.isEmpty()) {
            throw new ProcessingException("External source is empty");
        }

        // Use the first external source for resolution
        ExternalSource externalSource = externalSources.get(0);

        try {
            // Use C8YAgent to resolve external ID to global ID
            var globalId = c8yAgent.resolveExternalId2GlobalId(tenant,
                    new ID(externalSource.getType(), externalSource.getExternalId()),
                    context.isTesting());
            context.setExternalId(externalSource.getExternalId());

            if (globalId != null) {
                return globalId.getManagedObject().getId().getValue();
            } else {
                throw new ProcessingException("Could not resolve external ID: " + externalSource.getExternalId());
            }

        } catch (Exception e) {
            throw new ProcessingException("Failed to resolve external ID: " + externalSource.getExternalId(), e);
        }
    }

    protected String resolveFromInternalSource(Object internalSourceObj) throws ProcessingException {
        List<CumulocitySource> internalSources = convertToInternalSourceList(internalSourceObj);

        if (internalSources.isEmpty()) {
            throw new ProcessingException("Internal source is empty");
        }

        // Use the first internal source directly
        return internalSources.get(0).getInternalId();
    }

    // Keep all existing conversion methods unchanged
    @SuppressWarnings("unchecked")
    protected List<ExternalSource> convertToExternalSourceList(Object obj) {
        // ... (keep existing implementation)
        List<ExternalSource> result = new ArrayList<>();

        if (obj == null) {
            return result;
        }

        if (obj instanceof ExternalSource) {
            result.add((ExternalSource) obj);
        } else if (obj instanceof List) {
            List<?> list = (List<?>) obj;
            for (Object item : list) {
                if (item instanceof ExternalSource) {
                    result.add((ExternalSource) item);
                } else if (item instanceof Map) {
                    ExternalSource externalSource = convertMapToExternalSource((Map<String, Object>) item);
                    if (externalSource != null) {
                        result.add(externalSource);
                    }
                }
            }
        } else if (obj instanceof Map) {
            ExternalSource externalSource = convertMapToExternalSource((Map<String, Object>) obj);
            if (externalSource != null) {
                result.add(externalSource);
            }
        }

        return result;
    }

    @SuppressWarnings("unchecked")
    public List<CumulocitySource> convertToInternalSourceList(Object obj) {
        List<CumulocitySource> result = new ArrayList<>();

        if (obj == null) {
            return result;
        }

        if (obj instanceof CumulocitySource) {
            result.add((CumulocitySource) obj);
        } else if (obj instanceof List) {
            List<?> list = (List<?>) obj;
            for (Object item : list) {
                if (item instanceof CumulocitySource) {
                    result.add((CumulocitySource) item);
                } else if (item instanceof Map) {
                    // Convert Map to CumulocitySource
                    CumulocitySource cumulocitySource = convertMapToCumulocitySource((Map<String, Object>) item);
                    if (cumulocitySource != null) {
                        result.add(cumulocitySource);
                    }
                }
            }
        } else if (obj instanceof Map) {
            CumulocitySource cumulocitySource = convertMapToCumulocitySource((Map<String, Object>) obj);
            if (cumulocitySource != null) {
                result.add(cumulocitySource);
            }
        }

        return result;
    }

    private ExternalSource convertMapToExternalSource(Map<String, Object> map) {
        // ... (keep existing implementation)
        if (map == null) {
            return null;
        }

        ExternalSource externalSource = new ExternalSource();

        if (map.containsKey("externalId")) {
            externalSource.setExternalId(String.valueOf(map.get("externalId")));
        }
        if (map.containsKey("type")) {
            externalSource.setType(String.valueOf(map.get("type")));
        }
        if (map.containsKey("autoCreateDeviceMO")) {
            externalSource.setAutoCreateDeviceMO((Boolean) map.get("autoCreateDeviceMO"));
        }
        if (map.containsKey("parentId")) {
            externalSource.setParentId(String.valueOf(map.get("parentId")));
        }
        if (map.containsKey("childReference")) {
            externalSource.setChildReference(String.valueOf(map.get("childReference")));
        }
        if (map.containsKey("clientId")) {
            externalSource.setClientId(String.valueOf(map.get("clientId")));
        }

        // Only return if we have the required fields
        if (externalSource.getExternalId() != null && externalSource.getType() != null) {
            return externalSource;
        }

        return null;
    }

    private CumulocitySource convertMapToCumulocitySource(Map<String, Object> map) {
        if (map == null || !map.containsKey("internalId")) {
            return null;
        }

        return new CumulocitySource(String.valueOf(map.get("internalId")));
    }

}
