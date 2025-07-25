package dynamic.mapper.processor.model;

import java.util.Map;

import org.json.JSONException;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.PathNotFoundException;

public class SubstituteValue implements Cloneable{
    public static enum TYPE {
         ARRAY, //                       Extracted is is an array
         IGNORE, //                      Extracted should be ignored
         NUMBER, //                      Extracted is is a number
         OBJECT, //                      Extracted is is an object, e.g.  {"c8y_ThreePhaseElectricityMeasurement": {"A+": { "value": 435, "unit": "kWh" }}}
         TEXTUAL, //                     Extracted is is a text/ string
    }

    public Object value;
    public TYPE type;
    public RepairStrategy repairStrategy;
    public boolean expandArray;

    public SubstituteValue(Object value, TYPE type, RepairStrategy repair, boolean expandArray) {
        this.type = type;
        this.value = value;
        this.repairStrategy = repair;
        this.expandArray = expandArray;
    }

    @Override
    public SubstituteValue clone() {
        return new SubstituteValue(this.value, this.type, this.repairStrategy, this.expandArray);
    }

    public static void substituteValueInPayload(SubstituteValue sub,
            DocumentContext jsonObject, String keys)
            throws JSONException {
        boolean subValueMissingOrNull = sub == null || sub.value == null;
        // TOFDO fix this, we have to differentiate between {"nullField": null } and
        // "nonExisting"
        try {
            if (sub == null) return;
            if ("$".equals(keys)) {
                Object replacement = sub.value;
                if (replacement instanceof Map<?, ?> map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> rm = (Map<String, Object>) map;
                    for (Map.Entry<String, Object> entry : rm.entrySet()) {
                        jsonObject.put("$", entry.getKey(), entry.getValue());
                    }
                }
            } else {
                if ((sub.repairStrategy.equals(RepairStrategy.REMOVE_IF_MISSING_OR_NULL) && subValueMissingOrNull)) {
                    jsonObject.delete(keys);
                } else if (sub.repairStrategy.equals(RepairStrategy.CREATE_IF_MISSING)) {
                    // jsonObject.put("$", keys, sub.value);
                    SubstitutionEvaluation.addNestedValue(jsonObject, keys, sub.value);
                } else {
                    jsonObject.set(keys, sub.value);
                }
            }
        } catch (PathNotFoundException e) {
            throw new PathNotFoundException(String.format("Path: %s not found!", keys));
        }
    }
}
