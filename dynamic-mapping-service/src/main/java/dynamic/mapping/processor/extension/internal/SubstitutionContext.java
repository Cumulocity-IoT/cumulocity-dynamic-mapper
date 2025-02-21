package dynamic.mapping.processor.extension.internal;

import java.util.Map;

public class SubstitutionContext {
    private final Map jsonObject;

    public SubstitutionContext(Map jsonObject) {
        this.jsonObject = jsonObject;
    }

    public Map getJsonObject() {
        return jsonObject;
    }
}
