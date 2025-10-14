/**
 * @name Template for Smart Function
 * @description Template for Smart Function, creates either c8y_CurrentMeasurement or c8y_VoltageMeasurement, depending on the inventory data (enrichment)
 * @templateType INBOUND_SMART_FUNCTION
 * @direction INBOUND
 * @defaultTemplate false
 * @internal true
 * @readonly true
 * 
*/

function onMessage(inputMsg, context) {
    const msg = inputMsg; 

    var payload = msg.getPayload();

    context.logMessage("Context" + context.getStateAll());
    context.logMessage("Payload Raw:" + msg.getPayload());
    context.logMessage("Payload messageId" +  msg.getPayload().get("messageId"));

    // lookup device for enrichment
    var deviceByDeviceId = context.lookupDeviceByDeviceId(payload.get("deviceId"));
    context.logMessage("Device (by device id): " + deviceByDeviceId);

    var deviceByExternalId = context.lookupDeviceByExternalId(payload.get("clientId"), "c8y_Serial" );
    context.logMessage("Device (by external id): " + deviceByExternalId);

    // Determine measurement type based on device configuration
    var isVoltage = deviceByExternalId?.c8y_Sensor?.type?.voltage === true;
    var isCurrent = deviceByExternalId?.c8y_Sensor?.type?.current === true;

    var measurementPayload;

    if (isVoltage) {
        measurementPayload = {
            "time": new Date().toISOString(),
            "type": "c8y_VoltageMeasurement",
            "c8y_Voltage": {
                "voltage": {
                    "unit": "V",
                    "value": payload.get("sensorData").get("val")
                }
            }
        };
        context.logMessage("Creating c8y_VoltageMeasurement");
    } else if (isCurrent) {
        measurementPayload = {
            "time": new Date().toISOString(),
            "type": "c8y_CurrentMeasurement",
            "c8y_Current": {
                "current": {
                    "unit": "A",
                    "value": payload.get("sensorData").get("val")
                }
            }
        };
        context.logMessage("Creating c8y_CurrentMeasurement");
    } else {
        context.logMessage("Warning: No valid sensor type configuration found");
        return []; // Return empty array if no valid configuration
    }

    return [{
        cumulocityType: "measurement",
        action: "create",
        payload: measurementPayload,
        externalSource: [{"type":"c8y_Serial", "externalId": payload.get("clientId")}]
    }];
}