const SubstitutionResult = Java.type('dynamic.mapping.processor.extension.internal.SubstitutionResult');
function extractFromSource(ctx) {
    const jsonObject= ctx.getJsonObject();
    for (var key in jsonObject) {
        console.log(`key: ${key}, value: ${jsonObject.get(key)}`);
    }
    return new SubstitutionResult({
        temperature : jsonObject.get('temperature')
    });
}