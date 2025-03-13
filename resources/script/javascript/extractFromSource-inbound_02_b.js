//
// sample to generate multiple measurements
//

// payload
// {
//     "temperature": [139.0, 150.0],
//     "externalId": "berlin_01"
//  }
// topic 'testGraalsMulti/berlin_01'

function extractFromSource(ctx) {

    //This is the source message as json
    const sourceObject = ctx.getJsonObject();
    // for (var key in sourceObject) {
    //     console.log(`key: ${key}, value: ${sourceObject.get(key)}`);  
    // }
    const tempArray = sourceObject.get('temperature');

    // Create a new SubstitutionResult with the HashMap
    const result = new SubstitutionResult();

    // Loop through all temperature array entries
    const size = tempArray.size(); 
    for (let i = 0; i < size; i++) {
        const temperatureValue = new SubstitutionValue(
            tempArray.get(i), 
            TYPE.NUMBER, 
            RepairStrategy.DEFAULT, 
            true
        );
        addToSubstitutionsMap(result, 'c8y_TemperatureMeasurement.T.value', temperatureValue);
    }

    //Define Device Identifier
    const deviceIdentifier = new SubstitutionValue(sourceObject.get('_TOPIC_LEVEL_')[1], TYPE.TEXTUAL, RepairStrategy.DEFAULT, false);
    addToSubstitutionsMap(result, ctx.getGenericDeviceIdentifier(), deviceIdentifier);

    return result;
}