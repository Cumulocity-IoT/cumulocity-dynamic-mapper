/**
 * @description Default OUTBOUND template
 * @name Default OUTBOUND template, use external identifier
 * @templateType OUTBOUND
 * @defaultTemplate true
 * @internal true
 * @readonly true
 * 
 * Default OUTBOUND template, use external identifier
 */

function extractFromSource(ctx) {
    // This is the source message as json
    const sourceObject = JSON.parse(ctx.getPayload());

    // Log c8y sourceId
    // console.log(`C8Y sourceId: ${ctx.getC8YIdentifier()}`);
    // console.log(`C8Y externalIdentifier: ${ctx.getExternalIdentifier()}`);

    // for (var key in sourceObject) {
    //     console.log(`key: ${key}, value: ${sourceObject[key]}`);  
    //  }

    // Define a new Measurement Value for Temperatures by assigning from source
    const fragmentTemperature = {
        value: sourceObject['c8y_TemperatureMeasurement']['T']['value'],
        unit: sourceObject['c8y_TemperatureMeasurement']['T']['unit']
    };

    // Create a new SubstitutionResult with the HashMap
    const result = new SubstitutionResult();

    // Substitution: String key, Object value, MappingSubstitution.SubstituteValue.TYPE type, RepairStrategy repairStrategy
    // Define time mapping time -> time
    const time = new SubstitutionValue(sourceObject['time'], TYPE.TEXTUAL, RepairStrategy.DEFAULT, false);
    addSubstitution(result, 'time', time);

    // Define temperature fragment mapping temperature -> c8y_Temperature.T.value/unit
    const temperature = new SubstitutionValue(fragmentTemperature, TYPE.OBJECT, RepairStrategy.DEFAULT, false);
    addSubstitution(result, 'Temperature', temperature);

    // Define Device Identifier
    const deviceIdentifier = new SubstitutionValue(ctx.getExternalIdentifier(), TYPE.TEXTUAL, RepairStrategy.DEFAULT, false);
    addSubstitution(result, '_TOPIC_LEVEL_[1]', deviceIdentifier);

    // Use C8Y sourceId
    const deviceId = new SubstitutionValue(ctx.getC8YIdentifier(), TYPE.TEXTUAL, RepairStrategy.DEFAULT, false);
    addSubstitution(result, 'deviceId', deviceId);

    return result;
}