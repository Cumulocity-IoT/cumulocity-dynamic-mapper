/**
 * @name UNS - flush the previous timestamp when a newer one arrives
 * @description N:1 aggregation for publishers that do not send a fixed number of values. A UNS publisher sends one
 *              MQTT message per value (topic = site/area/device/<measurement>, payload = {time, value}). This
 *              function collects the values per device and timestamp. When a value with a newer timestamp
 *              arrives, it takes the earlier groups of that device as they are, even if partial, and emits ONE
 *              measurement per group with one fragment per value.
 * @templateType INBOUND_SMART_FUNCTION
 * @targetAPI MEASUREMENT
 * @mappingTopic unsFlush/+/+/+
 * @sampleTopic unsFlush/plant1/press7/temperature
 * @samplePayload {"time":"2026-01-01T10:00:00.000Z","value":21.5}
 *
 * Use the fixed-count sample (uns-group-by-timestamp.js) when every timestamp has the same number of values, or
 * context.collect with {keys: [...]} when the set of values is known. Use this one when neither is true.
 *
 * Assumptions and limits:
 * - The publisher sends the values of one timestamp before the values of the next. A value of an older timestamp
 *   that arrives after a newer one opens a new group and is sent as its own measurement later.
 * - The newest group of a device stays in memory until the next timestamp arrives. A device that stops sending
 *   leaves its last group unsent; MAX_AGE_SECONDS then drops it. Nothing runs on a timer.
 * - The buffer is in memory only (lost on restart, not shared between service instances).
 * - ISO 8601 timestamps in the same format and time zone compare correctly as text.
 */
var MAX_AGE_SECONDS = 3600; // an unsent group is dropped after this long

function toMeasurement(deviceId, time, values) {
    var measurement = { time: time, type: "uns_Measurement" };
    Object.keys(values).forEach(function (n) {
        var fragment = {};
        fragment[n] = { value: values[n] };
        measurement["uns_" + n] = fragment;
    });
    return {
        cumulocityType: "measurement",
        action: "create",
        payload: measurement,
        externalSource: [{ type: "c8y_Serial", externalId: deviceId }]
    };
}

function onMessage(msg, context) {
    var payload = msg.payload;
    var parts = (msg.topic || "").split("/");
    var name = parts[parts.length - 1];
    var deviceId = parts[parts.length - 2];
    if (!name || !deviceId || payload.value === undefined || !payload.time) {
        console.log("UNS message without device, name, time or value - skipped");
        return [];
    }

    var prefix = deviceId + "|";
    var out = [];
    context.openGroups(prefix).forEach(function (key) {
        var time = key.substring(prefix.length);
        if (time < payload.time) { // an older group of this device will get no more values
            var partial = context.take(key);
            if (partial) {
                out.push(toMeasurement(deviceId, time, partial));
            }
        }
    });

    // count is only an upper bound here: the next timestamp, not the count, ends a group
    context.collect(prefix + payload.time, name, payload.value, { count: 10000, maxAgeSeconds: MAX_AGE_SECONDS });
    return out;
}
