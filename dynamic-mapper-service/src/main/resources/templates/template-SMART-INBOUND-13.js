// @ts-check
/**
 * @name Decode a binary frame (Any Payload)
 * @description Decodes a fixed-layout 8-byte binary frame into a temperature and humidity
 *              measurement. A mapping of type ANY_PAYLOAD delivers the raw bytes as a Base64 string
 *              in msg.payload, so the function decodes it to bytes and reads the fields by offset.
 *              Use it as a starting point for any simple binary device protocol.
 * @templateType INBOUND_SMART_FUNCTION
 * @sampleTopic testBinary/sensor-berlin-01
 *
 * Requires:
 *   MappingType  : ANY_PAYLOAD
 *   Topic pattern: testBinary/+   (the device id is topic level 1)
 *
 * Frame layout (big endian):
 *   byte 0     frame version, must be 1
 *   bytes 1-2  temperature, signed 16 bit, in 0.1 degree Celsius
 *   byte 3     relative humidity, unsigned 8 bit, in percent
 *   bytes 4-7  time, unsigned 32 bit, seconds since 1970-01-01
 *
 * Sample frame, topic 'testBinary/sensor-berlin-01'
 *   hex    0100EB376955B900
 *   Base64 AQDrN2lVuQA=
 *   result 23.5 C, 55 %, 2026-01-01T00:00:00Z
 *
 * Publish it with:
 *   echo -n 0100EB376955B900 | xxd -r -p | mosquitto_pub -t testBinary/sensor-berlin-01 -s
*/

/**
 * Decodes a Base64 string to bytes (pure ECMAScript, no Java interop).
 * atob() is not used on purpose: it returns a UTF-8 decoded string, which corrupts bytes >= 0x80.
 * @param {string} b64
 * @returns {number[]}
 */
function base64ToBytes(b64) {
    var alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    var bytes = [];
    var bits = 0;
    var value = 0;
    for (var i = 0; i < b64.length; i++) {
        var index = alphabet.indexOf(b64.charAt(i));
        if (index < 0) continue; // padding or line breaks
        value = (value << 6) | index;
        bits += 6;
        if (bits >= 8) {
            bits -= 8;
            bytes.push((value >> bits) & 0xFF);
        }
    }
    return bytes;
}

/**
 * @param {import('@c8y/dynamic-mapper-smart-function').DynamicMapperDeviceMessage} msg
 * @param {import('@c8y/dynamic-mapper-smart-function').SmartFunctionContext} context
 */
function onMessage(msg, context) {
    // With ANY_PAYLOAD the payload is the Base64 encoded raw message
    var bytes = base64ToBytes(String(msg.payload));

    if (bytes.length !== 8 || bytes[0] !== 1) {
        context.addWarning("Unexpected frame: " + bytes.length + " bytes, version " + bytes[0]);
        return [];
    }

    // Device id is topic level 1, e.g. 'testBinary/sensor-berlin-01'
    var externalId = context.getClientId() || (msg.topic || "").split("/")[1];
    if (!externalId) {
        context.addWarning("No device id in topic '" + msg.topic + "' - message ignored.");
        return [];
    }

    // Combine the bytes by offset; (x << 16) >> 16 turns an unsigned 16 bit value into a signed one
    var temperature = (((bytes[1] << 8) | bytes[2]) << 16 >> 16) / 10;
    var humidity = bytes[3];
    var seconds = ((bytes[4] * 256 + bytes[5]) * 256 + bytes[6]) * 256 + bytes[7];

    return [{
        cumulocityType: "measurement",
        action: "create",
        payload: {
            "time": new Date(seconds * 1000).toISOString(),
            "type": "c8y_EnvironmentMeasurement",
            "c8y_Temperature": { "T": { "value": temperature, "unit": "C" } },
            "c8y_Humidity": { "H": { "value": humidity, "unit": "%" } }
        },
        externalSource: [{ "type": "c8y_Serial", "externalId": externalId }]
    }];
}
export { onMessage };
