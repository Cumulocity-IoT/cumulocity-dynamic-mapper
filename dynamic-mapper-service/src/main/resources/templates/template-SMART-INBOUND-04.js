/**
 * @name Create either measurement or event
 * @description Create either measurement or event, with persistent state for message counting and error deduplication
 * @templateType INBOUND_SMART_FUNCTION
 * @defaultTemplate false
 * @internal true
 * @readonly true
 *
 * Demonstrates two state use cases:
 * 1. Message counters — count telemetry and error messages across invocations
 * 2. Error deduplication — suppress consecutive duplicate error events
 *
 * Sample payload (telemetry)
 * {
 *     "messageId": "msg-001",
 *     "externalId": "sensor-berlin-01",
 *     "payloadType": "telemetry",
 *     "sensorData": {
 *         "temp_val": 23.5
 *     }
 * }
 * Sample payload (error)
 * {
 *     "messageId": "msg-002",
 *     "externalId": "sensor-berlin-01",
 *     "payloadType": "error",
 *     "logMessage": "Sensor malfunction detected"
 * }
 * topic 'flowState/sensor-berlin-01'
*/

function onMessage(msg, context) {
    var payload = msg.getPayload();

    console.log("Payload Raw:" + payload);
    console.log("Payload messageId: " + payload["messageId"]);

    // Get externalId from context first, fall back to payload
    var externalId = context.getClientId() || payload["externalId"];

    // --- Load persistent state ---
    var telemetryCount = context.getState("telemetryCount") || 0;
    var errorCount     = context.getState("errorCount")     || 0;
    var lastErrorMsg   = context.getState("lastErrorMsg")   || null;

    const payloadType = payload["payloadType"];
    var result;

    if (payloadType == "telemetry") {
        // --- Update telemetry counter ---
        telemetryCount = telemetryCount + 1;
        context.setState("telemetryCount", telemetryCount);

        console.log("Telemetry message #" + telemetryCount);

        result = {
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
            externalSource: [{"type": "c8y_Serial", "externalId": externalId}]
        };

    } else {
        // --- Error deduplication: suppress if same message as last time ---
        var currentErrorMsg = payload["logMessage"];

        if (currentErrorMsg === lastErrorMsg) {
            console.log("Suppressing duplicate error event: '" + currentErrorMsg +
                "' (errorCount=" + errorCount + ")");
            return [];
        }

        // --- Update error state ---
        errorCount = errorCount + 1;
        context.setState("errorCount",   errorCount);
        context.setState("lastErrorMsg", currentErrorMsg);

        console.log("Error message #" + errorCount + ": " + currentErrorMsg);

        result = {
            cumulocityType: "event",
            action: "create",
            payload: {
                "time":     new Date().toISOString(),
                "type":     "c8y_ErrorEvent",
                "text":     currentErrorMsg,
                "severity": "MAJOR",
                "status":   "ACTIVE"
            },
            externalSource: [{"type": "c8y_Serial", "externalId": externalId}]
        };
    }

    console.log("State: telemetryCount=" + telemetryCount + ", errorCount=" + errorCount +
        ", lastErrorMsg=" + lastErrorMsg);

    return result;
}
