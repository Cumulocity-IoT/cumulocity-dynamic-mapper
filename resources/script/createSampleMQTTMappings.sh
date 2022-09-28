# Step1 run first curl, this returns a source id
# Step2 run second curl with id from cmd 1

# command step 1
curl --location --request POST 'https://YOUR_TENANT.eu-latest.cumulocity.com/inventory/managedObjects' \
--header 'Authorization: Basic YOUR_AUTHENTICATION' \
--header 'Content-Type: application/json' \
--header 'Accept: application/json' \
--data-raw '{
    "name": "MQTT Mapping",
    "type": "c8y_mqttMapping",
            "c8y_mqttMapping": [
                {
                    "tested": false,
                    "mapDeviceIdentifier": true,
                    "active": true,
                    "targetAPI": "INVENTORY",
                    "source": "{\"customName\":\"Bus-Berlin-Rom\",\"customText\":\"Rom\",\"customFragment\":{\"customFragmentValue\":\"customValueNew\"},\"customNumber\":10,\"customArray\":[\"ArrayValue1\",\"ArrayValue2\"],\"customType\":\"type_Bus\"}",
                    "externalIdType": "c8y_Serial",
                    "target": "{\"c8y_IsDevice\":{},\"name\":\"Vibration Sensor\",\"type\":\"maker_Vibration_Sensor\"}",
                    "templateTopic": "device/+/rom/",
                    "qos": 1,
                    "substitutions": [
                        {
                            "definesIdentifier": true,
                            "pathSource": "_DEVICE_IDENT_",
                            "pathTarget": "_DEVICE_IDENT_"
                        }
                    ],
                    "snoopTemplates": "STOPPED",
                    "lastUpdate": 1663690366869,
                    "subscriptionTopic": "device/#",
                    "snoopedTemplates": [
                        "{\n\t\"customName\": \"Bus-Berlin-Rom\",\n\t\"customText\": \"Rom\",\n\t\"customFragment\": {\n\t\t\"customFragmentValue\": \"customValueNew\"\n\t},\n\t\"customNumber\": 10,\n\t\"customArray\": [\n\t\t\"ArrayValue1\",\n\t\t\"ArrayValue2\"\n\t],\n\t\"customType\": \"type_Bus\"\n}\n"
                    ],
                    "id": 1,
                    "indexDeviceIdentifierInTemplateTopic": 2
                },
                {
                    "tested": false,
                    "mapDeviceIdentifier": true,
                    "active": true,
                    "targetAPI": "ALARM",
                    "source": "{\"msg_type\":\"c8y_LoraBellAlarm\",\"tx\":\"Elevator was not called\",\"ts\":\"2022-09-08T16:21:53.389+02:00\"}",
                    "externalIdType": "c8y_Serial",
                    "target": "{\"source\":{\"id\":\"909090\"},\"type\":\"c8y_TestAlarm\",\"text\":\"This is a new test alarm!\",\"severity\":\"MAJOR\",\"status\":\"ACTIVE\",\"time\":\"2022-08-05T00:14:49.389+02:00\"}",
                    "templateTopic": "alarm/+",
                    "qos": 1,
                    "substitutions": [
                        {
                            "definesIdentifier": true,
                            "pathSource": "_DEVICE_IDENT_",
                            "pathTarget": "source.id"
                        },
                        {
                            "definesIdentifier": false,
                            "pathSource": "msg_type",
                            "pathTarget": "type"
                        },
                        {
                            "definesIdentifier": false,
                            "pathSource": "tx",
                            "pathTarget": "text"
                        },
                        {
                            "definesIdentifier": false,
                            "pathSource": "ts",
                            "pathTarget": "time"
                        }
                    ],
                    "snoopTemplates": "NONE",
                    "lastUpdate": 1663624993212,
                    "subscriptionTopic": "alarm/#",
                    "snoopedTemplates": [],
                    "id": 2,
                    "indexDeviceIdentifierInTemplateTopic": 2
                },
                {
                    "tested": true,
                    "mapDeviceIdentifier": true,
                    "active": true,
                    "targetAPI": "EVENT",
                    "source": "{\"msg_type\":\"c8y_LoraBellEvent\",\"txt\":\"Elevator was not called today!\",\"ts\":\"2022-09-08T16:21:53.389+02:00\"}",
                    "externalIdType": "c8y_Serial",
                    "target": "{\"source\":{\"id\":\"909090\"},\"text\":\"This is a new test event.\",\"time\":\"2022-08-05T00:14:49.389+02:00\",\"type\":\"c8y_TestEvent\"}",
                    "templateTopic": "/eventing/+/east",
                    "qos": 1,
                    "substitutions": [
                        {
                            "definesIdentifier": true,
                            "pathSource": "_DEVICE_IDENT_",
                            "pathTarget": "source.id"
                        },
                        {
                            "definesIdentifier": false,
                            "pathSource": "txt",
                            "pathTarget": "text"
                        },
                        {
                            "definesIdentifier": false,
                            "pathSource": "ts",
                            "pathTarget": "time"
                        }
                    ],
                    "snoopTemplates": "STARTED",
                    "lastUpdate": 1663847963183,
                    "subscriptionTopic": "/eventing/#",
                    "snoopedTemplates": [
                        "{\n  \"msg_type\": \"c8y_LoraBellEvent\",\n  \"txt\": \"Elevator was not too late yesterday!\",\n  \"ts\": \"2022-09-22T15:10:10.389+02:00\"\n}",
                        "{\n  \"msg_types\": \"c8y_LoraBellEvent\",\n  \"txt\": \"Elevator was not too late yesterday!\",\n  \"ts\": \"2022-09-22T15:10:10.389+02:00\"\n}",
                        "{\n  \"msg_type\": \"c8y_LoraBellEvent\",\n  \"txt\": \"Elevator was not too late yesterday!\",\n  \"ts\": \"2022-09-22T15:10:10.389+02:00\"\n}",
                        "{\n  \"msg_type\": \"c8y_LoraBellEvent\",\n  \"txt\": \"Elevator was not too late yesterday!\",\n  \"ts\": \"2022-09-22T15:10:10.389+02:00\"\n}"
                    ],
                    "id": 3,
                    "indexDeviceIdentifierInTemplateTopic": 3
                }
            ]
}'

# cmd step 2

curl --location --request POST 'https://YOUR_TENANT.eu-latest.cumulocity.com/identity/globalIds/ID_FROM_STEP1/externalIds' \
--header 'Authorization: Basic YOUR_AUTHENTICATION' \
--header 'Content-Type: application/vnd.com.nsn.cumulocity.externalId+json' \
--header 'Accept: application/vnd.com.nsn.cumulocity.externalId+json' \
--data-raw '{
	"externalId": "c8y_mqttMapping",
    "type": "c8y_Serial"
}'