const SubstitutionResult = Java.type('dynamic.mapping.processor.extension.internal.SubstitutionResult');
const Substitution = Java.type('dynamic.mapping.processor.extension.internal.Substitution');
function extractFromSource(ctx) {
    const jsonObject = ctx.getJsonObject();
    for (var key in jsonObject) {
        console.log(`key: ${key}, value: ${jsonObject.get(key)}`);
    }

    const fragmentTemperatureSeries = {
        value: jsonObject.get('temperature'),
        unit: jsonObject.get('unit')
    };

    const fragmentTemperature = {
        T: fragmentTemperatureSeries
    };
    // String key, Object value, MappingSubstitution.SubstituteValue.TYPE type, RepairStrategy repairStrategy
    return new SubstitutionResult([new Substitution(
        'time',
        jsonObject.get('time'),
        'TEXTUAL',
        'DEFAULT'
    ),
    new Substitution(
        'c8y_Temperature',
        fragmentTemperature,
        'OBJECT',
        'DEFAULT'
    ),
    new Substitution(
        'externalId',
        ctx.getGenericDeviceIdentifier(),
        'TEXTUAL',
        'DEFAULT'
    )]);
}