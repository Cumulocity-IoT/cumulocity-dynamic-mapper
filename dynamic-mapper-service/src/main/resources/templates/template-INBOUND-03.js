/**
 * @name Snippet shows overriding API
 * @description Snippet shows overriding API: ALARM, EVENT MEASUREMENT, ...
 * @templateType INBOUND_SUBSTITUTION_AS_CODE
 * @direction INBOUND
 * @defaultTemplate false
 * @internal true
 * @readonly true
*/

function extractFromSource(ctx) {
    // This is the source message as json
    const sourceObject = JSON.parse(ctx.getPayload());

    // Create a new SubstitutionResult with the HashMap
    const result = new SubstitutionResult();

    // Override API 
    const api = new SubstitutionValue('ALARMS', TYPE.TEXTUAL, RepairStrategy.DEFAULT, false);
    addSubstitution (result, '_CONTEXT_DATA_.api', api)

    // Define Device Identifier
    const deviceIdentifier = new SubstitutionValue(sourceObject['_TOPIC_LEVEL_'][1], TYPE.TEXTUAL, RepairStrategy.DEFAULT, false);
    // Add deviceIdentifier with key ctx.getGenericDeviceIdentifier() to result.getSubstitutions()
    addSubstitution(result, ctx.getGenericDeviceIdentifier(), deviceIdentifier);

    return result;
}
