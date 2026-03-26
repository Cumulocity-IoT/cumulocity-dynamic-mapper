/**
 * @name Create measurement and implicitly create device with deviceFragments and deviceGroups
 * @description Creates one measurement and implicitly creates a device with deviceType, deviceName,
 *              custom managed object fragments (deviceFragments), and group assignment (deviceGroups).
 *              The deviceFragments are merged into the device managed object when the device is created
 *              for the first time. The deviceGroups list names the device groups the device is added to
 *              as a child asset; missing groups are created automatically.
 * @templateType INBOUND_SMART_FUNCTION
 * @direction INBOUND
 * @defaultTemplate false
 * @internal true
 * @readonly true
 *
 * Sample payload
 * {
 *     "messageId": "msg-001",
 *     "clientId": "sensor-berlin-01",
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
    console.log("Payload messageId" + payload["messageId"]);

    // Get clientId from context first, fall back to payload
    var clientId = context.getClientId() || payload["clientId"];

    return [{
        cumulocityType: "measurement",
        action: "create",
        payload: {
            "time": new Date().toISOString(),
            "type": "c8y_TemperatureMeasurement",
            "c8y_Steam": {
                "Temperature": {
                    "unit": "C",
                    "value": payload["sensorData"]["temp_val"]
                }
            }
        },
        externalSource: [{"type": "c8y_Serial", "externalId": clientId}],
        contextData: {
            "deviceName": "Test-Sensor",           // display name of the implicitly created device
            "deviceType": "sensor-type",           // managed object type of the implicitly created device
            "deviceGroups": ["line 1", "line 2"],  // groups the device is assigned to as child asset
            "deviceFragments": {                   // additional fragments merged into the device managed object
                "c8y_Hardware": {
                    "model":        "SmartSensor v2",
                    "serialNumber": clientId,
                    "revision":     "2.0"
                },
                "c8y_SupportedOperations": ["c8y_Restart", "c8y_Configuration"]
            }
        }
    }];
}