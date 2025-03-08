package dynamic.mapping.processor.extension.internal;

import java.util.Map;

import dynamic.mapping.model.Mapping;

public class SubstitutionContext {
    private final Map jsonObject;
    private final String genericDeviceIdentifier;
    public final String IDENTITY_EXTERNAL = Mapping.IDENTITY + ".externalId";
    public final String IDENTITY_C8Y = Mapping.IDENTITY + ".c8ySourceId";

    public SubstitutionContext(String genericDeviceIdentifier, Map jsonObject) {
        this.jsonObject = jsonObject;
        this.genericDeviceIdentifier = genericDeviceIdentifier;
    }

    public String getGenericDeviceIdentifier() {
        return genericDeviceIdentifier;
    }

    public String getExternalIdentifier() {
        try {
            // Check if jsonObject and the IDENTITY map exist
            if (jsonObject == null || jsonObject.get(Mapping.IDENTITY) == null) {
                return null;
            }

            Map identityMap = (Map) jsonObject.get(Mapping.IDENTITY);
            return (String) identityMap.get("externalId");
        } catch (Exception e) {
            // Optionally log the exception
            // logger.debug("Error retrieving external identifier", e);
            return null;
        }
    }

    public String getC8YIdentifier() {
        try {
            // Check if jsonObject and the IDENTITY map exist
            if (jsonObject == null || jsonObject.get(Mapping.IDENTITY) == null) {
                return null;
            }

            Map identityMap = (Map) jsonObject.get(Mapping.IDENTITY);
            return (String) identityMap.get("c8ySourceId");
        } catch (Exception e) {
            // Optionally log the exception
            // logger.debug("Error retrieving c8y identifier", e);
            return null;
        }
    }

    public Map getJsonObject() {
        return jsonObject;
    }
}
