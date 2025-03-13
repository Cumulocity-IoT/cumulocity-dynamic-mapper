function extractFromSource(ctx) {
    //This is the source message as json
    const sourceObject = ctx.getJsonObject();

    //Log c8y sourceId
    //console.log(`C8Y sourceId: ${ctx.getC8YIdentifier()}`);
    //console.log(`C8Y extenalIdentifier: ${ctx.getExternalIdentifier()}`);

    // for (var key in sourceObject) {
    //     console.log(`key: ${key}, value: ${sourceObject.get(key)}`);  
    // }

    //Define a new Measurement Value for Temperatures by assigning from source
    const fragmentTemperature = {
        value: sourceObject.get('c8y_TemperatureMeasurement').get('T').get('value'),
        unit: sourceObject.get('c8y_TemperatureMeasurement').get('T').get('unit')
    };

    // Create a new SubstitutionResult with the HashMap
    const result = new SubstitutionResult();

    // Substitution: String key, Object value, MappingSubstitution.SubstituteValue.TYPE type, RepairStrategy repairStrategy
    //Define time mapping time -> time
    const time = new SubstitutionValue(sourceObject.get('time'), TYPE.TEXTUAL, RepairStrategy.DEFAULT, false);
    addToSubstitutionsMap(result, 'time', time);
    
    //Define temperature fragment mapping temperature -> c8y_Temperature.T.value/unit
    const temperature = new SubstitutionValue( fragmentTemperature, TYPE.OBJECT,RepairStrategy.DEFAULT, false);
    addToSubstitutionsMap(result, 'Temperature', temperature);

    //Define Device Identifier
    const deviceIdentifier = new SubstitutionValue( ctx.getExternalIdentifier(), TYPE.TEXTUAL, RepairStrategy.DEFAULT, false);
    addToSubstitutionsMap(result, '_TOPIC_LEVEL_[1]', deviceIdentifier);

    //Use C8Y sourceId
    const deviceId = new SubstitutionValue(ctx.getC8YIdentifier(), TYPE.TEXTUAL, RepairStrategy.DEFAULT, false);
    addToSubstitutionsMap(result, 'deviceId', deviceId);

    return result;
}