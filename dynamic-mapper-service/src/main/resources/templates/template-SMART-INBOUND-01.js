/**
 * @name Default template for Smart Function
 * @description Default template for Smart Function, creates one measurement
 * @templateType INBOUND_SMART_FUNCTION
 * @direction INBOUND
 * @defaultTemplate true
 * @internal true
 * @readonly true
 *
 * Sample payload
 * {
 *     "messageId": "msg-001",
 *     "clientId": "sensor-berlin-01",
 *     "deviceId": "12345",
 *     "sensorData": {
 *         "temp_val": 23.5
 *     }
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

    // lookup device by c8y internal id for enrichment
    var deviceByDeviceId = context.getManagedObject(payload["deviceId"]);
    console.log("Device (by device id): " + deviceByDeviceId);

    // lookup device by externalId for enrichment
    var deviceByExternalId = context.getManagedObjectByExternalId({ externalId: clientId, type: "c8y_Serial" });
    console.log("Device (by external id): " + deviceByExternalId);

    return [{
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
    }];
}