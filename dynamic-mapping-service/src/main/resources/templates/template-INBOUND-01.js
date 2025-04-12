/**
 * @name Default INBOUND, one measurement
 * @description Default INBOUND template to generate one measurement
 * @templateType INBOUND
 * @defaultTemplate true
 * @internal true
 * @readonly true
 * 
 * Default INBOUND template to generate one measurement
 */

/**
   sample to generate one measurement
  
   payload
   {
       "temperature": 139.0,
       "unit": "C",
       "externalId": "berlin_01"
    }
   topic 'testGraalsSingle/berlin_01'
*/

function extractFromSource(ctx) {
    //This is the source message as json
    const sourceObject = JSON.parse(ctx.getPayload());

    for (var key in sourceObject) {
        console.log(`key: ${key}, value: ${sourceObject[key]}`);
    }

    // Define a new Measurement Value for Temperatures by assigning from source
    const fragmentTemperatureSeries = {
        value: sourceObject['temperature'],
        unit: sourceObject['unit']
    };

    // Assign Values to Series
    const fragmentTemperature = {
        T: fragmentTemperatureSeries
    };

    // Create a new SubstitutionResult with the HashMap
    const result = new SubstitutionResult();

    // Add time with key 'time' to result.getSubstitutions()
    // const time = new SubstitutionValue(sourceObject['time'], 'TEXTUAL', 'DEFAULT', false);
    // addToSubstitutionsMap(result, 'time', time);

    // Define temperature fragment mapping temperature -> c8y_Temperature.T.value/unit
    const temperature = new SubstitutionValue(fragmentTemperature, TYPE.OBJECT, RepairStrategy.DEFAULT, false);
    // Add temperature with key 'c8y_TemperatureMeasurement' to result.getSubstitutions()
    addToSubstitutionsMap(result, 'c8y_TemperatureMeasurement', temperature);

    // Define Device Identifier
    const deviceIdentifier = new SubstitutionValue(sourceObject['_TOPIC_LEVEL_'][1], TYPE.TEXTUAL, RepairStrategy.DEFAULT, false);
    // Add deviceIdentifier with key ctx.getGenericDeviceIdentifier() to result.getSubstitutions()
    addToSubstitutionsMap(result, ctx.getGenericDeviceIdentifier(), deviceIdentifier);

    return result;
}
