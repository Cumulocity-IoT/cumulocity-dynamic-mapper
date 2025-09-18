/**
 * @name Default template for Smart Function
 * @description Default template for Smart Function, creates one measurement
 * @templateType INBOUND_SMART
 * @direction INBOUND
 * @defaultTemplate true
 * @internal true
 * @readonly true
 * 
*/

function onMessage(inputMsg, context) {
    const msg = inputMsg; 

    var payload = msg.getPayload();

    context.logMessage("Context" + context.getStateAll());
    context.logMessage("Payload Raw:" + msg.getPayload());
    context.logMessage("Payload messageId" +  msg.getPayload().get('messageId'));

    return [{
        cumulocityType: "measurement",
        action: "create",
        
        payload: {
            "time":  new Date().toISOString(),
            "type": "c8y_TemperatureMeasurement",
            "c8y_Steam": {
                "Temperature": {
                "unit": "C",
                "value": payload["sensorData"]["temp_val"]
                }
            }
        },

        externalSource: [{"type":"c8y_Serial", "externalId": payload.get('clientId')}]
    }];
}