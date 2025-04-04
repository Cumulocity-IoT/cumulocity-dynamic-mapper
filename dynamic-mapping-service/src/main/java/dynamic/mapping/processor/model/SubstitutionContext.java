package dynamic.mapping.processor.model;

import java.util.Map;

import com.dashjoin.jsonata.json.Json;

import dynamic.mapping.model.Mapping;

@SuppressWarnings("rawtypes")
public class SubstitutionContext {
    private final String payload;
    private final String genericDeviceIdentifier;
    public final String IDENTITY_EXTERNAL = Mapping.IDENTITY + ".externalId";
    public final String IDENTITY_C8Y = Mapping.IDENTITY + ".c8ySourceId";

    public SubstitutionContext(String genericDeviceIdentifier, String payload) {
        this.payload = payload;
        this.genericDeviceIdentifier = genericDeviceIdentifier;
    }

    public String getGenericDeviceIdentifier() {
        return genericDeviceIdentifier;
    }

    public String getExternalIdentifier() {
        Object jsonObject = Json.parseJson(this.payload);
        if (!(jsonObject instanceof Map))
            return null;
        Map json = (Map) jsonObject;

        try {
            // Check if payload and the IDENTITY map exist
            if (json == null || json.get(Mapping.IDENTITY) == null || ! (json instanceof Map) ) {
                return null;
            }

            Map identityMap = (Map)json.get(Mapping.IDENTITY);
            return (String) identityMap.get("externalId");
        } catch (Exception e) {
            // Optionally log the exception
            // logger.debug("Error retrieving external identifier", e);
            return null;
        }
    }

    public String getC8YIdentifier() {
        Object jsonObject = Json.parseJson(this.payload);
        if (!(jsonObject instanceof Map))
            return null;
        Map json = (Map) jsonObject;

        try {
            // Check if payload and the IDENTITY map exist
            if (json == null || json.get(Mapping.IDENTITY) == null || ! (json instanceof Map) ) {
                return null;
            }

            Map identityMap = (Map) json.get(Mapping.IDENTITY);
            return (String) identityMap.get("c8ySourceId");
        } catch (Exception e) {
            // Optionally log the exception
            // logger.debug("Error retrieving c8y identifier", e);
            return null;
        }
    }

    public String getPayload() {
        return payload;
    }
}
