/**
 * @name Create either measurement or event
 * @description Create either measurement or event
 * @templateType INBOUND_SMART_FUNCTION
 * @direction INBOUND
 * @defaultTemplate false
 * @internal true
 * @readonly true
 *
 * Sample payload (telemetry)
 * {
 *     "messageId": "msg-001",
 *     "clientId": "sensor-berlin-01",
 *     "payloadType": "telemetry",
 *     "sensorData": {
 *         "temp_val": 23.5
 *     }
 * }
 * Sample payload (error)
 * {
 *     "messageId": "msg-002",
 *     "clientId": "sensor-berlin-01",
 *     "payloadType": "error",
 *     "logMessage": "Sensor malfunction detected"
 * }
 * topic 'testSmartInbound/sensor-berlin-01'
*/

function onMessage(msg, context) {
    var payload = msg.getPayload();

    console.log("Context" + context.getStateAll());
    console.log("Payload Raw:" + payload);
    console.log("Payload messageId" +  payload["messageId"]);

    // Get clientId from context first, fall back to payload
    var clientId = context.getClientId() || payload["clientId"];

    let result;
    const payloadType = payload["payloadType"];

    if (payloadType == "telemetry") {
        result = {
            cumulocityType: "measurement",
            action: "create",
            payload: {
                "time":  new Date().toISOString(),
                "type": "c8y_TemperatureMeasurement",
                "c8y_Steam": {
                    "Temperature": {
                    "unit": "C",
                    "value": payload["sensorData"]["temp_val"]
                    }
                }
            },
            externalSource: [{"type":"c8y_Serial", "externalId": clientId}]
        }
    } else {
        // if type == "error"
        result = {
            cumulocityType: "event",
            action: "create",
            payload: {
                "time":  new Date().toISOString(),
                "type": "c8y_ErrorEvent",
                "text": payload["logMessage"],
                "severity": "MAJOR",
                "status": "ACTIVE"
            },
            externalSource: [{"type":"c8y_Serial", "externalId": clientId}]
        }
    }
    return result;
}