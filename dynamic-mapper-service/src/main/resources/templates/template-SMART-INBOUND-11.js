/**
 * @name Bulk measurement collection (inbound)
 * @description Demonstrates how to send multiple measurements in a single Cumulocity
 *              REST call using cumulocityType: "measurementCollection".
 *
 *              All measurements in the array are for the same device (resolved via
 *              externalSource). The mapper injects source.id into every entry and
 *              posts to POST /measurement/measurements with
 *              Content-Type: application/vnd.com.nsn.cumulocity.measurementcollection+json.
 *
 *              Use this instead of returning N individual measurement objects when the
 *              incoming message contains time-series data for multiple time slots.
 *
 * @templateType INBOUND_SMART_FUNCTION
 * @direction INBOUND
 *
 * Sample payload (MQTT topic: testBulkMeasurement/sensor-berlin-01)
 * {
 *     "clientId": "sensor-berlin-01",
 *     "readings": [
 *         { "time": "2026-01-01T00:00:00Z", "temperature": 22.5, "humidity": 55.0 },
 *         { "time": "2026-01-01T00:01:00Z", "temperature": 23.1, "humidity": 54.2 },
 *         { "time": "2026-01-01T00:02:00Z", "temperature": 23.8, "humidity": 53.7 }
 *     ]
 * }
 */
function onMessage(msg, context) {
    var payload = msg.getPayload();
    var clientId = context.getClientId() || payload["clientId"];
    var readings = payload["readings"] || [];

    // Build the measurements array — do NOT include source; the mapper injects it.
    var measurements = readings.map(function(reading) {
        return {
            "type": "c8y_EnvironmentMeasurement",
            "time": reading["time"] || new Date().toISOString(),
            "c8y_Temperature": {
                "T": { "value": reading["temperature"], "unit": "C" }
            },
            "c8y_Humidity": {
                "H": { "value": reading["humidity"], "unit": "%" }
            }
        };
    });

    return [{
        cumulocityType: "measurementCollection",
        action: "create",
        payload: {
            measurements: measurements
        },
        externalSource: [{ "type": "c8y_Serial", "externalId": clientId }]
    }];
}

export {onMessage};
