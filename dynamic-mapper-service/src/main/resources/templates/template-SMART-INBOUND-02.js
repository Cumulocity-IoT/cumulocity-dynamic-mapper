/**
 * @name Smart Function using device data for enrichment
 * @description Creates either c8y_CurrentMeasurement or c8y_VoltageMeasurement, depending on the inventory data (enrichment)
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

    // testing lookup device by deviceId for enrichment
    try {
        var deviceByDeviceId = context.getManagedObjectByDeviceId(payload.get("deviceId"));
        console.log("Device (by device id): " + deviceByDeviceId);
    } catch (e) {
        console.log(e);
    }

    // testing lookup device by externalId for enrichment
    try {
        var deviceByExternalId = context.getManagedObject({ externalId: clientId, type: "c8y_Serial" });
        console.log("Device (by external id): " + deviceByExternalId);
    } catch (e) {
        console.log(e);
    }

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
        console.log("Creating c8y_VoltageMeasurement");
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
        console.log("Creating c8y_CurrentMeasurement");
    } else {
        console.log("Warning: No valid sensor type configuration found");
        return []; // Return empty array if no valid configuration
    }

    return [{
        cumulocityType: "measurement",
        action: "create",
        payload: measurementPayload,
        externalSource: [{"type":"c8y_Serial", "externalId": payload.get("clientId")}]
    }];
}