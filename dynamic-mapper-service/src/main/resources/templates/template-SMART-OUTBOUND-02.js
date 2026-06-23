/**
 * @name Creates one measurement as array
 * @description Creates one measurement as array
 * @templateType OUTBOUND_SMART_FUNCTION
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

function onMessage(msg, context) {
    var payload = msg.payload;

    // context.getExternalId() returns the resolved external id of the source device.
    // Requires the mapping to have 'useExternalId' enabled and an 'externalIdType' configured.
    const externalId = context.getExternalId();
    // C8Y measurement payloads always carry a 'time' field; fall back to current time
    var time = payload["time"] || new Date().toISOString();

    return [{
        topic: `measurements/${externalId}`,
        payload: [{
            "time":  time,
            "c8y_Steam": {
                "Temperature": {
                "unit": "C",
                "value": payload["c8y_TemperatureMeasurement"]["T"]["value"]
                }
            }
        }]
    }];
}
export {onMessage};
