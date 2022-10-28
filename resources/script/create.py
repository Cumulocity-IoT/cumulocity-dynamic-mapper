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

    payload_create_mo = json.dumps(
  {
    "name": "MQTT Mapping",
    "type": "c8y_mqttMapping",
    "mapDeviceIdentifier": false,
    "active": false,
    "c8y_mqttMapping": [
        {
            "snoopStatus": "NONE",
            "templateTopicSample": "/plant1/line1/device1_measure1_Type",
            "tested": false,
            "ident": "627EA012-9D07-4E60-B0AC-0D830C30F0E3",
            "mapDeviceIdentifier": true,
            "active": true,
            "targetAPI": "MEASUREMENT",
            "source": "{\"value\":100}",
            "target": "{\"measure1_Type\":{\"V\":{\"value\":110,\"unit\":\"C\"}},\"time\":\"2022-08-05T00:14:49.389+02:00\",\"source\":{\"id\":\"909090\"},\"type\":\"c8y_TemperatureMeasurement\"}",
            "externalIdType": "c8y_Serial",
            "templateTopic": "/plant1/+/+",
            "qos": "AT_LEAST_ONCE",
            "substitutions": [
                {
                    "definesIdentifier": true,
                    "pathSource": "_TOPIC_LEVEL_[0]&\"_\"&_TOPIC_LEVEL_[1]&\"_\"&$substringBefore(_TOPIC_LEVEL_[2],\"_\")",
                    "pathTarget": "source.id",
                    "repairStrategy": "DEFAULT"
                },
                {
                    "definesIdentifier": false,
                    "pathSource": "$substringAfter(_TOPIC_LEVEL_[2],\"_\")",
                    "pathTarget": "type",
                    "repairStrategy": "DEFAULT"
                },
                {
                    "definesIdentifier": false,
                    "pathSource": "$now()",
                    "pathTarget": "time",
                    "repairStrategy": "DEFAULT"
                },
                {
                    "definesIdentifier": false,
                    "pathSource": "value",
                    "pathTarget": "measure1_Type.V.value",
                    "repairStrategy": "DEFAULT"
                }
            ],
            "updateExistingDevice": false,
            "lastUpdate": 1666602894015,
            "snoopedTemplates": [],
            "createNonExistingDevice": true,
            "id": 10,
            "subscriptionTopic": "/plant1/#",
            "indexDeviceIdentifierInTemplateTopic": -1
        },
        {
            "snoopStatus": "NONE",
            "templateTopicSample": "devices/device_best_01",
            "ident": "05241eba-e0c5-4c77-a723-15511dee8709",
            "tested": false,
            "mapDeviceIdentifier": true,
            "active": true,
            "targetAPI": "MEASUREMENT",
            "source": "{\"mea\":[{\"tid\":\"uuid_01\",\"psid\":\"Crest\",\"devicePath\":\"path01_80_X03_VVB001StatusB_Crest\",\"values\":[{\"value\":4.6,\"timestamp\":1648562285347}]},{\"tid\":\"uuid_02\",\"psid\":\"Crest\",\"devicePath\":\"path01_80_X03_VVB001StatusB_Crest\",\"values\":[{\"value\":5.6,\"timestamp\":1648563285347}]}]}",
            "target": "{\"c8y_ProcessLoadMeasurement\":{\"L\":{\"value\":110,\"unit\":\"%\"}},\"time\":\"2022-08-05T00:14:49.389+02:00\",\"source\":{\"id\":\"909090\"},\"type\":\"c8y_ProcessLoadMeasurement\"}",
            "externalIdType": "c8y_Serial",
            "templateTopic": "devices/+",
            "qos": "AT_LEAST_ONCE",
            "substitutions": [
                {
                    "definesIdentifier": false,
                    "pathSource": "mea.values[0].value",
                    "pathTarget": "c8y_ProcessLoadMeasurement.L.value",
                    "repairStrategy": "DEFAULT"
                },
                {
                    "definesIdentifier": false,
                    "pathSource": "$map($map(mea.values[0].timestamp, $number), function($v, $i, $a) { $fromMillis($v) })",
                    "pathTarget": "time",
                    "repairStrategy": "DEFAULT"
                },
                {
                    "definesIdentifier": true,
                    "pathSource": "_TOPIC_LEVEL_[1]",
                    "pathTarget": "source.id",
                    "repairStrategy": "DEFAULT"
                }
            ],
            "updateExistingDevice": false,
            "lastUpdate": 1666551207391,
            "snoopedTemplates": [],
            "createNonExistingDevice": true,
            "id": 11,
            "subscriptionTopic": "devices/#"
        },
        {
            "snoopStatus": "NONE",
            "templateTopicSample": "device/express/berlin_01",
            "ident": "38c5ebbd-990c-4eeb-b556-75109b37c904",
            "tested": true,
            "mapDeviceIdentifier": true,
            "active": true,
            "targetAPI": "INVENTORY",
            "source": "{\"line\":\"Bus-Berlin-Rom\",\"operator\":\"EuroBus\",\"customFragment\":{\"customFragmentValue\":\"Express\"},\"capacity\":64,\"customArray\":[\"ArrayValue1\",\"ArrayValue2\"],\"customType\":\"type_International\"}",
            "target": "{\"c8y_IsDevice\":{},\"name\":\"Vibration Sensor\",\"type\":\"maker_Vibration_Sensor\",\"capacity\":77}",
            "externalIdType": "c8y_Serial",
            "templateTopic": "device/express/+",
            "qos": "AT_LEAST_ONCE",
            "substitutions": [
                {
                    "definesIdentifier": true,
                    "pathSource": "_TOPIC_LEVEL_[2]",
                    "pathTarget": "_DEVICE_IDENT_",
                    "repairStrategy": "DEFAULT"
                },
                {
                    "definesIdentifier": false,
                    "pathSource": "capacity",
                    "pathTarget": "capacity",
                    "repairStrategy": "DEFAULT"
                },
                {
                    "definesIdentifier": false,
                    "pathSource": "customType",
                    "pathTarget": "type",
                    "repairStrategy": "DEFAULT"
                },
                {
                    "definesIdentifier": false,
                    "pathSource": "operator&\"-\"&line",
                    "pathTarget": "name",
                    "repairStrategy": "DEFAULT"
                }
            ],
            "updateExistingDevice": false,
            "lastUpdate": 1666551219757,
            "snoopedTemplates": [],
            "createNonExistingDevice": false,
            "id": 12,
            "subscriptionTopic": "device/#"
        },
        {
            "snoopStatus": "NONE",
            "templateTopicSample": "event/berlin_01",
            "ident": "9109c667-16a3-486f-babf-9d01afdf1d2b",
            "tested": false,
            "mapDeviceIdentifier": true,
            "active": true,
            "targetAPI": "EVENT",
            "source": "{\"msg_type\":\"c8y_BusStopEvent\",\"txt\":\"Bus stopped at petrol station today!\",\"td\":\"2022-09-08T16:21:53.389+02:00\",\"ts\":\"1665473038000\"}",
            "target": "{\"source\":{\"id\":\"909090\"},\"text\":\"This is a new test event.\",\"time\":\"2022-08-05T00:14:49.389+02:00\",\"type\":\"c8y_TestEvent\"}",
            "externalIdType": "c8y_Serial",
            "templateTopic": "event/+",
            "qos": "AT_LEAST_ONCE",
            "substitutions": [
                {
                    "definesIdentifier": true,
                    "pathSource": "_TOPIC_LEVEL_[1]",
                    "pathTarget": "source.id",
                    "repairStrategy": "DEFAULT"
                },
                {
                    "definesIdentifier": false,
                    "pathSource": "txt",
                    "pathTarget": "text",
                    "repairStrategy": "DEFAULT"
                },
                {
                    "definesIdentifier": false,
                    "pathSource": "msg_type",
                    "pathTarget": "type",
                    "repairStrategy": "DEFAULT"
                },
                {
                    "definesIdentifier": false,
                    "pathSource": "$now()",
                    "pathTarget": "time",
                    "repairStrategy": "DEFAULT"
                }
            ],
            "updateExistingDevice": false,
            "lastUpdate": 1666709501654,
            "snoopedTemplates": [],
            "createNonExistingDevice": true,
            "id": 13,
            "subscriptionTopic": "event/#"
        },
        {
            "snoopStatus": "NONE",
            "templateTopicSample": "measurement/berlin_01/gazoline",
            "ident": "2b295739-c1c4-4c2c-ae9e-2f04dc8313f6",
            "tested": false,
            "mapDeviceIdentifier": true,
            "active": true,
            "targetAPI": "MEASUREMENT",
            "source": "{\"fuel\":65,\"ts\":\"2022-08-05T00:14:49.389+02:00\",\"mea\":\"c8y_FuelMeasurement\"}",
            "target": "{\"c8y_FuelMeasurement\":{\"Tank\":{\"value\":110,\"unit\":\"l\"}},\"time\":\"2022-08-05T00:14:49.389+02:00\",\"source\":{\"id\":\"909090\"},\"type\":\"c8y_FuelMeasurement\"}",
            "externalIdType": "c8y_Serial",
            "templateTopic": "measurement/+/gazoline",
            "qos": "AT_LEAST_ONCE",
            "substitutions": [
                {
                    "definesIdentifier": true,
                    "pathSource": "_TOPIC_LEVEL_[1]",
                    "pathTarget": "source.id",
                    "repairStrategy": "DEFAULT"
                },
                {
                    "definesIdentifier": false,
                    "pathSource": "mea",
                    "pathTarget": "type",
                    "repairStrategy": "DEFAULT"
                },
                {
                    "definesIdentifier": false,
                    "pathSource": "$now()",
                    "pathTarget": "time",
                    "repairStrategy": "DEFAULT"
                },
                {
                    "definesIdentifier": false,
                    "pathSource": "fuel*3.78541",
                    "pathTarget": "c8y_FuelMeasurement.Tank.value",
                    "repairStrategy": "DEFAULT"
                }
            ],
            "updateExistingDevice": false,
            "lastUpdate": 1666551301416,
            "snoopedTemplates": [],
            "createNonExistingDevice": false,
            "id": 14,
            "subscriptionTopic": "measurement/#"
        },
        {
            "snoopStatus": "NONE",
            "templateTopicSample": "multiarray/devices",
            "ident": "e87a10ad-c223-4bd7-bd91-9df23e2bd3cf",
            "tested": false,
            "mapDeviceIdentifier": true,
            "active": true,
            "targetAPI": "INVENTORY",
            "source": "{\"device\":[\"d1_id\",\"d2_id\"],\"types\":{\"type_A\":\"type_A\",\"type_B\":\"type_B\"},\"used_name\":[\"Pressure_d1\",\"Pressure_d2\"]}",
            "target": "{\"c8y_IsDevice\":{},\"name\":\"Vibration Sensor\",\"type\":\"maker_Vibration_Sensor\"}",
            "externalIdType": "c8y_Serial",
            "templateTopic": "multiarray/devices",
            "qos": "AT_LEAST_ONCE",
            "substitutions": [
                {
                    "definesIdentifier": true,
                    "pathSource": "device",
                    "pathTarget": "_DEVICE_IDENT_",
                    "repairStrategy": "DEFAULT"
                },
                {
                    "definesIdentifier": false,
                    "pathSource": "types.type_A",
                    "pathTarget": "type",
                    "repairStrategy": "DEFAULT"
                },
                {
                    "definesIdentifier": false,
                    "pathSource": "$map(used_name, function($v, $i, $a) { $contains($v,'d1') ? $join(['Special_i0', $string($i)]) : $join([$string($v), $string($i)]) } )",
                    "pathTarget": "name",
                    "repairStrategy": "DEFAULT"
                }
            ],
            "updateExistingDevice": false,
            "lastUpdate": 1666551290318,
            "snoopedTemplates": [],
            "createNonExistingDevice": false,
            "id": 15,
            "subscriptionTopic": "multiarray/devices"
        },
        {
            "snoopStatus": "NONE",
            "templateTopicSample": "arrayType/devices",
            "ident": "c35424fc-aa6e-42f9-8434-66444e68d2aa",
            "tested": false,
            "mapDeviceIdentifier": true,
            "active": true,
            "targetAPI": "MEASUREMENT",
            "source": "[{\"tid\":\"5e4bac9f-b47a-499e-8601-68fc16a9847c\",\"psid\":\"Crest\",\"devicePath\":\"c2818e07-4c09-42f0-ba24-ddb712573ab5_AL1352_192168221_80_X03_VVB001StatusB_Crest\",\"processDataUnit\":\"20\",\"values\":[{\"value\":4.6,\"timestamp\":1648562285347}]},{\"tid\":\"5e4bac9f-b47a-499e-8601-68fc16a9847c\",\"psid\":\"Crest\",\"devicePath\":\"c2818e07-4c09-42f0-ba24-ddb712573ab5_AL1352_192168221_80_X03_VVB001StatusB_Crest\",\"processDataUnit\":\"20\",\"values\":[{\"value\":5.6,\"timestamp\":1648562285347}]}]",
            "target": "{\"c8y_TemperatureMeasurement\":{\"T\":{\"value\":110,\"unit\":\"C\"}},\"time\":\"2022-08-05T00:14:49.389+02:00\",\"source\":{\"id\":\"909090\"},\"type\":\"c8y_TemperatureMeasurement\"}",
            "externalIdType": "c8y_Serial",
            "templateTopic": "arrayType/devices",
            "qos": "AT_LEAST_ONCE",
            "substitutions": [
                {
                    "definesIdentifier": true,
                    "pathSource": "$substringBefore($[0].devicePath,\"_AL\")",
                    "pathTarget": "source.id",
                    "repairStrategy": "DEFAULT"
                },
                {
                    "definesIdentifier": false,
                    "pathSource": "$[].values[0].value",
                    "pathTarget": "c8y_TemperatureMeasurement.T.value",
                    "repairStrategy": "DEFAULT"
                },
                {
                    "definesIdentifier": false,
                    "pathSource": "$map($map($[].values[0].timestamp, $number), function($v) { $fromMillis($v)})",
                    "pathTarget": "time",
                    "repairStrategy": "DEFAULT"
                }
            ],
            "updateExistingDevice": true,
            "lastUpdate": 1666551278176,
            "snoopedTemplates": [],
            "createNonExistingDevice": true,
            "id": 16,
            "subscriptionTopic": "arrayType/devices"
        },
        {
            "snoopStatus": "NONE",
            "templateTopicSample": "eventObject/berlin_01",
            "ident": "def10a25-d11e-4201-b454-48b54ba8cd5e",
            "tested": false,
            "mapDeviceIdentifier": true,
            "active": true,
            "targetAPI": "EVENT",
            "source": "{\"msg_type\":\"c8y_BusStopEvent\",\"txt\":\"Bus stopped at petrol station today!\",\"td\":\"2022-09-08T16:21:53.389+02:00\",\"model\":{\"name\":\"MAN e-Bus\"}}",
            "target": "{\"source\":{\"id\":\"909090\"},\"text\":\"This is a new test event.\",\"time\":\"2022-08-05T00:14:49.389+02:00\",\"type\":\"c8y_TestEvent\",\"customProperties\":\"dummy\"}",
            "externalIdType": "c8y_Serial",
            "templateTopic": "eventObject/+",
            "qos": "AT_LEAST_ONCE",
            "substitutions": [
                {
                    "definesIdentifier": true,
                    "pathSource": "_TOPIC_LEVEL_[1]",
                    "pathTarget": "source.id",
                    "repairStrategy": "DEFAULT"
                },
                {
                    "definesIdentifier": false,
                    "pathSource": "txt",
                    "pathTarget": "text",
                    "repairStrategy": "DEFAULT"
                },
                {
                    "definesIdentifier": false,
                    "pathSource": "msg_type",
                    "pathTarget": "type",
                    "repairStrategy": "DEFAULT"
                },
                {
                    "definesIdentifier": false,
                    "pathSource": "$now()",
                    "pathTarget": "time",
                    "repairStrategy": "DEFAULT"
                },
                {
                    "definesIdentifier": false,
                    "pathSource": "model",
                    "pathTarget": "customProperties",
                    "repairStrategy": "REMOVE_IF_MISSING"
                }
            ],
            "updateExistingDevice": false,
            "lastUpdate": 1666820302312,
            "snoopedTemplates": [],
            "createNonExistingDevice": true,
            "id": 17,
            "subscriptionTopic": "eventObject/#"
        }
    ]
}
        )

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
