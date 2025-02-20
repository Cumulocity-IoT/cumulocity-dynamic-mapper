const SubstitutionResult = Java.type('dynamic.mapping.processor.extension.internal.SubstitutionResult');
function map(ctx) {
    return new SubstitutionResult({
        myNameIs: ctx.getName(),
        myValueIs: ctx.getValue()
    });
}