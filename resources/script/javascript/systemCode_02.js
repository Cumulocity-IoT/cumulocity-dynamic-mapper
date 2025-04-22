// share project specific java value classes
const SubstitutionResult = Java.type('dynamic.mapping.processor.model.SubstitutionResult');
const SubstitutionValue = Java.type('dynamic.mapping.processor.model.SubstituteValue');
const RepairStrategy = Java.type('dynamic.mapping.processor.model.RepairStrategy');
const TYPE = Java.type('dynamic.mapping.processor.model.SubstituteValue$TYPE');

// share java value classes
const ArrayList = Java.type('java.util.ArrayList');
const HashMap = Java.type('java.util.HashMap');

// Helper function to add a SubstitutionValue to the map
function addSubstitution(result, key, value) {
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