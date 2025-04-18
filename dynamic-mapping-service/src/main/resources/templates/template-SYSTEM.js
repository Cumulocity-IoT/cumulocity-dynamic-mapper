/**
 * @name System code
 * @description System code containing the required definitions of the java classes
 * @templateType SYSTEM
 * @defaultTemplate true
 * @internal true
 * @readonly true
 * 
 * System code containing the required definitions of the java classes
 */
 
const ArrayList = Java.type('java.util.ArrayList');
const HashMap = Java.type('java.util.HashMap');

const SubstitutionResult = Java.type('dynamic.mapping.processor.model.SubstitutionResult');

/**
 * @param value                Extracted value
 * @param type                 Type of Extracted value
 * @param repairStrategy       RepairStrategy defines how substitution should be processed
 * @param expandArray          If true an array will generate multiple MEAs, [10.5, 30, 49] will generate 3 measurements from one payload
 */
const SubstitutionValue = Java.type('dynamic.mapping.processor.model.SubstituteValue');

/**
 * ARRAY                       Extracted is an array
 * IGNORE                      Extracted should be ignored
 * NUMBER                      Extracted is a number
 * OBJECT                      Extracted is an object, e.g.  {"c8y_ThreePhaseElectricityMeasurement": {"A+": { "value": 435, "unit": "kWh" }}}
 * TEXTUAL                     Extracted is a text/ string
 */
const TYPE = Java.type('dynamic.mapping.processor.model.SubstituteValue$TYPE');

/**
 * DEFAULT                     Process substitution as defined
 * USE_FIRST_VALUE_OF_ARRAY    If extracted content from the source payload is an array, copy only the first item to the target payload
 * USE_LAST_VALUE_OF_ARRAY     If extracted content from the source payload is an array, copy only the last item to the target payload
 * REMOVE_IF_MISSING_OR_NULL   Remove the node in the target if it the evaluation of the source expression returns undefined, empty. This allows for using mapping with dynamic content
 * CREATE_IF_MISSING           Create the node in the target if it doesn't exist. This allows for using mapping with dynamic content
 */
const RepairStrategy = Java.type('dynamic.mapping.processor.model.RepairStrategy');

// Helper function to add a SubstitutionValue to the map
function addToSubstitutionsMap(result, key, value) {
    let map = result.getSubstitutions();
    let valuesList = map.get(key);

    // If the list doesn't exist for this key, create it
    if (valuesList === null || valuesList === undefined) {
        valuesList = new ArrayList();
        map.put(key, valuesList);
    }

    // Add the value to the list
    valuesList.add(value);
}