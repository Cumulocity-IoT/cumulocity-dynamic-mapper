/**
 * @name UNS - group single-value messages into one measurement
 * @description N:1 aggregation. A UNS publisher sends one MQTT message per value
 *              (topic = site/area/device/<measurement>, payload = {time, value}). This function
 *              collects the values per device and timestamp and emits ONE measurement with one
 *              fragment per value as soon as EXPECTED_COUNT distinct values have arrived.
 * @templateType INBOUND_SMART_FUNCTION
 * @targetAPI MEASUREMENT
 * @mappingTopic uns/+/+/+
 * @sampleTopic uns/plant1/press7/temperature
 * @samplePayload {"time":"2026-01-01T10:00:00.000Z","value":21.5}
 *
 * Why context.collect and not getState/setState: state is read as a snapshot when a message starts
 * and written back when it ends. Of 100 messages that run at the same time, none sees the other 99,
 * so a count made from state never reaches 100. context.collect adds the value and checks the count
 * in one atomic step, so exactly one message receives the complete group.
 *
 * Limits: the buffer is in memory only (lost on restart, not shared between service instances),
 * a group that never completes is dropped after 1,000 newer open groups, and a group is removed
 * when it is handed out, so a failure after that point loses it.
 */
var EXPECTED_COUNT = 100; // number of values per timestamp (set per customer)

function onMessage(msg, context) {
    var payload = msg.payload;
    var parts = (msg.topic || "").split("/");
    var name = parts[parts.length - 1];
    var deviceId = parts[parts.length - 2];
    if (!name || !deviceId || payload.value === undefined || !payload.time) {
        console.log("UNS message without device, name, time or value - skipped");
        return [];
    }

    var values = context.collect(deviceId + "|" + payload.time, name, payload.value, EXPECTED_COUNT);
    if (!values) {
        return []; // group still incomplete
    }

    var measurement = { time: payload.time, type: "uns_Measurement" };
    Object.keys(values).forEach(function (n) {
        var fragment = {};
        fragment[n] = { value: values[n] };
        measurement["uns_" + n] = fragment;
    });
    return [{
        cumulocityType: "measurement",
        action: "create",
        payload: measurement,
        externalSource: [{ type: "c8y_Serial", externalId: deviceId }]
    }];
}
