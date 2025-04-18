/**
 * @name INBOUND multiple measurements
 * @description INBOUND template to generate multiple measurements
 * @templateType INBOUND
 * @defaultTemplate false
 * @internal true
 * @readonly true
 * 
 * INBOUND template to generate multiple measurements
 */

/**
   sample to generate multiple measurements
  

   payload
   {
       "temperature": [139.0, 150.0],
       "externalId": "berlin_01"
    }
   topic 'testGraalsMulti/berlin_01'
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
        addToSubstitutionsMap(result, 'c8y_TemperatureMeasurement.T.value', temperatureValue);
    }

    //Define Device Identifier
    const deviceIdentifier = new SubstitutionValue(sourceObject['_TOPIC_LEVEL_'][1], TYPE.TEXTUAL, RepairStrategy.DEFAULT, false);
    addToSubstitutionsMap(result, ctx.getGenericDeviceIdentifier(), deviceIdentifier);

    // Overwrite api 
    const api = new SubstitutionValue('ALARM', TYPE.TEXTUAL, RepairStrategy.DEFAULT, false);
    addToSubstitutionsMap(result, '_CONTEXT_DATA_.api', api);

    return result;
}