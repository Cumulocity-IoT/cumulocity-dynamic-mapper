/**
 * @name Creates one measurement as array
 * @description Creates one measurement as array
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

    return [{  
        topic: `measurements/${payload["source"]["id"]}`,
        payload:[{
            "time":  new Date().toISOString(),
            "c8y_Steam": {
                "Temperature": {
                "unit": "C",
                "value": payload["c8y_TemperatureMeasurement"]["T"]["value"]
                }
            }
        }]
    }];
}