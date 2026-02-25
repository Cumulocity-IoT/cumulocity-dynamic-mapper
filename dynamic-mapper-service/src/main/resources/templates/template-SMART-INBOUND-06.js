/**
 * @name Template for Smart Function with sourceId override
 * @description Demonstrates routing measurements to parent device using sourceId.
 *              This template shows how to send a child device's measurement to its parent
 *              by looking up the parent from assetParents in the inventory cache.
 * @templateType INBOUND_SMART_FUNCTION
 * @direction INBOUND
 * @defaultTemplate false
 * @internal true
 * @readonly true
 * @since 6.1.6
 *
 * Prerequisites:
 * - Enable inventory cache in connector configuration
 * - Add "assetParents" to inventoryFragmentsToCache list
 * - Ensure devices have parent-child relationships configured in Cumulocity
 *
 * Use Case:
 * A child device sends data, but you want the measurement to appear on the parent device.
 * For example: Multiple sensors on a production line sending data to the line's device group.
 *
*/

function onMessage(msg, context) {
    var payload = msg.getPayload();

    console.log("Processing message with sourceId override");
    console.log("Payload messageId: " + payload["messageId"]);

    // Get clientId from context first, fall back to payload
    var clientId = context.getClientId() || payload["clientId"];

    // Lookup the originating device using external ID
    var originatingDevice = context.getManagedObject({
        externalId: clientId,
        type: "c8y_Serial"
    });

    console.log("Originating device: " + JSON.stringify(originatingDevice));

    // Extract parent device ID from assetParents (if available)
    var targetDeviceId = null;

    if (originatingDevice && originatingDevice.assetParents && originatingDevice.assetParents.length > 0) {
        // Get the first parent device (you could also implement logic to select a specific parent)
        var firstParent = originatingDevice.assetParents[0];
        targetDeviceId = firstParent.id;

        console.log("Routing measurement to parent device - ID: " + targetDeviceId +
                    ", Name: " + firstParent.name +
                    ", Type: " + firstParent.type);
    } else {
        console.log("No parent devices found, measurement will go to originating device");
    }

    // Create measurement object with sourceId override
    return [{
        sourceId: targetDeviceId,  // NEW: Explicitly set target device (parent), set as first field
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
