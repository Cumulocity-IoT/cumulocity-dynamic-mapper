/**
 * @name Forward operation to tenant microservice (outbound)
 * @description Demonstrates custom routing: when a Cumulocity operation is received,
 *              forwards it to a tenant-local microservice via cumulocityType: "custom".
 *              The targetPath must start with /service/ and point to a microservice
 *              that is subscribed to the same Cumulocity tenant.
 * @templateType OUTBOUND_SMART_FUNCTION
 * @direction OUTBOUND
 *
 * Sample Cumulocity operation payload (source):
 * {
 *     "id": "99001",
 *     "deviceId": "12345",
 *     "c8y_Command": { "text": "reboot" }
 * }
 */
function onMessage(msg, context) {
    var payload = msg.getPayload();
    var deviceId = payload["deviceId"] || (payload["source"] && payload["source"]["id"]);
    var command = payload["c8y_Command"] && payload["c8y_Command"]["text"];

    // Forward the operation to a custom command-handler microservice via HTTP POST.
    // targetPath must start w ith /service/ and the microservice must be subscribed to
    // this tenant.  No device identity resolution is performed for custom routing.
    return {
        cumulocityType: "custom",
        action: "create",
        targetPath: "/service/my-processor/execute",
        payload: {
            "deviceId": deviceId,
            "command": command,
            "operationId": payload["id"],
            "timestamp": new Date().toISOString()
        }
    };
}

export {onMessage};
