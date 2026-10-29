// @ts-check
/**
 * @name Create or clear an alarm
 * @description Creates a Cumulocity alarm from a device fault message and clears it again when the
 *              device reports the fault as gone. Level 1-4 map to WARNING, MINOR, MAJOR and
 *              CRITICAL (status ACTIVE); level 0 sends the same alarm type with status CLEARED.
 *              Repeated messages for the same device and alarm type update the existing alarm
 *              instead of creating a second one.
 * @templateType INBOUND_SMART_FUNCTION
 * @sampleTopic testAlarm/sensor-berlin-01
 * @samplePayload
 *   {
 *     "code": "Overheat",
 *     "level": 3,
 *     "message": "Temperature above limit",
 *     "limit": 80
 *   }
 * Send the same message with "level": 0 to clear the alarm.
*/

/**
 * @param {import('@c8y/dynamic-mapper-smart-function').DynamicMapperDeviceMessage} msg
 * @param {import('@c8y/dynamic-mapper-smart-function').SmartFunctionContext} context
 */
function onMessage(msg, context) {
    var payload = msg.payload;

    // Prefer context clientId (MQTT client id), fall back to topic level 1
    var topic = context.getConfig()["topic"] || "";
    var externalId = context.getClientId() || topic.split("/")[1];
    if (!externalId) {
        context.addWarning("No device id in client id or topic '" + topic + "' - message ignored.");
        return [];
    }

    var severities = ["WARNING", "MINOR", "MAJOR", "CRITICAL"];
    var level = Number(payload["level"]);
    if (!(level >= 0 && level <= 4)) {
        context.addWarning("Unknown level '" + payload["level"] + "' - message ignored.");
        return [];
    }

    var cleared = level === 0;
    var alarm = {
        "time": payload["time"] || msg.time,
        "type": "c8y_" + payload["code"] + "Alarm",
        "text": payload["message"],
        // The severity is required by Cumulocity, also when the alarm is cleared
        "severity": cleared ? "WARNING" : severities[level - 1],
        "status": cleared ? "CLEARED" : "ACTIVE"
    };

    // Optional device data can be stored as a custom alarm fragment
    if (payload["limit"] !== undefined) {
        alarm["c8y_Threshold"] = { "limit": payload["limit"] };
    }

    return [{
        cumulocityType: "alarm",
        action: "create",
        payload: alarm,
        externalSource: [{ "type": "c8y_Serial", "externalId": externalId }]
    }];
}
export { onMessage };
