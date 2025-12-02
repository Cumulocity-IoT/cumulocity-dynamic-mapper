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

    console.log("Context" + context.getStateAll());
    console.log("Payload Raw:" + payload);
    console.log("Payload messageId" +  payload.get('messageId'));

    return {  
        topic: `measurements/${payload["source"]["id"]}`,
        payload:[{
            "time":  new Date().toISOString(),
            "c8y_Steam": {
                "Temperature": {
                "unit": "C",
                "value": payload["c8y_TemperatureMeasurement"]["T"]["value"]
                }
            }
        },
        transportFields: { "key": payload["source"]["id"]}  // define key to add to Kafka payload (record)
    }];
}