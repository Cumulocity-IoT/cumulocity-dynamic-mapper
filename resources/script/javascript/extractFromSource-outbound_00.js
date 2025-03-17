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

    // Substitution: String key, Object value, MappingSubstitution.SubstituteValue.TYPE type, RepairStrategy repairStrategy
    //Define time mapping time -> time
    const time = new Substitution('time', sourceObject.get('time'), 'TEXTUAL', 'DEFAULT');
    
    //Define temperature fragment mapping temperature -> c8y_Temperature.T.value/unit
    const temperature = new Substitution('Temperature', fragmentTemperature, 'OBJECT', 'DEFAULT');

    //Define Device Identifier
    const deviceIdentifier = new Substitution('_TOPIC_LEVEL_[1]', ctx.getExternalIdentifier(), 'TEXTUAL', 'DEFAULT');

    //Use C8Y sourceId
    const deviceId = new Substitution('deviceId', ctx.getC8YIdentifier(), 'TEXTUAL', 'DEFAULT');

    return new SubstitutionResult([deviceIdentifier, time, temperature, deviceId]);
}