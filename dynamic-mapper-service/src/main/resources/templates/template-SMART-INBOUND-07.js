/**
 * @name Sample persistent state - running statistics
 * @description Tracks message count and running average temperature across invocations
 * @templateType INBOUND_SMART_FUNCTION
 * @direction INBOUND
 * @defaultTemplate false
 * @internal true
 * @readonly true
 *
 * Demonstrates persistent state management:
 * - State written with context.setState() survives across message invocations
 * - State is scoped per mapping (not per device)
 * - State is cleared when the mapping is deleted
 *
 * Example payload:
 * { "deviceId": "sensor-001", "temperature": 23.5 }
*/

function onMessage(msg, context) {
    var payload = msg.getPayload();
    var deviceId = payload["deviceId"];
    var temperature = payload["temperature"];

    if (!deviceId || temperature === undefined) {
        console.log("Missing deviceId or temperature in payload");
        return [];
    }

    // --- Load persisted state (null on first invocation) ---
    var count   = context.getState("messageCount")   || 0;
    var total   = context.getState("temperatureSum") || 0;
    var minTemp = context.getState("minTemperature");
    var maxTemp = context.getState("maxTemperature");

    // --- Update statistics ---
    count   = count + 1;
    total   = total + temperature;
    minTemp = (minTemp === null || minTemp === undefined) ? temperature : Math.min(minTemp, temperature);
    maxTemp = (maxTemp === null || maxTemp === undefined) ? temperature : Math.max(maxTemp, temperature);
    var avg = total / count;

    // --- Persist updated state for the next message ---
    context.setState("messageCount",   count);
    context.setState("temperatureSum", total);
    context.setState("minTemperature", minTemp);
    context.setState("maxTemperature", maxTemp);

    console.log("Statistics after message " + count + ": avg=" + avg.toFixed(2) +
                ", min=" + minTemp + ", max=" + maxTemp);

    // --- Emit a measurement that includes the running statistics ---
    return [{
        cumulocityType: "measurement",
        action: "create",
        payload: {
            time: new Date().toISOString(),
            type: "c8y_TemperatureMeasurement",
            c8y_Temperature: {
                T: { value: temperature, unit: "C" }
            },
            c8y_TemperatureStatistics: {
                average:      { value: avg,     unit: "C" },
                minimum:      { value: minTemp,  unit: "C" },
                maximum:      { value: maxTemp,  unit: "C" },
                messageCount: { value: count,    unit: "#" }
            }
        },
        externalSource: [{ type: "c8y_Serial", externalId: deviceId }]
    }];
}
