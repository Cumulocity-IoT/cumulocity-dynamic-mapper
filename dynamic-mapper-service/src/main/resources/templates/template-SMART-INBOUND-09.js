// @ts-check
/**
 * @name Metrics array to one measurement per metric
 * @description Maps the metrics array of a Sparkplug B DDATA payload (as delivered by the
 *              SPARKPLUGB mapping type) to Cumulocity measurements. Each metric becomes a
 *              separate measurement fragment, named after the metric. Only numeric values are
 *              sent; booleans are converted to 0/1 and non-numeric metrics are skipped.
 *              The unit lookup table is an example - adapt it to your metric names.
 * @templateType INBOUND_SMART_FUNCTION
 */
/**
 * @param {import('@c8y/dynamic-mapper-smart-function').DynamicMapperDeviceMessage} msg
 * @param {import('@c8y/dynamic-mapper-smart-function').SmartFunctionContext} context
 */
function onMessage(msg, context) {
    var payload = msg.payload;

    console.log("Payload Raw: " + JSON.stringify(payload));

    var metrics = payload["metrics"];
    var deviceId = payload["deviceId"];
    var groupId = payload["groupId"];
    var edgeNodeId = payload["edgeNodeId"];
    var messageTimestamp = payload["timestamp"];

    if (!metrics || metrics.length === 0) {
        console.log("Warning: No metrics found in payload.");
        return [];
    }

    // Example unit lookup, keyed by metric name (HART process variables here)
    var unitMap = {
        "Primary Variable":    "mA",
        "Secondary Variable":  "mA",
        "Tertiary Variable":   "mA",
        "Quaternary Variable": "mA",
        "Percent Range":       "%",
        "Loop Current":        "mA",
        "Temperature":         "°C",
        "Pressure":            "bar",
        "Flow":                "m3/h",
        "Level":               "m"
    };

    /**
     * Determine unit for a given metric name.
     * Falls back to an empty string if the name is not in the known map.
     * @param {string} metricName
     * @returns {string}
     */
    function determineUnit(metricName) {
        return unitMap[metricName] || "";
    }

    /**
     * Converts a raw metric value to a numeric value suitable for a Cumulocity measurement.
     * - Numbers are returned as-is.
     * - Booleans are converted: true -> 1, false -> 0.
     * - Strings that parse as a finite number are converted.
     * - All other types (objects, arrays, plain strings, etc.) return null,
     *   indicating the metric should be skipped.
     *
     * @param {any} value
     * @returns {number|null}
     */
    function toNumericValue(value) {
        if (typeof value === "boolean") {
            return value ? 1 : 0;
        }
        if (typeof value === "number") {
            return isFinite(value) ? value : null;
        }
        if (typeof value === "string") {
            var parsed = Number(value);
            return isFinite(parsed) ? parsed : null;
        }
        // object, array, null-like after the earlier guard, etc.
        return null;
    }

    var results = [];

    for (var i = 0; i < metrics.length; i++) {
        var metric = metrics[i];

        // Guard: must have a name and a non-null value
        if (!metric["name"] || metric["value"] === undefined || metric["value"] === null) {
            console.log("Warning: Skipping metric with missing name or value at index " + i);
            continue;
        }

        // Convert value; skip metric if it is not numeric-compatible
        var numericValue = toNumericValue(metric["value"]);
        if (numericValue === null) {
            console.log("Warning: Skipping non-numeric metric '" + metric["name"] +
                "' (type: " + typeof metric["value"] + ", value: " + metric["value"] + ")");
            continue;
        }

        // Use metric-level timestamp if available, otherwise fall back to message-level timestamp
        var rawTimestamp = metric["timestamp"] || messageTimestamp;
        var isoTimestamp = rawTimestamp
            ? new Date(rawTimestamp).toISOString()
            : msg.time;

        // Build a safe fragment name from the metric name
        // e.g. "Primary Variable" -> "c8y_PrimaryVariable"
        var fragmentName = "c8y_" + metric["name"].replace(/[^a-zA-Z0-9]/g, "");

        // Build the series name in camelCase
        // e.g. "Primary Variable" -> "primaryVariable"
        var seriesName = metric["name"]
            .trim()
            .replace(/[^a-zA-Z0-9\s]/g, "")
            .replace(/\s+(.)/g, function(match, chr) { return chr.toUpperCase(); })
            .replace(/^(.)/, function(match, chr) { return chr.toLowerCase(); });

        var measurementPayload = {
            "time": isoTimestamp,
            "type": "c8y_MetricMeasurement"
        };

        // Add the measurement fragment dynamically
        measurementPayload[fragmentName] = {};
        measurementPayload[fragmentName][seriesName] = {
            "value": numericValue,
            "unit": determineUnit(metric["name"])
        };

        console.log("Creating measurement for metric: " + metric["name"] +
            " = " + numericValue +
            " (original type: " + typeof metric["value"] + ")");

        results.push({
            cumulocityType: "measurement",
            action: "create",
            payload: measurementPayload,
            externalSource: [{
                "type": "c8y_Serial",
                "externalId": groupId + "_" + edgeNodeId
            }]
        });
    }

    console.log("Total measurements created: " + results.length);
    return results;
}
export {onMessage};