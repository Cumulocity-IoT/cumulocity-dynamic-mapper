// @ts-check
/**
 * @name Send an operation as a raw text command
 * @description Turns a Cumulocity operation into the single ASCII command string a device expects, for
 *              example an AT command that ends with "$". The string is sent exactly as returned: no
 *              quotes and no JSON. Use payloadEncoding "base64" or "hex" for binary protocols.
 * @templateType OUTBOUND_SMART_FUNCTION
 * @sampleTopic command/outbound/berlin_01
 * @samplePayload
 *   {
 *     "deviceId": "12345",
 *     "c8y_Command": {
 *       "text": "GTRTO=gv300,3,,,,,,0001$"
 *     }
 *   }
*/

/**
 * @param {import('@c8y/dynamic-mapper-smart-function').OutboundMessage} msg
 * @param {import('@c8y/dynamic-mapper-smart-function').SmartFunctionContext} context
 */
function onMessage(msg, context) {
    var command = msg.payload["c8y_Command"];
    if (!command || !command["text"]) {
        context.addWarning("Operation of device " + msg.sourceId + " has no c8y_Command text - no message published.");
        return null;
    }

    var externalId = context.getExternalId();
    if (!externalId) {
        context.addWarning("No external id resolved for device " + msg.sourceId + " - no message published.");
        return null;
    }

    return {
        topic: "command/" + externalId,
        // A string payload is sent as text; "AT+" is added here so the operation only carries the command body
        payload: "AT+" + command["text"],
        payloadEncoding: "text"
    };
}
