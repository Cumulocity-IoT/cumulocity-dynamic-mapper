package dynamic.mapping.processor.extension.internal;

import java.util.Map;

public class SubstitutionContext {
    private final Map jsonObject;
    private final String genericDeviceIdentifier;

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
