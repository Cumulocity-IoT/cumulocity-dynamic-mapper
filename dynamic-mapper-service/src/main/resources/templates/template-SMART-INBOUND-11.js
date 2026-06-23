/**
 * @name Sample persistent state - per-device running statistics
 * @description Tracks message count and running average temperature per device, using the MQTT
 *              client ID from context (not the payload) as the device identifier.
 * @templateType INBOUND_SMART_FUNCTION
 * @defaultTemplate false
 * @internal true
 * @readonly true
 * @since 6.2
 *
 * Demonstrates per-device state management using composite state keys:
 * - The device identifier is taken from context.getClientId() (MQTT client ID)
 * - Falls back to the last segment of the resolved topic if the client ID is absent
 * - State keys are namespaced as "<deviceId>:<statKey>" so each device accumulates
 *   its own counters within the single per-mapping state store
 * - State survives across message invocations and is scoped per mapping
 * - State is cleared when the mapping is deleted
 *
 * Prerequisites:
 * - Each device must connect to the MQTT broker with a client ID matching its
 *   external ID (c8y_Serial), or the last topic segment must carry the device id.
 * - Enable createNonExistingDevice in the mapping so new devices are auto-registered.
 *
 * Example publish:
 *   mosquitto_pub -i "sensor-001" -t "perDevStats/sensor-001" \
 *     -m '{"temperature": 23.5}'
 *
 * Example payload:
 *   { "temperature": 23.5 }
*/

function onMessage(msg, context) {
    var payload = msg.payload;
    var temperature = payload["temperature"];

    if (temperature === undefined || temperature === null) {
        console.log("Missing temperature in payload — skipping");
        return [];
    }

    // --- Resolve device identifier from context (never from payload) ---
    // Primary source: MQTT client ID set by the connecting device.
    // Fallback: last segment of the resolved topic (e.g. "perDevStats/sensor-001" → "sensor-001").
    var deviceId = context.getClientId();
    if (!deviceId) {
        var config = context.getConfig();
        var topic = config ? config["topic"] : null;
        if (topic) {
            var parts = topic.split("/");
            deviceId = parts[parts.length - 1];
        }
    }

    if (!deviceId) {
        console.log("Cannot determine deviceId from context — skipping");
        return [];
    }

    // --- Load per-device persisted state (null on first invocation for this device) ---
    var countKey   = deviceId + ":messageCount";
    var totalKey   = deviceId + ":temperatureSum";
    var minKey     = deviceId + ":minTemperature";
    var maxKey     = deviceId + ":maxTemperature";

    var count   = context.getState(countKey)   || 0;
    var total   = context.getState(totalKey)   || 0;
    var minTemp = context.getState(minKey);
    var maxTemp = context.getState(maxKey);

    // --- Update per-device statistics ---
    count   = count + 1;
    total   = total + temperature;
    minTemp = (minTemp === null || minTemp === undefined) ? temperature : Math.min(minTemp, temperature);
    maxTemp = (maxTemp === null || maxTemp === undefined) ? temperature : Math.max(maxTemp, temperature);
    var avg = total / count;

    // --- Persist updated per-device state ---
    context.setState(countKey, count);
    context.setState(totalKey, total);
    context.setState(minKey,   minTemp);
    context.setState(maxKey,   maxTemp);

    console.log("Device " + deviceId + " — message #" + count +
                ": avg=" + avg.toFixed(2) + ", min=" + minTemp + ", max=" + maxTemp);

    var time = payload["time"] ? payload["time"] : new Date().toISOString();

    return [{
        cumulocityType: "measurement",
        action: "create",
        payload: {
            time: time,
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

export {onMessage};
