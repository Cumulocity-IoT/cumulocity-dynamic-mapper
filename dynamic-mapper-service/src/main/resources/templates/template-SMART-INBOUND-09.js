/**
 * @name HART Device Metrics to Cumulocity Measurements
 * @description Maps Sparkplug-B/HART DDATA payload metrics to Cumulocity measurements.
 *              Each metric in the metrics array becomes a separate measurement fragment.
 *              Only numeric values are sent; booleans are converted to 0/1.
 * @templateType INBOUND_SMART_FUNCTION
 * @direction INBOUND
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

    // Unit lookup map based on known HART metric names
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
            : new Date().toISOString();

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
            "type": "c8y_HARTMeasurement"
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