package dynamic.mapper.processor.model;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.jayway.jsonpath.DocumentContext;

import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.Substitution;
import dynamic.mapper.processor.model.SubstituteValue.TYPE;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SubstitutionEvaluation {
    String key;
    Object value;
    String type;
    public String repairStrategy;

    public SubstitutionEvaluation(String key, Object value, String type, String repairStrategy) {
        this.key = key;
        this.value = value;
        this.type = type;
        this.repairStrategy = repairStrategy;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public Object getValue() {
        return value;
    }

    public void setValue(Object value) {
        this.value = value;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getRepairStrategy() {
        return repairStrategy;
    }

    public void setRepairStrategy(String repairStrategy) {
        this.repairStrategy = repairStrategy;
    }

    public static void addNestedValue(DocumentContext jsonObject, String path, Object value) {
        String[] parts = path.split("\\.");
        StringBuilder currentPath = new StringBuilder("$");
        
        // Create all parent objects except the last one
        for (int i = 0; i < parts.length - 1; i++) {
            if (i == 0) {
                jsonObject.put("$", parts[i], new HashMap<>());
            } else {
                jsonObject.put(currentPath.toString(), parts[i], new HashMap<>());
            }
            currentPath.append(".").append(parts[i]);
        }
        
        // Add the final value
        jsonObject.put(currentPath.toString(), parts[parts.length - 1], value);
    }

    public static void processSubstitute(String tenant,
            List<SubstituteValue> processingCacheEntry,
            Object extractedSourceContent, Substitution substitution, Mapping mapping) {
        if (extractedSourceContent == null) {
            SubstitutionEvaluation.log.warn("{} - Substitution {} not in message payload. Check your mapping {}", tenant,
                    substitution, mapping.getMappingTopic());
            processingCacheEntry
                    .add(new SubstituteValue(extractedSourceContent,
                            SubstituteValue.TYPE.IGNORE, substitution.repairStrategy, substitution.expandArray));
        } else if (SubstitutionEvaluation.isTextual(extractedSourceContent)) {
            processingCacheEntry.add(
                    new SubstituteValue(extractedSourceContent,
                            TYPE.TEXTUAL, substitution.repairStrategy, substitution.expandArray));
        } else if (SubstitutionEvaluation.isNumber(extractedSourceContent)) {
            processingCacheEntry
                    .add(new SubstituteValue(extractedSourceContent,
                            SubstituteValue.TYPE.NUMBER, substitution.repairStrategy, substitution.expandArray));
        } else if (SubstitutionEvaluation.isArray(extractedSourceContent)) {
            processingCacheEntry
                    .add(new SubstituteValue(extractedSourceContent,
                            SubstituteValue.TYPE.ARRAY, substitution.repairStrategy, substitution.expandArray));
        } else {
            processingCacheEntry
                    .add(new SubstituteValue(extractedSourceContent,
                            SubstituteValue.TYPE.OBJECT, substitution.repairStrategy, substitution.expandArray));
        }
    }


    public static void processSubstitute(String tenant,
            List<SubstituteValue> processingCacheEntry,
            Object extractedSourceContent, SubstituteValue substitutionValue , Mapping mapping) {
        if (extractedSourceContent == null) {
            SubstitutionEvaluation.log.warn("{} - Substitution {} not in message payload. Check your mapping {}", tenant,
                    substitutionValue, mapping.getMappingTopic());
            processingCacheEntry
                    .add(new SubstituteValue(extractedSourceContent,
                            SubstituteValue.TYPE.IGNORE, substitutionValue.repairStrategy, substitutionValue.expandArray));
        } else if (SubstitutionEvaluation.isTextual(extractedSourceContent)) {
            processingCacheEntry.add(
                    new SubstituteValue(extractedSourceContent,
                            TYPE.TEXTUAL, substitutionValue.repairStrategy, substitutionValue.expandArray));
        } else if (SubstitutionEvaluation.isNumber(extractedSourceContent)) {
            processingCacheEntry
                    .add(new SubstituteValue(extractedSourceContent,
                            SubstituteValue.TYPE.NUMBER, substitutionValue.repairStrategy, substitutionValue.expandArray));
        } else if (SubstitutionEvaluation.isArray(extractedSourceContent)) {
            processingCacheEntry
                    .add(new SubstituteValue(extractedSourceContent,
                            SubstituteValue.TYPE.ARRAY, substitutionValue.repairStrategy, substitutionValue.expandArray));
        } else {
            processingCacheEntry
                    .add(new SubstituteValue(extractedSourceContent,
                            SubstituteValue.TYPE.OBJECT, substitutionValue.repairStrategy, substitutionValue.expandArray));
        }
    }

    public static Boolean isObject(Object obj) {
        return obj != null && obj instanceof Map;
    }

    public static Boolean isTextual(Object obj) {
        return obj != null && obj instanceof String;
    }

    public static Boolean isArray(Object obj) {
        return obj != null && obj instanceof Collection;
    }

    public static Boolean isBoolean(Object obj) {
        return obj != null && obj instanceof Boolean;
    }

    public static Boolean isNumber(Object obj) {
        return obj != null && obj instanceof Number;
    }
}
