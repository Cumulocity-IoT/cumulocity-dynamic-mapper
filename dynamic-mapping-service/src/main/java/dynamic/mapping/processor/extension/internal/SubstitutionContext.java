package dynamic.mapping.processor.extension.internal;

import java.util.Map;

import dynamic.mapping.model.Mapping;

public class SubstitutionContext {
    private final Map jsonObject;
    private final String genericDeviceIdentifier;
    public final String IDENTITY_EXTERNAL= Mapping.IDENTITY + ".externalId";
    public final String IDENTITY_C8Y= Mapping.IDENTITY + ".c8ySourceId";

    public SubstitutionContext(String genericDeviceIdentifier,Map jsonObject) {
        this.jsonObject = jsonObject;
        this.genericDeviceIdentifier = genericDeviceIdentifier;
    }


    public String getGenericDeviceIdentifier() {
        return genericDeviceIdentifier;
    }
    public Map getJsonObject() {
        return jsonObject;
    }
}
