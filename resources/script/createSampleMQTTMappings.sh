curl --location --request POST 'https://YOUR_TENANT.eu-latest.cumulocity.com/inventory/managedObjects' \
--header 'Authorization: Basic YOUR_AUTHENTICATION' \
--header 'Content-Type: application/json' \
--header 'Accept: application/json' \
--data-raw '{
    "name": "MQTT Mapping",
    "c8y_IsDevice": {},
    "type": "c8y_mqttMapping_v2_type",
    "c8y_mqttMapping": [
        {
            "qos": 0,
            "substitutions": [],
            "tested": false,
            "createNoExistingDevice": false,
            "lastUpdate": 1659798913434,
            "topic": "temperature/#",
            "active": false,
            "id": 0,
            "targetAPI": "measurement",
            "source": "{\n    \"value\": 125,\n    \"timestamp\": \"2022-08-05T00:14:49.389+02:00\"\n}",
            "target": "{\n    \"source\": {\n        \"id\": \"909090\"\n    },\n    \"time\": \"2020-03-19T12:03:27.845Z\",\n    \"type\": \"c8y_TemperatureMeasurement\",\n    \"c8y_Steam\": {\n        \"Temperature\": {\n            \"unit\": \"C\",\n            \"value\": 110\n        }\n    }\n}"
        },
        {
            "qos": 0,
            "substitutions": [],
            "tested": false,
            "createNoExistingDevice": false,
            "lastUpdate": 1659798906443,
            "topic": "speed/#",
            "active": false,
            "id": 1,
            "targetAPI": "measurement",
            "source": "{\n    \"value\": 85,\n    \"timestamp\": \"2022-08-05T00:14:49.389+02:00\"\n}",
            "target": "{\n    \"source\": {\n        \"id\": \"909090\"\n    },\n    \"time\": \"2020-03-19T12:03:27.845Z\",\n    \"type\": \"c8y_SpeedMeasurement\",\n    \"c8y_Velovity\": {\n        \"Velocity\": {\n            \"unit\": \"km/h\",\n            \"value\": 210\n        }\n    }\n}"
        },
        {
            "qos": 0,
            "substitutions": [],
            "tested": true,
            "createNoExistingDevice": false,
            "lastUpdate": 1659988312306,
            "topic": "event/#",
            "active": true,
            "id": 2,
            "targetAPI": "event",
            "source": "{\n    \"asset\": \"303030\",\n    \"msg_type\": \"c8y_LoraBellEvent\",\n    \"text\": \"Elevator was called\",\n    \"time\": \"2022-08-05T00:14:49.389+02:00\"\n}",
            "target": "{\n    \"source\": {\n        \"id\": \"909090\"\n    },\n    \"type\": \"c8y_LoraBellEvent\",\n    \"text\": \"Elevator was called\",\n    \"time\": \"2020-03-19T12:03:27.845Z\"\n}"
        },
        {
            "qos": 1,
            "substitutions": [],
            "tested": true,
            "createNoExistingDevice": false,
            "lastUpdate": 1660035178751,
            "topic": "alarm/#",
            "active": true,
            "id": 3,
            "targetAPI": "alarm",
            "source": "{\n    \"id\": \"251982\",\n    \"alarm_type\": \"c8y_UnavailabilityAlarm\",\n    \"text\": \"No data received from the device within the required interval.\",\n    \"severity\": \"MAJOR\",\n    \"status\": \"ACTIVE\",\n \"time\": \"2020-03-19T12:03:27.845Z\"\n}",
            "target": "{\n    \"source\": {\n        \"id\": \"909090\"\n    },\n    \"type\": \"c8y_UnavailabilityAlarm\",\n    \"text\": \"No data received from the device within the required interval.\",\n    \"severity\": \"MINOR\",\n    \"status\": \"ACTIVE\",\n    \"time\": \"2020-03-19T12:03:27.845Z\"\n}"
        }
    ]
}'