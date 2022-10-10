import base64
import requests
import json
import sys
import getopt


def main(argv):
    opts, args = getopt.getopt(
        argv, "p:t:u:d", ["password=", "tenant=", "user=", "dummy="])
    for opt, arg in opts:
        if opt == '-h':
            print('create.py -t <tenant> -u <user> -p <password>')
            sys.exit()
        elif opt in ("-t", "--tenant"):
            tenant = arg
        elif opt in ("-p", "--password"):
            secret = arg
        elif opt in ("-u", "--user"):
            user = arg

    url1 = f"{tenant}/inventory/managedObjects"
    up = f'{user}:{secret}'
    up_encoded = base64.b64encode(up.encode("utf-8")).decode("utf-8")
    token = f'Basic {up_encoded}'

    headers = {
        'Authorization': token,
        'Content-Type': 'application/json',
        'Accept': 'application/json'
    }

    payload_create_mo = json.dumps({
        "name": "MQTT Mapping",
        "type": "c8y_mqttMapping",
        "mapDeviceIdentifier": false,
        "active": false,
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
                    "qos": "AT_LEAST_ONCE",
                    "substitutions": [
                        {
                            "definesIdentifier": true,
                            "pathSource": "_DEVICE_IDENT_",
                            "pathTarget": "_DEVICE_IDENT_"
                        }
                    ],
                    "snoopTemplates": "STOPPED",
                    "lastUpdate": 1663690366869,
                    "snoopedTemplates": [
                        "{\n\t\"customName\": \"Bus-Berlin-Rom\",\n\t\"customText\": \"Rom\",\n\t\"customFragment\": {\n\t\t\"customFragmentValue\": \"customValueNew\"\n\t},\n\t\"customNumber\": 10,\n\t\"customArray\": [\n\t\t\"ArrayValue1\",\n\t\t\"ArrayValue2\"\n\t],\n\t\"customType\": \"type_Bus\"\n}\n"
                    ],
                    "subscriptionTopic": "device/#",
                    "id": 1,
                    "indexDeviceIdentifierInTemplateTopic": 2,
                    "createNonExistingDevice": false
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
                    "qos": "AT_LEAST_ONCE",
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
                    "snoopedTemplates": [],
                    "subscriptionTopic": "alarm/#",
                    "id": 2,
                    "indexDeviceIdentifierInTemplateTopic": 2,
                    "createNonExistingDevice": false
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
                    "qos": "AT_LEAST_ONCE",
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
                    "lastUpdate": 1664342814816,
                    "snoopedTemplates": [
                        "{\n  \"msg_type\": \"c8y_LoraBellEvent\",\n  \"txt\": \"Elevator was not too late yesterday!\",\n  \"ts\": \"2022-09-22T15:10:10.389+02:00\"\n}",
                        "{\n  \"msg_type\": \"c8y_LoraBellEvent\",\n  \"txt\": \"Elevator was not too late yesterday!\",\n  \"ts\": \"2022-09-22T15:10:10.389+02:00\"\n}",
                        "{\n  \"msg_type\": \"c8y_LoraBellEvent\",\n  \"txt\": \"Door was not too late last week!\",\n  \"ts\": \"2022-09-22810:10:10.389+02:00\"\n}",
                        "{\n  \"msg_type\": \"c8y_LoraBellEvent\",\n  \"txt\": \"Door was not too late last week!\",\n  \"ts\": \"2022-09-22810:10:10.389+02:00\"\n}"
                    ],
                    "subscriptionTopic": "/eventing/#",
                    "id": 3,
                    "indexDeviceIdentifierInTemplateTopic": 3,
                    "createNonExistingDevice": false
            },
            {
                    "tested": true,
                    "mapDeviceIdentifier": true,
                    "active": true,
                    "targetAPI": "MEASUREMENT",
                    "source": "{\"value\":100,\"ts\":\"2022-08-05T00:14:49.389+02:00\",\"mea\":\"c8y_TemeratureMeasurement\"}",
                    "target": "{\"c8y_TemperatureMeasurement\":{\"T\":{\"value\":110,\"unit\":\"C\"}},\"time\":\"2022-08-05T00:14:49.389+02:00\",\"source\":{\"id\":\"909090\"},\"type\":\"c8y_TemperatureMeasurement\"}",
                    "externalIdType": "c8y_Serial",
                    "templateTopic": "measure/+",
                    "qos": "AT_LEAST_ONCE",
                    "substitutions": [
                        {
                            "definesIdentifier": true,
                            "pathSource": "_DEVICE_IDENT_",
                            "pathTarget": "source.id"
                        },
                        {
                            "definesIdentifier": false,
                            "pathSource": "ts",
                            "pathTarget": "time"
                        },
                        {
                            "definesIdentifier": false,
                            "pathSource": "mea",
                            "pathTarget": "type"
                        }
                    ],
                    "snoopTemplates": "NONE",
                    "lastUpdate": 1664519049169,
                    "snoopedTemplates": [],
                    "id": 4,
                    "subscriptionTopic": "measure/#",
                    "indexDeviceIdentifierInTemplateTopic": 2,
                    "createNonExistingDevice": false
            },
            {
                    "tested": false,
                    "mapDeviceIdentifier": true,
                    "active": true,
                    "targetAPI": "INVENTORY",
                    "source": "{\"device\":[\"d1_id\",\"d2_id\"],\"types\":{\"type_A\":\"type_A\",\"type_B\":\"type_B\"},\"used_name\":[\"Pressure_d1\",\"Pressure_d2\"]}",
                    "target": "{\"c8y_IsDevice\":{},\"name\":\"Vibration Sensor\",\"type\":\"maker_Vibration_Sensor\"}",
                    "externalIdType": "c8y_Serial",
                    "templateTopic": "special/devices",
                    "qos": "AT_LEAST_ONCE",
                    "substitutions": [
                        {
                            "definesIdentifier": true,
                            "pathSource": "device",
                            "pathTarget": "_DEVICE_IDENT_"
                        },
                        {
                            "definesIdentifier": false,
                            "pathSource": "types.type_A",
                            "pathTarget": "type"
                        },
                        {
                            "definesIdentifier": false,
                            "pathSource": "$map(used_name, function($v, $i, $a) { $contains($v,'d1') ? $join(['Special_i0', $string($i)]) : $join([$string($v), $string($i)]) } )",
                            "pathTarget": "name"
                        }
                    ],
                    "snoopTemplates": "NONE",
                    "lastUpdate": 1664823280448,
                    "snoopedTemplates": [],
                    "id": 5,
                    "subscriptionTopic": "special/devices",
                    "indexDeviceIdentifierInTemplateTopic": -1,
                    "createNonExistingDevice": false
            },
            {
                    "tested": false,
                    "mapDeviceIdentifier": false,
                    "active": false,
                    "targetAPI": "MEASUREMENT",
                    "source": "{}",
                    "target": "{\"c8y_TemperatureMeasurement\":{\"T\":{\"value\":110,\"unit\":\"C\"}},\"time\":\"2022-08-05T00:14:49.389+02:00\",\"source\":{\"id\":\"909090\"},\"type\":\"c8y_TemperatureMeasurement\"}",
                    "externalIdType": "c8y_Serial",
                    "templateTopic": "sg",
                    "qos": "AT_MOST_ONCE",
                    "substitutions": [],
                    "snoopTemplates": "NONE",
                    "lastUpdate": 1665211609366,
                    "snoopedTemplates": [],
                    "id": 6,
                    "subscriptionTopic": "sg",
                    "indexDeviceIdentifierInTemplateTopic": -1,
                    "createNonExistingDevice": false
            }
        ]
    })

    response1 = requests.request(
        "POST", url1, headers=headers, data=payload_create_mo)
    jsonResponse = response1.json()
    id = jsonResponse["id"]
    print(f'Created mapping {id}')

    url2 = f"{tenant}/identity/globalIds/{id}/externalIds"
    payload_create_id = json.dumps({
        "externalId": "c8y_mqttMapping",
        "type": "c8y_Serial"
    })
    response2 = requests.request(
        "POST", url2, headers=headers, data=payload_create_id)
    text = response2.text
    print(f'Created mapping external id {text}')


if __name__ == "__main__":
    main(sys.argv[1:])
