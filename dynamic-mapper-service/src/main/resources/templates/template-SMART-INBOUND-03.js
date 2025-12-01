/**
 * @name Create measurement and define implicitly create device
 * @description Creates one measurement and implicitly create device with deviceType, deviceName
 * @templateType INBOUND_SMART_FUNCTION
 * @direction INBOUND
 * @defaultTemplate false
 * @internal true
 * @readonly true
 * 
*/

function onMessage(msg, context) {
    var payload = msg.getPayload();

    console.log("Context" + context.getStateAll());
    console.log("Payload Raw:" + payload);
    console.log("Payload messageId" +  payload.get("messageId"));

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
        contextData: {"deviceName":"Test-Sensor", "deviceType": "sensor-type"} // specify the name and type of the new implicitly created device
    }];
}