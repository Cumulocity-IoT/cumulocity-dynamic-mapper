/**
 * @name Template overriding method: POST, PATCH
 * @description This sample shows how to perform a partial update using the PATCH method
 * @templateType OUTBOUND
 * @defaultTemplate false
 * @internal true
 * @readonly true
 */

function extractFromSource(ctx) {
    // This is the source message as json
    const sourceObject = JSON.parse(ctx.getPayload());

    const input = sourceObject;
    const measurements = input['bytes'];

    // Define a new Measurement Value for Temperatures by assigning from source
    const w2a_configuration = {
        value: 'bsd_' + measurements[0]
    };

    // Create a new SubstitutionResult with the HashMap
    const result = new SubstitutionResult();

    // Substitution: String key, Object value, MappingSubstitution.SubstituteValue.TYPE type, RepairStrategy repairStrategy

    const w2a_configuration_fragment = new SubstitutionValue(w2a_configuration, TYPE.OBJECT, RepairStrategy.CREATE_IF_MISSING, false);
    addSubstitution(result, 'w2a_configuration', w2a_configuration_fragment);

    // Use C8Y sourceId
    const deviceId = new SubstitutionValue(ctx.getC8YIdentifier(), TYPE.TEXTUAL, RepairStrategy.DEFAULT, false);
    addSubstitution(result, '_TOPIC_LEVEL_[2]', deviceId);

    // Set method to PATCH to partially update a nested property
    const method = new SubstitutionValue('PATCH', TYPE.TEXTUAL, RepairStrategy.DEFAULT, false);
    addSubstitution (result, '_CONTEXT_DATA_.method', method)

    return result;
}