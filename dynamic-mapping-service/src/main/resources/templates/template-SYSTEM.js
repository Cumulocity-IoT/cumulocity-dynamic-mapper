/**
 * @name System code containing the required definitions of the java classes
 * @templateType SYSTEM
 * @defaultTemplate true
 * @internal true
 * @readonly true
 * 
 * System code containing the required definitions of the java classes
 */

const SubstitutionResult = Java.type('dynamic.mapping.processor.model.SubstitutionResult');
const SubstitutionValue = Java.type('dynamic.mapping.processor.model.SubstituteValue');
const ArrayList = Java.type('java.util.ArrayList');
const HashMap = Java.type('java.util.HashMap');
const TYPE = Java.type('dynamic.mapping.processor.model.SubstituteValue$TYPE');
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