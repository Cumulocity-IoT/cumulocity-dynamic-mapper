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
    const sourceObject = JSON.parse(ctx.getPayload());
    // for (var key in sourceObject) {
    //     console.log(`key: ${key}, value: ${sourceObject[key]}`);  
    // }
    const tempArray = sourceObject['temperature'];

    // Create a new SubstitutionResult with the HashMaps
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
        addSubstitution(result, 'c8y_TemperatureMeasurement.T.value', temperatureValue);
    }

    //Define Device Identifier
    const deviceIdentifier = new SubstitutionValue(sourceObject['_TOPIC_LEVEL_'][1], TYPE.TEXTUAL, RepairStrategy.DEFAULT, false);
    addSubstitution(result, ctx.getGenericDeviceIdentifier(), deviceIdentifier);

    // Overwrite api 
    const api = new SubstitutionValue('ALARM', TYPE.TEXTUAL, RepairStrategy.DEFAULT, false);
    addSubstitution(result, '_CONTEXT_DATA_.api', api);

    return result;
}