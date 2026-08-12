package dynamic.mapper.processor.model;

import java.util.Map;

import com.dashjoin.jsonata.json.Json;

import dynamic.mapper.model.Mapping;

@SuppressWarnings("rawtypes")
public class SubstitutionContext {
    private final String payload;
    private final String topic;
    private final String genericDeviceIdentifier;

    // Lazily parsed and cached on first access: getExternalIdentifier() and
    // getC8YIdentifier() both read from the same _IDENTITY_ map, and Smart
    // Functions commonly call both (see template-SYSTEM.js) — cache instead of
    // re-parsing the payload JSON for each call.
    private Map identityMap;
    private boolean identityMapResolved;

    public SubstitutionContext(String genericDeviceIdentifier, String payload, String topic) {
        this.payload = payload;
        this.genericDeviceIdentifier = genericDeviceIdentifier;
        this.topic = topic;
    }

    public String getGenericDeviceIdentifier() {
        return genericDeviceIdentifier;
    }

    public String getTopic() {
        return topic;
    }

    public String getExternalIdentifier() {
        return getIdentityField("externalId");
    }

    public String getC8YIdentifier() {
        return getIdentityField("c8ySourceId");
    }

    public String getPayload() {
        return payload;
    }

    private String getIdentityField(String key) {
        Map identity = resolveIdentityMap();
        return identity == null ? null : (String) identity.get(key);
    }

    private Map resolveIdentityMap() {
        if (!identityMapResolved) {
            identityMapResolved = true;
            try {
                Object jsonObject = Json.parseJson(this.payload);
                if (jsonObject instanceof Map json && json.get(Mapping.TOKEN_IDENTITY) instanceof Map identity) {
                    identityMap = identity;
                }
            } catch (Exception e) {
                // Optionally log the exception
                // logger.debug("Error parsing payload for identity resolution", e);
            }
        }
        return identityMap;
    }
}
