// @ts-check
/**
 * @name Drop repeated messages
 * @description Creates a temperature measurement and drops a message that repeats an earlier one from the same
 *              device with the same timestamp within 60 seconds, for example after a broker reconnect or an
 *              MQTT QoS 1 retry. The check is atomic, keys expire with the window, and the monitoring table
 *              counts every drop under Repeated.
 * @templateType INBOUND_SMART_FUNCTION
 * @sampleTopic dedup/sensor-berlin-01
 * @samplePayload
 *   {
 *     "externalId": "sensor-berlin-01",
 *     "time": "2026-01-01T00:00:00.000Z",
 *     "temperature": 23.5
 *   }
 *
 * Send the sample payload twice within 60 seconds: the first message creates a measurement, the second
 * returns no output and is counted as Repeated.
 *
 * A repeat is only detected when the payload carries its own "time". The receive time differs on every
 * message, so it cannot identify a repeat. A message without externalId or time has no safe key, so it is
 * processed without a check. A message that fails after the check does not block its retry: the key is
 * given back, so the retry is processed.
 *
 * Keys live in memory only. They are lost on restart and are not shared between service instances. Dry Run
 * and Live Test never record a key, so testing does not change what production drops.
 *
 * To drop repeats without code, use the filter function instead, for example
 *   $not($isDuplicate(externalId & "|" & time, 60))
 * in the Filter execution mapping.
*/

/**
 * @param {import('@c8y/dynamic-mapper-smart-function').DynamicMapperDeviceMessage} msg
 * @param {import('@c8y/dynamic-mapper-smart-function').SmartFunctionContext} context
 */
function onMessage(msg, context) {
    var payload = msg.payload;
    var externalId = context.getClientId() || payload["externalId"];
    var time = payload["time"];

    // The key says what makes two messages the same. A separator keeps "a" + "bc" apart from "ab" + "c".
    // Without both fields there is no safe key, so the message is processed without a check.
    var key = externalId && time ? externalId + "|" + time : null;
    if (key && context.isDuplicate(key, 60)) {
        console.log("Dropping repeated message from " + externalId + " at " + time);
        return [];
    }

    return {
        cumulocityType: "measurement",
        action: "create",
        payload: {
            "time": time || msg.time,
            "type": "c8y_TemperatureMeasurement",
            "c8y_Temperature": {
                "T": {
                    "unit": "C",
                    "value": payload["temperature"]
                }
            }
        },
        externalSource: [{"type": "c8y_Serial", "externalId": externalId}]
    };
}

export {onMessage};
