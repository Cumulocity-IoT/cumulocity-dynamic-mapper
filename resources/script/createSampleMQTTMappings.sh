curl --location --request POST 'https://YOUR_TENANT.eu-latest.cumulocity.com/inventory/managedObjects' \
--header 'Authorization: Basic YOUR_AUTHENTICATION' \
--header 'Content-Type: application/json' \
--header 'Accept: application/json' \
--data-raw '{
    "name": "MQTT Mapping",
    "c8y_IsDevice": {},
    "type": "c8y_mqttMapping_type",
    "c8y_mqttMapping": [
        {
            "id": 0,
            "topic": "temperature/#",
            "targetAPI": "measurement",
            "source": "{\"device\": \"303030\", \"value\": 125, \"timestamp\": \"2022-08-05T00:14:49.389+02:00\"}",
            "target": "{ \"source\": {   \"id\": \"${device}\" }, \"time\": \"${timestamp}\", \"type\":\"c8y_TemperatureMeasurement\", \"c8y_Steam\": {   \"Temperature\": {    \"unit\": \"C\",  \"value\": ${value}   }  }}",
            "lastUpdate": {{current_ts}},
            "active": false,
            "qos": 0
        },
        {
            "id": 1,
            "topic": "speed/#",
            "targetAPI": "measurement",
            "source": "{\"device\": \"303030\", \"value\": 85, \"timestamp\": \"2022-08-05T00:14:49.389+02:00\"}",
            "target": "{ \"source\": {   \"id\": \"${device}\" }, \"time\": \"${timestamp}\", \"type\":\"c8y_SpeedMeasurement\", \"c8y_Velovity\": {   \"Velocity\": {    \"unit\": \"km/h\",  \"value\": ${value}   }  }}",
            "lastUpdate": {{current_ts}},
             "active": false,
            "qos": 0
        },
        {
            "id": 2,
            "topic": "event/#",
            "targetAPI": "event",
            "source": "{ \"source\": {\"id\": \"303030\" },\"type\": \"c8y_LoraBellEvent\", \"text\": \"Elevator was called\",\"time\": \"2022-08-05T00:14:49.389+02:00\"}",
            "target": "{ \"source\": {\"id\": \"${device}\" },\"type\": \"${type}\", \"text\": \"${text}\",\"time\": \"${timestamp}\"}",
            "lastUpdate": {{current_ts}},
             "active": false,
            "qos": 0
        }
    ]
}'