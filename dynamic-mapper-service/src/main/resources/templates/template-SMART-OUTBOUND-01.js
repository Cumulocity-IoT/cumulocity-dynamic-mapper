/**
 * @name Default template for Smart Function
 * @description Creates one measurement
 * @templateType OUTBOUND_SMART_FUNCTION
 * @defaultTemplate true
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

function onMessage(msg, context) {
    var payload = msg.getPayload();

    // context.getConfig().externalId contains the resolved external id of the source device.
    // Requires the mapping to have 'useExternalId' enabled and an 'externalIdType' configured.
    const externalId = context.getConfig().externalId;

    return {
        topic: `measurements/${externalId}`,
        payload: {
            "time":  new Date().toISOString(),
            "c8y_Steam": {
                "Temperature": {
                "unit": "C",
                "value": payload["c8y_TemperatureMeasurement"]["T"]["value"]
                }
            }
        }
    };
}