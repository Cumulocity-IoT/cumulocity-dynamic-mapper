/**
 * @name Snippet shows overriding API INBOUND
 * @description Snippet shows overriding API INBOUND
 * @templateType INBOUND
 * @defaultTemplate false
 * @internal true
 * @readonly true
 * 
*/

function extractFromSource(ctx) {
    //This is the source message as json
    const sourceObject = JSON.parse(ctx.getPayload());

    for (var key in sourceObject) {
        console.log(`key: ${key}, value: ${sourceObject[key]}`);
    }

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
