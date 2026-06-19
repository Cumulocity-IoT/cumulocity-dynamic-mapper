/**
 * @name Forward payload to tenant microservice (inbound)
 * @description Demonstrates custom routing: forwards the incoming device payload to a
 *              tenant-local microservice via cumulocityType: "custom", in addition to
 *              creating a standard Cumulocity measurement.
 *              The targetPath must start with /service/ and point to a microservice
 *              that is subscribed to the same Cumulocity tenant.
 * @templateType INBOUND_SMART_FUNCTION
 * @direction INBOUND
 *
 * Sample payload (MQTT topic: testCustomRouting/sensor-berlin-01)
 * {
 *     "externalId": "sensor-berlin-01",
 *     "temperature": 23.5
 * }
 */
function onMessage(msg, context) {
    var payload = msg.getPayload();
    var externalId = context.getClientId() || payload["externalId"];
    // Prefer timestamp from payload; fall back to message arrival time
    var time = payload["time"] ? payload["time"] : msg.time;

    return [
        // 1. Create a standard Cumulocity temperature measurement
        {
            cumulocityType: "measurement",
            action: "create",
            payload: {
                "time": time,
                "type": "c8y_TemperatureMeasurement",
                "c8y_Steam": { "Temperature": { "unit": "C", "value": payload["temperature"] } }
            },
            externalSource: [{ "type": "c8y_Serial", "externalId": externalId }]
        },
        // 2. Forward the raw reading to a custom tenant microservice via HTTP POST.
        //    targetPath must start with /service/ and the microservice must be subscribed
        //    to this tenant.  Device identity resolution is skipped for custom routing.
        {
            cumulocityType: "custom",
            action: "create",
            targetPath: "/service/my-processor/ingest",
            payload: {
                "deviceId": externalId,
                "timestamp": time,
                "reading": payload["temperature"]
            }
        }
    ];
}

export {onMessage};
