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
                        "pathTarget": "source.id"
                    },
                    {
                        "definesIdentifier": false,
                        "pathSource": "$substringAfter(_TOPIC_LEVEL_[2],\"_\")",
                        "pathTarget": "type"
                    },
                    {
                        "definesIdentifier": false,
                        "pathSource": "$now()",
                        "pathTarget": "time"
                    },
                    {
                        "definesIdentifier": false,
                        "pathSource": "value",
                        "pathTarget": "measure1_Type.V.value"
                    }
                ],
                "updateExistingDevice": false,
                "lastUpdate": 1666273003074,
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
                        "pathTarget": "c8y_ProcessLoadMeasurement.L.value"
                    },
                    {
                        "definesIdentifier": false,
                        "pathSource": "$map($map(mea.values[0].timestamp, $number), function($v, $i, $a) { $fromMillis($v) })",
                        "pathTarget": "time"
                    },
                    {
                        "definesIdentifier": true,
                        "pathSource": "_TOPIC_LEVEL_[1]",
                        "pathTarget": "source.id"
                    }
                ],
                "updateExistingDevice": false,
                "lastUpdate": 1666291110079,
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
                        "pathTarget": "_DEVICE_IDENT_"
                    },
                    {
                        "definesIdentifier": false,
                        "pathSource": "capacity",
                        "pathTarget": "capacity"
                    },
                    {
                        "definesIdentifier": false,
                        "pathSource": "customType",
                        "pathTarget": "type"
                    },
                    {
                        "definesIdentifier": false,
                        "pathSource": "operator&\"-\"&line",
                        "pathTarget": "name"
                    }
                ],
                "updateExistingDevice": false,
                "lastUpdate": 1666332234941,
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
                        "pathTarget": "source.id"
                    },
                    {
                        "definesIdentifier": false,
                        "pathSource": "txt",
                        "pathTarget": "text"
                    },
                    {
                        "definesIdentifier": false,
                        "pathSource": "msg_type",
                        "pathTarget": "type"
                    },
                    {
                        "definesIdentifier": false,
                        "pathSource": "$now()",
                        "pathTarget": "time"
                    }
                ],
                "updateExistingDevice": false,
                "lastUpdate": 1666332542966,
                "snoopedTemplates": [],
                "createNonExistingDevice": false,
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
                        "pathTarget": "source.id"
                    },
                    {
                        "definesIdentifier": false,
                        "pathSource": "mea",
                        "pathTarget": "type"
                    },
                    {
                        "definesIdentifier": false,
                        "pathSource": "$now()",
                        "pathTarget": "time"
                    },
                    {
                        "definesIdentifier": false,
                        "pathSource": "fuel*3.78541",
                        "pathTarget": "c8y_FuelMeasurement.Tank.value"
                    }
                ],
                "updateExistingDevice": false,
                "lastUpdate": 1666333462183,
                "snoopedTemplates": [],
                "createNonExistingDevice": false,
                "id": 14,
                "subscriptionTopic": "measurement/#"
            },
            {
                "snoopStatus": "NONE",
                "templateTopicSample": "multidevice/devices",
                "ident": "a12f9fc0-42b3-4d07-bbdf-cd9faed29997",
                "tested": false,
                "mapDeviceIdentifier": true,
                "active": true,
                "targetAPI": "INVENTORY",
                "source": "{\"device\":[\"d1_id\",\"d2_id\"],\"types\":{\"type_A\":\"type_A\",\"type_B\":\"type_B\"},\"used_name\":[\"Pressure_d1\",\"Pressure_d2\"]}",
                "target": "{\"c8y_IsDevice\":{},\"name\":\"Vibration Sensor\",\"type\":\"maker_Vibration_Sensor\"}",
                "externalIdType": "c8y_Serial",
                "templateTopic": "multidevice/devices",
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
                "updateExistingDevice": false,
                "lastUpdate": 1666337298899,
                "snoopedTemplates": [],
                "createNonExistingDevice": false,
                "id": 15,
                "subscriptionTopic": "multidevice/devices"
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
