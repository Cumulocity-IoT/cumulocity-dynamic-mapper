/**
 * @name Template using C8Y source id
 * @description This sample show how to create a new outgoing payload using C8Y source id
 * @templateType OUTBOUND_SUBSTITUTION_AS_CODE
 * @direction OUTBOUND
 * @defaultTemplate false
 * @internal true
 * @readonly true
 *
 * Sample Cumulocity measurement payload (source)
 * {
 *     "time": "2025-01-01T12:00:00.000Z",
 *     "type": "c8y_TemperatureMeasurement",
 *     "c8y_TemperatureMeasurement": {
 *         "T": {
 *             "value": 23.5,
 *             "unit": "C"
 *         }
 *     },
 *     "source": { "id": "12345" }
 * }
 * publishTopic 'measurements/berlin_01'
 */

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
    addSubstitution(result, 'time', time);

    const temperature = new SubstitutionValue(fragmentTemperature, TYPE.OBJECT, RepairStrategy.DEFAULT, false);
    addSubstitution(result, 'Temperature', temperature);

    // define source fragment to send payload as measurement to Cumulocity
    const source = {
        id: ctx.getC8YIdentifier()
    };
    const sourceFragment = new SubstitutionValue(source, TYPE.OBJECT, RepairStrategy.CREATE_IF_MISSING, false);
    addSubstitution(result, 'source', sourceFragment);

    return result;
}