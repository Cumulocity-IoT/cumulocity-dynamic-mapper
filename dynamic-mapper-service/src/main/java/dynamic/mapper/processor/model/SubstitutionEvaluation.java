package dynamic.mapper.processor.model;

import java.util.ArrayList;
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
        this.setRepairStrategy(repairStrategy);
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
        this.setRepairStrategy(repairStrategy);
    }

    public static void addNestedValue(DocumentContext jsonObject, String path, Object value) {
        String[] parts = path.split("\\.");
        StringBuilder currentPath = new StringBuilder("$");

        // Create all parent objects/arrays except the last one
        for (int i = 0; i < parts.length - 1; i++) {
            String part = parts[i];

            if (containsArrayIndex(part)) {
                String[] arrayParts = parseArrayNotation(part);
                String arrayName = arrayParts[0];
                int index = Integer.parseInt(arrayParts[1]);

                // Create the array if it doesn't exist
                try {
                    jsonObject.read(currentPath + "." + arrayName);
                } catch (Exception e) {
                    jsonObject.put(currentPath.toString(), arrayName, new ArrayList<>());
                }

                // Ensure array has enough elements
                ensureArraySize(jsonObject, currentPath + "." + arrayName, index + 1);

                // Set the array element to an empty object for nesting
                jsonObject.set(currentPath + "." + arrayName + "[" + index + "]", new HashMap<>());
                currentPath.append(".").append(arrayName).append("[").append(index).append("]");
            } else {
                // Regular object property
                try {
                    jsonObject.read(currentPath + "." + part);
                } catch (Exception e) {
                    jsonObject.put(currentPath.toString(), part, new HashMap<>());
                }
                currentPath.append(".").append(part);
            }
        }

        // Handle the final part (could also be an array)
        String finalPart = parts[parts.length - 1];
        if (containsArrayIndex(finalPart)) {
            String[] arrayParts = parseArrayNotation(finalPart);
            String arrayName = arrayParts[0];
            int index = Integer.parseInt(arrayParts[1]);

            // Create the array if it doesn't exist
            try {
                jsonObject.read(currentPath + "." + arrayName);
            } catch (Exception e) {
                jsonObject.put(currentPath.toString(), arrayName, new ArrayList<>());
            }

            // Ensure array has enough elements
            ensureArraySize(jsonObject, currentPath + "." + arrayName, index + 1);

            // Set the final value
            jsonObject.set(currentPath + "." + arrayName + "[" + index + "]", value);
        } else {
            // Regular final property
            jsonObject.put(currentPath.toString(), finalPart, value);
        }
    }

    private static boolean containsArrayIndex(String part) {
        return part.contains("[") && part.contains("]");
    }

    private static String[] parseArrayNotation(String part) {
        int openBracket = part.indexOf('[');
        int closeBracket = part.indexOf(']');

        String arrayName = part.substring(0, openBracket);
        String indexStr = part.substring(openBracket + 1, closeBracket);

        return new String[] { arrayName, indexStr };
    }

    private static void ensureArraySize(DocumentContext jsonObject, String arrayPath, int requiredSize) {
        try {
            List<Object> array = jsonObject.read(arrayPath);
            while (array.size() < requiredSize) {
                array.add(null);
            }
            jsonObject.set(arrayPath, array);
        } catch (Exception e) {
            // Array doesn't exist, create it with required size
            List<Object> newArray = new ArrayList<>();
            for (int i = 0; i < requiredSize; i++) {
                newArray.add(null);
            }
            jsonObject.set(arrayPath, newArray);
        }
    }

    public static void processSubstitute(String tenant,
            List<SubstituteValue> processingCacheEntry,
            Object extractedSourceContent, Substitution substitution, Mapping mapping) {
        if (extractedSourceContent == null) {
            SubstitutionEvaluation.log.warn("{} - Substitution {} not in message payload. Check your mapping {}",
                    tenant,
                    substitution, mapping.getMappingTopic());
            processingCacheEntry
                    .add(new SubstituteValue(extractedSourceContent,
                            SubstituteValue.TYPE.IGNORE, substitution.getRepairStrategy(),
                            substitution.isExpandArray()));
        } else if (SubstitutionEvaluation.isTextual(extractedSourceContent)) {
            processingCacheEntry.add(
                    new SubstituteValue(extractedSourceContent,
                            TYPE.TEXTUAL, substitution.getRepairStrategy(), substitution.isExpandArray()));
        } else if (SubstitutionEvaluation.isNumber(extractedSourceContent)) {
            processingCacheEntry
                    .add(new SubstituteValue(extractedSourceContent,
                            SubstituteValue.TYPE.NUMBER, substitution.getRepairStrategy(),
                            substitution.isExpandArray()));
        } else if (SubstitutionEvaluation.isArray(extractedSourceContent)) {
            processingCacheEntry
                    .add(new SubstituteValue(extractedSourceContent,
                            SubstituteValue.TYPE.ARRAY, substitution.getRepairStrategy(),
                            substitution.isExpandArray()));
        } else if (SubstitutionEvaluation.isBoolean(extractedSourceContent)) {
            processingCacheEntry
                    .add(new SubstituteValue(extractedSourceContent,
                            SubstituteValue.TYPE.BOOLEAN, substitution.getRepairStrategy(),
                            substitution.isExpandArray()));
        } else {
            processingCacheEntry
                    .add(new SubstituteValue(extractedSourceContent,
                            SubstituteValue.TYPE.OBJECT, substitution.getRepairStrategy(),
                            substitution.isExpandArray()));
        }

    }

    public static void processSubstitute(String tenant,
            List<SubstituteValue> processingCacheEntry,
            Object extractedSourceContent, SubstituteValue substitutionValue, Mapping mapping) {
        if (extractedSourceContent == null) {
            SubstitutionEvaluation.log.warn("{} - Substitution {} not in message payload. Check your mapping {}",
                    tenant,
                    substitutionValue, mapping.getMappingTopic());
            processingCacheEntry
                    .add(new SubstituteValue(extractedSourceContent,
                            SubstituteValue.TYPE.IGNORE, substitutionValue.getRepairStrategy(),
                            substitutionValue.isExpandArray()));
        } else if (SubstitutionEvaluation.isTextual(extractedSourceContent)) {
            processingCacheEntry.add(
                    new SubstituteValue(extractedSourceContent,
                            TYPE.TEXTUAL, substitutionValue.getRepairStrategy(), substitutionValue.isExpandArray()));
        } else if (SubstitutionEvaluation.isNumber(extractedSourceContent)) {
            processingCacheEntry
                    .add(new SubstituteValue(extractedSourceContent,
                            SubstituteValue.TYPE.NUMBER, substitutionValue.getRepairStrategy(),
                            substitutionValue.isExpandArray()));
        } else if (SubstitutionEvaluation.isArray(extractedSourceContent)) {
            processingCacheEntry
                    .add(new SubstituteValue(extractedSourceContent,
                            SubstituteValue.TYPE.ARRAY, substitutionValue.getRepairStrategy(),
                            substitutionValue.isExpandArray()));
        } else {
            processingCacheEntry
                    .add(new SubstituteValue(extractedSourceContent,
                            SubstituteValue.TYPE.OBJECT, substitutionValue.getRepairStrategy(),
                            substitutionValue.isExpandArray()));
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
