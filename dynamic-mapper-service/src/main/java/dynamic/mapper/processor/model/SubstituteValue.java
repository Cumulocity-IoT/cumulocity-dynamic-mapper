package dynamic.mapper.processor.model;

import java.util.Map;

import org.json.JSONException;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.PathNotFoundException;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SubstituteValue implements Cloneable{
    public static enum TYPE {
         ARRAY, //                       Extracted value is an array
         IGNORE, //                      Extracted should be ignored
         NUMBER, //                      Extracted value is a number
         OBJECT, //                      Extracted value is an object, e.g.  {"c8y_ThreePhaseElectricityMeasurement": {"A+": { "value": 435, "unit": "kWh" }}}
         TEXTUAL, //                     Extracted value is a text/ string
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

    public static void substituteValueInPayload(SubstituteValue substitute,
            DocumentContext payloadTarget, String pathTarget)
            throws JSONException {
        boolean subValueMissingOrNull = substitute == null || substitute.value == null;
        // TODO fix this, we have to differentiate between {"nullField": null } and
        // "nonExisting"
        try {
            if (substitute == null) return;
            if ("$".equals(pathTarget)) {
                Object replacement = substitute.value;
                if (replacement instanceof Map<?, ?> map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> rm = (Map<String, Object>) map;
                    for (Map.Entry<String, Object> entry : rm.entrySet()) {
                        payloadTarget.put("$", entry.getKey(), entry.getValue());
                    }
                }
            } else {
                if ((substitute.repairStrategy.equals(RepairStrategy.REMOVE_IF_MISSING_OR_NULL) && subValueMissingOrNull)) {
                    payloadTarget.delete(pathTarget);
                } else if (substitute.repairStrategy.equals(RepairStrategy.CREATE_IF_MISSING)) {
                    // jsonObject.put("$", keys, sub.value);
                    SubstitutionEvaluation.addNestedValue(payloadTarget, pathTarget, substitute.value);
                } else {
                    payloadTarget.set(pathTarget, substitute.value);
                }
            }
        } catch (PathNotFoundException e) {
            throw new PathNotFoundException(String.format("Path: %s not found!", pathTarget));
        }
    }
}
