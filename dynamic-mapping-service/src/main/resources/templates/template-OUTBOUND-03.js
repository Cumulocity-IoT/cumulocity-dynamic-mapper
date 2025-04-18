/**
 * @name OUTBOUND setting methods
 * @description This sample shows how to perform a partial update using the PATCH method
 * @templateType OUTBOUND
 * @defaultTemplate false
 * @internal true
 * @readonly true
 * 
 * This sample shows how to perform a partial update using the PATCH method
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

    //Log c8y sourceId
    //console.log(`C8Y sourceId: ${ctx.getC8YIdentifier()}`);
    //console.log(`C8Y externalIdentifier: ${ctx.getExternalIdentifier()}`);

    //for (var key in sourceObject) {
    //     console.log(`key: ${key}, value: ${sourceObject[key]}`);  
    // }
    const input = sourceObject;
    const measurements = input['bytes'];

    //Define a new Measurement Value for Temperatures by assigning from source
    const w2a_configuration = {
        value: 'bsd_' + measurements[0]
    };

    // Create a new SubstitutionResult with the HashMap
    const result = new SubstitutionResult();

    // Substitution: String key, Object value, MappingSubstitution.SubstituteValue.TYPE type, RepairStrategy repairStrategy

    const w2a_configuration_fragment = new SubstitutionValue(w2a_configuration, TYPE.OBJECT, RepairStrategy.CREATE_IF_MISSING, false);
    addToSubstitutionsMap(result, 'w2a_configuration', w2a_configuration_fragment);

    //Use C8Y sourceId
    const deviceId = new SubstitutionValue(ctx.getC8YIdentifier(), TYPE.TEXTUAL, RepairStrategy.DEFAULT, false);
    addToSubstitutionsMap(result, '_TOPIC_LEVEL_[2]', deviceId);

    const method = new SubstitutionValue('PATCH', TYPE.TEXTUAL, RepairStrategy.DEFAULT, false);
    addToSubstitutionsMap (result, '_CONTEXT_DATA_.method', method)

    return result;
}