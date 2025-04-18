/**
 * @name Default INBOUND, one measurement
 * @description Default INBOUND template to generate one measurement
 * @templateType INBOUND
 * @defaultTemplate true
 * @internal true
 * @readonly true
 * 
 * Default INBOUND template to generate one measurement

 * sample to generate one measurement
  
 * payload
 * {
 *     "temperature": 139.0,
 *     "unit": "C",
 *     "externalId": "berlin_01"
 *  }
 * topic 'testGraalsSingle/berlin_01'
*/

/**
 * Extracts data from the source payload to be used in substitutions during mapping.
 * 
 * This function is called during the evaluation at runtime to define the substitution values
 * that will be applied to the target payload. It analyzes the source payload and
 * creates the necessary substitution values based on the mapping configuration.
 * 
 * @function extractFromSource
 * @param {SubstitutionContext} ctx - Context object containing:
 *   @param {Object} ctx.getPayload() - The source payload to extract data from
 *   @param {string} ctx.getGenericDeviceIdentifier() - Name of device identifier, i.e. either "_IDENTITY_.externalId" or "_IDENTITY_.c8ySourceId" 
 *   @param {string} ctx.getExternalDeviceIdentifier() - Device identifier used in external systems
 *   @param {string} ctx.getC8YDeviceIdentifier() - Cumulocity platform device identifier
 * 
 * @returns {SubstitutionResult} A result object populated using method addToSubstitutionsMap(result, key, value), containing:
 *   @returns {Object.<string, SubstitutionValue>} substitutions - Key-value pairs of substitution values
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
