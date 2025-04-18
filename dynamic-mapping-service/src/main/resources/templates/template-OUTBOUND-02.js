/**
 * @name OUTBOUND using C8Y source id
 * @description This sample show how to create a new outgoing payload using C8Y source id
 * @templateType OUTBOUND
 * @defaultTemplate false
 * @internal true
 * @readonly true
 * 
 * This sample show how to create a new outgoing payload
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

    // Create a new SubstitutionResult with the HashMap
    const result = new SubstitutionResult();

    //Define a new Measurement Value for Temperatures by assigning from source
    const fragmentTemperature = {
        value: sourceObject['c8y_TemperatureMeasurement']['T']['value'],
        unit: sourceObject['c8y_TemperatureMeasurement']['T']['unit']
    };

    // SubstitutionValue: String key, Object value, SubstituteValue.TYPE type, RepairStrategy repairStrategy
    //Define time mapping time -> time
    const time = new SubstitutionValue(sourceObject['time'], TYPE.TEXTUAL, RepairStrategy.DEFAULT, false);
    addToSubstitutionsMap(result, 'time', time);

    const temperature = new SubstitutionValue(fragmentTemperature, TYPE.OBJECT, RepairStrategy.DEFAULT, false);
    addToSubstitutionsMap(result, 'Temperature', temperature);

    const source = {
        id: ctx.getC8YIdentifier()
    };
    const sourceFragment = new SubstitutionValue(source, TYPE.OBJECT, RepairStrategy.CREATE_IF_MISSING, false);
    addToSubstitutionsMap(result, 'source', sourceFragment);

    return result;
}