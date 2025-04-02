// share project specific java value classes
const SubstitutionResult = Java.type('dynamic.mapping.processor.model.SubstitutionResult');
const SubstitutionValue = Java.type('dynamic.mapping.processor.model.SubstituteValue');
const RepairStrategy = Java.type('dynamic.mapping.processor.model.RepairStrategy');
const TYPE = Java.type('dynamic.mapping.processor.model.SubstituteValue$TYPE');

// share java value classes
const ArrayList = Java.type('java.util.ArrayList');
const HashMap = Java.type('java.util.HashMap');

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


function extractFromSource(ctx) {
    // This is the source message as json
    const sourceObject = ctx.getJsonObject();
    for (var key in sourceObject) {
        console.log(`key: ${key}, value: ${sourceObject.get(key)}`);
    }

    // Define a new Measurement Value for Temperatures by assigning from source
    const fragmentTemperatureSeries = {
        value: sourceObject.get('temperature'),
        unit: sourceObject.get('unit')
    };

    // Assign Values to Series
    const fragmentTemperature = {
        T: fragmentTemperatureSeries
    };

    // Create a new SubstitutionResult with the HashMap
    const result = new SubstitutionResult();

    // Add time with key 'time' to result.getSubstitutions()
    // const time = new SubstitutionValue(sourceObject.get('time'), 'TEXTUAL', 'DEFAULT', false);
    // addToSubstitutionsMap(result, 'time', time);

    // Define temperature fragment mapping temperature -> c8y_Temperature.T.value/unit
    const temperature = new SubstitutionValue(fragmentTemperature,TYPE.OBJECT, RepairStrategy.DEFAULT, false);
    // Add temperature with key 'c8y_TemperatureMeasurement' to result.getSubstitutions()
    addToSubstitutionsMap(result, 'c8y_TemperatureMeasurement', temperature);

    // Define Device Identifier
    const deviceIdentifier = new SubstitutionValue(sourceObject.get('_TOPIC_LEVEL_')[1], TYPE.TEXTUAL,RepairStrategy.DEFAULT, false);
    // Add deviceIdentifier with key ctx.getGenericDeviceIdentifier() to result.getSubstitutions()
    addToSubstitutionsMap(result, ctx.getGenericDeviceIdentifier(), deviceIdentifier);

    return result;
}