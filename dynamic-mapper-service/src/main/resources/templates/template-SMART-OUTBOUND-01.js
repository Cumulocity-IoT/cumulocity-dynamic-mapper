/**
 * @name Default template for Smart Function
 * @description Default template for Smart Function, creates one measurement
 * @templateType OUTBOUND_SMART_FUNCTION
 * @direction OUTBOUND
 * @defaultTemplate true
 * @internal true
 * @readonly true
 * 
*/

function onMessage(msg, context) {
    var payload = msg.getPayload();

    context.logMessage("Context" + context.getStateAll());
    context.logMessage("Payload Raw:" + payload);
    context.logMessage("Payload messageId" +  payload.get('messageId'));

    // use _externalId_ to reference the external id of the device.
    // it is resolved automatically using the externalId type from externalSource: [{"type":"c8y_Serial"}]
    // e.g. topic: `measurements/_externalId_`
    // return [{  
    //    topic: `measurements/_externalId_`,
    //    payload: {
    //        "time":  new Date().toISOString(),
    //        "c8y_Steam": {
    //            "Temperature": {
    //            "unit": "C",
    //            "value": payload["c8y_TemperatureMeasurement"]["T"]["value"]
    //            }
    //        }
    //    },
    //    transportFields: { "key": payload["source"]["id"]},  // define key to add to Kafka payload (record)
    //    externalSource: [{"type":"c8y_Serial"}]
    // }];

    return [{  
        topic: `measurements/${payload["source"]["id"]}`,
        payload: {
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