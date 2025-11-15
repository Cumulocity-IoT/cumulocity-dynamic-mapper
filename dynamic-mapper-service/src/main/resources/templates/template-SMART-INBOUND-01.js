/**
 * @name Default template for Smart Function
 * @description Default template for Smart Function, creates one measurement
 * @templateType INBOUND_SMART_FUNCTION
 * @direction INBOUND
 * @defaultTemplate true
 * @internal true
 * @readonly true
 * 
*/

function onMessage(msg, context) {
    var payload = msg.getPayload();

    context.logMessage("Context" + context.getStateAll());
    context.logMessage("Payload Raw:" + payload);
    context.logMessage("Payload messageId" +  payload.get("messageId"));

    // lookup device for enrichment
    var deviceByDeviceId = context.lookupDeviceByDeviceId(payload.get("deviceId"));
    context.logMessage("Device (by device id): " + deviceByDeviceId);

    var deviceByExternalId = context.lookupDeviceByExternalId(payload.get("clientId"), "c8y_Serial" );
    context.logMessage("Device (by external id): " + deviceByExternalId);

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

        externalSource: [{"type":"c8y_Serial", "externalId": payload.get("clientId")}]
    }];
}