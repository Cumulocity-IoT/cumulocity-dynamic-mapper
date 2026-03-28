/**
 * @name Smart Function using device data for enrichment
 * @description Creates either c8y_CurrentMeasurement or c8y_VoltageMeasurement, depending on the inventory data (enrichment)
 * @templateType INBOUND_SMART_FUNCTION
 * @defaultTemplate false
 * @internal true
 * @readonly true
 *
 * Sample payload
 * {
 *     "messageId": "msg-001",
 *     "deviceId": "12345",
 *     "sensorData": {
 *         "val": 230.5
 *     }
 * }
 * topic 'testSmartInbound/sensor-berlin-01'
 * The externalId is extracted from context.getConfig().topic split by '/' at index 1, e.g. "sensor-berlin-01".
 * Note: The device inventory must have c8y_Sensor.type.voltage=true or c8y_Sensor.type.current=true
*/

function onMessage(msg, context) {
    var payload = msg.getPayload();

    console.log("Payload Raw:" + payload);
    console.log("Payload messageId: " +  payload["messageId"]);

    // Extract externalId from the topic via context.getConfig().
    // For topic 'testSmartInbound/sensor-berlin-01', index 1 gives 'sensor-berlin-01'.
    var config = context.getConfig();
    var topicSegments = config["topic"] ? config["topic"].split("/") : [];
    var externalId = topicSegments[1] || null;

    if (!externalId) {
        console.error("Cannot determine externalId: topic segment [1] is missing. Config: " + JSON.stringify(config));
        return [];
    }

    // lookup device by c8y internal id for enrichment
    try {
        var deviceByDeviceId = context.getManagedObject(payload["deviceId"]);
        console.log("Device (by device id): " + deviceByDeviceId);
    } catch (e) {
        console.error("Failed to lookup device by deviceId '" + payload["deviceId"] + "': " + (e.message || e));
    }

    // lookup device by externalId for enrichment
    try {
        var deviceByExternalId = context.getManagedObjectByExternalId({ externalId: externalId, type: "c8y_Serial" });
        console.log("Device (by external id): " + deviceByExternalId);
    } catch (e) {
        console.error("Failed to lookup device by externalId '" + externalId + "': " + (e.message || e));
    }

    // Determine measurement type based on device configuration.
    // Use deviceByDeviceId as primary source since it is resolved from the explicit
    // deviceId in the payload and is guaranteed to be the correct device.
    var device = deviceByDeviceId || deviceByExternalId;
    var isVoltage = device?.c8y_Sensor?.type?.voltage === true;
    var isCurrent = device?.c8y_Sensor?.type?.current === true;

    var measurementPayload;

    if (isVoltage) {
        measurementPayload = {
            "time": new Date().toISOString(),
            "type": "c8y_VoltageMeasurement",
            "c8y_Voltage": {
                "voltage": {
                    "unit": "V",
                    "value": payload["sensorData"]["val"]
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
                    "value": payload["sensorData"]["val"]
                }
            }
        };
        console.log("Creating c8y_CurrentMeasurement");
    } else {
        console.log("Warning: No valid sensor type found for device '" + externalId +
            "'. Inventory must have c8y_Sensor.type.voltage=true or c8y_Sensor.type.current=true." +
            " Got: " + JSON.stringify(device));
        return []; // Return empty array if no valid configuration
    }

    return [{
        cumulocityType: "measurement",
        action: "create",
        payload: measurementPayload,
        externalSource: [{"type":"c8y_Serial", "externalId": externalId}]
    }];
}