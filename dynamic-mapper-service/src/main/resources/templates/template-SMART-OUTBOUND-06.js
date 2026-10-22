// @ts-check
/**
 * @name Send an alarm as device fault level
 * @description Translates a Cumulocity alarm into the fault message a device understands:
 *              severity becomes a numeric level (WARNING=1 .. CRITICAL=4) and a cleared alarm is
 *              sent as level 0. Alarms that are only acknowledged are not forwarded.
 * @templateType OUTBOUND_SMART_FUNCTION
 *
 * Sample Cumulocity alarm payload (source)
 * {
 *     "time": "2025-01-01T12:00:00.000Z",
 *     "type": "c8y_OverheatAlarm",
 *     "text": "Temperature above limit",
 *     "severity": "MAJOR",
 *     "status": "ACTIVE",
 *     "source": { "id": "12345" }
 * }
 * publishTopic 'alarm/outbound/berlin_01'
*/

/**
 * @param {import('@c8y/dynamic-mapper-smart-function').OutboundMessage} msg
 * @param {import('@c8y/dynamic-mapper-smart-function').SmartFunctionContext} context
 */
function onMessage(msg, context) {
    var payload = msg.payload;

    // Acknowledging an alarm is not a change the device cares about; returning null publishes nothing
    if (payload["status"] === "ACKNOWLEDGED") {
        console.log("Alarm " + payload["type"] + " is only acknowledged - not forwarded");
        return null;
    }

    // context.getExternalId() returns the resolved external id of the source device.
    // Requires the mapping to have 'useExternalId' enabled and an 'externalIdType' configured.
    var externalId = context.getExternalId();
    if (!externalId) {
        context.addWarning("No external id resolved for device " + msg.sourceId + " - no message published.");
        return null;
    }

    var levels = { "WARNING": 1, "MINOR": 2, "MAJOR": 3, "CRITICAL": 4 };
    var cleared = payload["status"] === "CLEARED";

    return {
        topic: "alarm/outbound/" + externalId,
        payload: {
            "deviceId": externalId,
            "code": payload["type"],
            "level": cleared ? 0 : (levels[payload["severity"]] || 0),
            "message": payload["text"],
            "time": payload["time"] || msg.time
        }
    };
}
export { onMessage };
