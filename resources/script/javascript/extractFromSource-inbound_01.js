const SubstitutionResult = Java.type('dynamic.mapping.processor.model.SubstitutionResult');
const SubstitutionValue = Java.type('dynamic.mapping.processor.model.SubstituteValue');

function extractFromSource(ctx) {

    //This is the source message as json
    const sourceObject = ctx.getJsonObject();
    for (var key in sourceObject) {
        console.log(`key: ${key}, value: ${sourceObject.get(key)}`);  
    }

    //Define a new Measurement Value for Temperatures by assigning from source
    const fragmentTemperatureSeries = {
        value: sourceObject.get('temperature'),
        unit: sourceObject.get('unit')
    };

    //Assign Values to Series
    const fragmentTemperature = {
        T: fragmentTemperatureSeries
    };
   
    // Substitution: String key, Object value, MappingSubstitution.SubstituteValue.TYPE type, RepairStrategy repairStrategy
    //Define time mapping time -> time
    const time = new Substitution('time', sourceObject.get('time'), 'TEXTUAL', 'DEFAULT');
    
    //Define temperature fragment mapping temperature -> c8y_Temperature.T.value/unit
    const temperature = new Substitution('c8y_TemperatureMeasurement', fragmentTemperature, 'OBJECT', 'DEFAULT');

    //Define Device Identifier
    const deviceIdentifier = new Substitution(ctx.getGenericDeviceIdentifier(), sourceObject.get('_TOPIC_LEVEL_')[1], 'TEXTUAL', 'DEFAULT');
    
    //Return undefined to skip the current message for further processing
    //return undefined;
    
    return new SubstitutionResult([deviceIdentifier, time, temperature]);
}
