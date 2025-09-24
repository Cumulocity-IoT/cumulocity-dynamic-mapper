/**
 * @name Snippet shows setting method, publishTopic
 * @description This sample shows how to perform a partial update using the PATCH method
 * @templateType OUTBOUND_SUBSTITUTION_AS_CODE
 * @direction OUTBOUND
 * @defaultTemplate false
 * @internal true
 * @readonly true
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

    // Create a new SubstitutionResult with the HashMap
    const result = new SubstitutionResult();

    // Use C8Y sourceId
    const deviceId = new SubstitutionValue(ctx.getC8YIdentifier(), TYPE.TEXTUAL, RepairStrategy.DEFAULT, false);
    addSubstitution(result, '_TOPIC_LEVEL_[2]', deviceId);

    // Define method 
    const method = new SubstitutionValue('PATCH', TYPE.TEXTUAL, RepairStrategy.DEFAULT, false);
    addSubstitution (result, '_CONTEXT_DATA_.method', method)

    // Override publishTopic 
    const publishTopic = new SubstitutionValue('events/event', TYPE.TEXTUAL, RepairStrategy.DEFAULT, false);
    addSubstitution (result, '_CONTEXT_DATA_.publishTopic', publishTopic)

    return result;
}