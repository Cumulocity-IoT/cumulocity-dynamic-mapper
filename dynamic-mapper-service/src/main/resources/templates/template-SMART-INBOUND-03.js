/**
 * @name Default template for Smart Function
 * @description Default template for Smart Function, creates one measurement, create device with deviceType, deviceName
 * @templateType INBOUND_SMART_FUNCTION
 * @direction INBOUND
 * @defaultTemplate true
 * @internal true
 * @readonly true
 * 
*/

function onMessage(msg, context) {
    var payload = msg.getPayload();

    console.log("Context" + context.getStateAll());
    console.log("Payload Raw:" + payload);
    console.log("Payload messageId" +  payload.get("messageId"));

    // lookup device for enrichment
    var deviceByDeviceId = context.getManagedObjectByDeviceId(payload.get("deviceId"));
    console.log("Device (by device id): " + deviceByDeviceId);

    var deviceByExternalId = context.getManagedObject({ externalId: payload.get("clientId"), type: "c8y_Serial" } );
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
        externalSource: [{"type":"c8y_Serial", "externalId": payload.get("clientId")}],
        contextData: {"deviceName":"Test-Sensor", "deviceType": "sensor-type"}
    }];
}