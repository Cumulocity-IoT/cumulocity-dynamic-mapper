/**
 * @name Creates one measurement and key for messages to Kafka connectors
 * @description Creates one measurement as array, set key for messages to Kafka connectors
 * @templateType OUTBOUND_SMART_FUNCTION
 * @direction OUTBOUND
 * @defaultTemplate false
 * @internal true
 * @readonly true
 * 
*/

function onMessage(msg, context) {
    var payload = msg.getPayload();

    // context.getConfig().externalId contains the resolved external id of the source device.
    // Requires the mapping to have 'useExternalId' enabled and an 'externalIdType' configured.
    const externalId = context.getConfig().externalId;

    return [{
        topic: `measurements/${externalId}`,
        payload:{
            "time":  new Date().toISOString(),
            "c8y_Steam": {
                "Temperature": {
                "unit": "C",
                "value": payload["c8y_TemperatureMeasurement"]["T"]["value"]
                }
            }
        },
        transportFields: { "key": externalId }  // define key to add to Kafka payload (record)
    }];
}