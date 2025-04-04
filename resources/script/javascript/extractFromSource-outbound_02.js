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

    //Define temperature fragment mapping temperature -> c8y_Temperature.T.value/unit
    const temperature = new SubstitutionValue(fragmentTemperature, TYPE.OBJECT, RepairStrategy.DEFAULT, false);
    addToSubstitutionsMap(result, 'Temperature', temperature);

    //Use C8Y sourceId
    const deviceId = new SubstitutionValue(ctx.getC8YIdentifier(), TYPE.TEXTUAL, RepairStrategy.DEFAULT, false);
    // addToSubstitutionsMap(result, 'deviceId', deviceId);

    const source = {
        id: ctx.getC8YIdentifier()
    };
    const sourceFragment = new SubstitutionValue(source, TYPE.OBJECT, RepairStrategy.CREATE_IF_MISSING, false);
    addToSubstitutionsMap(result, 'source', sourceFragment);

    return result;
}