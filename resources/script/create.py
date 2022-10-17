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
                "source": "{\"line\":\"Bus-Berlin-Rom\",\"operator\":\"Flix\",\"customFragment\":{\"customFragmentValue\":\"Express\"},\"capacity\":64,\"customArray\":[\"ArrayValue1\",\"ArrayValue2\"],\"customType\":\"type_International\"}",
                "targetAPI": "INVENTORY",
                "externalIdType": "c8y_Serial",
                "target": "{\"c8y_IsDevice\":{},\"name\":\"Vibration Sensor\",\"type\":\"maker_Vibration_Sensor\"}",
                "templateTopic": "device/express/+",
                "qos": "AT_LEAST_ONCE",
                "substitutions": [
                    {
                        "definesIdentifier": true,
                        "pathTarget": "_DEVICE_IDENT_",
                        "pathSource": "_DEVICE_IDENT_"
                    },
                    {
                        "definesIdentifier": false,
                        "pathTarget": "type",
                        "pathSource": "customType"
                    },
                    {
                        "definesIdentifier": false,
                        "pathTarget": "name",
                        "pathSource": "$join([operator, \"-\", line])"
                    }
                ],
                "snoopStatus": "STOPPED",
                "updateExistingDevice": false,
                "lastUpdate": 1666009770743,
                "snoopedTemplates": [
                    "{\n\t\"customName\": \"Bus-Berlin-Rom\",\n\t\"customText\": \"Rom\",\n\t\"customFragment\": {\n\t\t\"customFragmentValue\": \"customValueNew\"\n\t},\n\t\"customNumber\": 10,\n\t\"customArray\": [\n\t\t\"ArrayValue1\",\n\t\t\"ArrayValue2\"\n\t],\n\t\"customType\": \"type_Bus\"\n}\n"
                ],
                "createNonExistingDevice": false,
                "subscriptionTopic": "device/#",
                "id": 1,
                "indexDeviceIdentifierInTemplateTopic": 4
            },
            {
                "tested": false,
                "mapDeviceIdentifier": true,
                "active": true,
                "source": "{\"customType\":\"Over-Night\"}",
                "targetAPI": "INVENTORY",
                "externalIdType": "c8y_Serial",
                "target": "{\"c8y_IsDevice\":{},\"type\":\"maker_Vibration_Sensor\"}",
                "templateTopic": "device/update/+",
                "qos": "AT_LEAST_ONCE",
                "substitutions": [
                    {
                        "definesIdentifier": true,
                        "pathTarget": "_DEVICE_IDENT_",
                        "pathSource": "_DEVICE_IDENT_"
                    },
                    {
                        "definesIdentifier": false,
                        "pathTarget": "type",
                        "pathSource": "customType"
                    }
                ],
                "snoopStatus": "NONE",
                "updateExistingDevice": true,
                "lastUpdate": 1666013932915,
                "snoopedTemplates": [],
                "createNonExistingDevice": false,
                "subscriptionTopic": "device/update",
                "id": 2,
                "indexDeviceIdentifierInTemplateTopic": 4
            },
            {
                "tested": true,
                "mapDeviceIdentifier": true,
                "active": true,
                "source": "{\"msg_type\":\"c8y_BusStopEvent\",\"txt\":\"Bus stopped at petrol station today!\",\"ts\":\"2022-09-08T16:21:53.389+02:00\"}",
                "targetAPI": "EVENT",
                "externalIdType": "c8y_Serial",
                "target": "{\"source\":{\"id\":\"909090\"},\"text\":\"This is a new test event.\",\"time\":\"2022-08-05T00:14:49.389+02:00\",\"type\":\"c8y_GeneralBusEvent\"}",
                "templateTopic": "/event/+",
                "qos": "AT_LEAST_ONCE",
                "substitutions": [
                    {
                        "definesIdentifier": true,
                        "pathTarget": "source.id",
                        "pathSource": "_DEVICE_IDENT_"
                    },
                    {
                        "definesIdentifier": false,
                        "pathTarget": "text",
                        "pathSource": "txt"
                    },
                    {
                        "definesIdentifier": false,
                        "pathTarget": "type",
                        "pathSource": "msg_type"
                    }
                ],
                "snoopStatus": "STOPPED",
                "updateExistingDevice": false,
                "lastUpdate": 1666024767545,
                "snoopedTemplates": [
                    "{\n  \"msg_type\": \"c8y_LoraBellEvent\",\n  \"txt\": \"Elevator was not too late yesterday!\",\n  \"ts\": \"2022-09-22T15:10:10.389+02:00\"\n}",
                    "{\n  \"msg_type\": \"c8y_LoraBellEvent\",\n  \"txt\": \"Door was not too late last week!\",\n  \"ts\": \"2022-09-22810:10:10.389+02:00\"\n}",
                    "{\n  \"msg_type\": \"c8y_LoraBellEvent\",\n  \"txt\": \"Door was not too late last week!\",\n  \"ts\": \"2022-09-22810:10:10.389+02:00\"\n}",
                    "{\n  \"msg_type\": \"c8y_BusStopEvent\",\n  \"txt\": \"Bus stopped at petrol stationtoday!\",\n  \"ts\": \"2022-09-08T16:21:53.389+02:00\"\n}"
                ],
                "createNonExistingDevice": false,
                "subscriptionTopic": "/event/#",
                "id": 3,
                "indexDeviceIdentifierInTemplateTopic": 3
            },
            {
                "tested": true,
                "mapDeviceIdentifier": true,
                "active": true,
                "source": "{\"fuel\":65,\"ts\":\"2022-08-05T00:14:49.389+02:00\",\"mea\":\"c8y_FuelMeasurement\"}",
                "targetAPI": "MEASUREMENT",
                "externalIdType": "c8y_Serial",
                "target": "{\"c8y_FuelMeasurement\":{\"L\":{\"value\":110,\"unit\":\"L\"}},\"time\":\"2022-10-18T00:14:49.389+02:00\",\"source\":{\"id\":\"909090\"},\"type\":\"c8y_FuelMeasurement\"}",
                "templateTopic": "measurement/+/gazoline",
                "qos": "AT_LEAST_ONCE",
                "substitutions": [
                    {
                        "definesIdentifier": true,
                        "pathSource": "_DEVICE_IDENT_",
                        "pathTarget": "source.id"
                    },
                    {
                        "definesIdentifier": false,
                        "pathSource": "fuel",
                        "pathTarget": "c8y_FuelMeasurement.L.value"
                    }
                ],
                "snoopStatus": "NONE",
                "updateExistingDevice": false,
                "lastUpdate": 1666025431423,
                "snoopedTemplates": [],
                "createNonExistingDevice": false,
                "subscriptionTopic": "measurement/#",
                "id": 4,
                "indexDeviceIdentifierInTemplateTopic": 2
            },
            {
                "tested": false,
                "mapDeviceIdentifier": true,
                "active": true,
                "targetAPI": "ALARM",
                "source": "{\"msg_type\":\"c8y_FlatTireAlarm\",\"tx\":\"Left rear tire loses air!\",\"ts\":\"2022-09-08T16:21:53.389+02:00\",\"bus_id\":\"berlin\"}",
                "target": "{\"source\":{\"id\":\"909090\"},\"type\":\"c8y_TestAlarm\",\"text\":\"This is a new test alarm!\",\"severity\":\"MAJOR\",\"status\":\"ACTIVE\",\"time\":\"2022-08-05T00:14:49.389+02:00\"}",
                "externalIdType": "c8y_Serial",
                "templateTopic": "alarm/tires",
                "qos": "AT_LEAST_ONCE",
                "substitutions": [
                    {
                        "definesIdentifier": true,
                        "pathSource": "bus_id",
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
                    }
                ],
                "snoopStatus": "NONE",
                "updateExistingDevice": false,
                "lastUpdate": 1666027537992,
                "snoopedTemplates": [],
                "createNonExistingDevice": false,
                "id": 5,
                "subscriptionTopic": "alarm/tires",
                "indexDeviceIdentifierInTemplateTopic": -1
            },
            {
                "tested": false,
                "mapDeviceIdentifier": true,
                "active": true,
                "source": "{\"deviceId\":\"863859042393327\",\"version\":\"1\",\"deviceType\":\"20\",\"deviceTimestamp\":\"1664964865345\",\"deviceStatus\":\"BTR\",\"temperature\":10,\"batteryVoltage\":\"3012\",\"initializationStatus\":\"2\",\"batteryAlarmStatus\":\"2\",\"temperatureAlarmStatus\":\"1\",\"spillAlarmStatus\":\"1\",\"connectionInfosMcc\":\"262\",\"connectionInfosMnc\":\"01\",\"connectionInfosChannelId\":\"1F00906\",\"connectionInfosTac\":\"D2FA\",\"connectionInfosBand\":\"LTE BAND 8\",\"connectionInfosBandTec\":\"NBIoT\",\"signalStrength\":\"-101\",\"telegramNumber\":\"183\"}",
                "targetAPI": "MEASUREMENT",
                "externalIdType": "c8y_Serial",
                "target": "{\"c8y_TemperatureMeasurement\":{\"T\":{\"value\":110,\"unit\":\"C\"}},\"time\":\"2022-08-05T00:14:49.389+02:00\",\"source\":{\"id\":\"909090\"},\"type\":\"c8y_TemperatureMeasurement\"}",
                "templateTopic": "panel",
                "qos": "AT_MOST_ONCE",
                "substitutions": [
                    {
                        "definesIdentifier": false,
                        "pathTarget": "c8y_TemperatureMeasurement.T.value",
                        "pathSource": "temperature"
                    },
                    {
                        "definesIdentifier": true,
                        "pathTarget": "source.id",
                        "pathSource": "deviceId"
                    },
                    {
                        "definesIdentifier": false,
                        "pathTarget": "time",
                        "pathSource": "$fromMillis($number(deviceTimestamp))"
                    }
                ],
                "snoopStatus": "NONE",
                "updateExistingDevice": false,
                "lastUpdate": 1665468494071,
                "snoopedTemplates": [],
                "createNonExistingDevice": true,
                "subscriptionTopic": "panel",
                "id": 6,
                "indexDeviceIdentifierInTemplateTopic": -1
            },
            {
                "tested": false,
                "mapDeviceIdentifier": true,
                "active": true,
                "source": "{\"deviceId\":\"863859042393327\",\"version\":\"1\",\"deviceType\":\"20\",\"deviceTimestamp\":\"1664964865345\",\"deviceStatus\":\"BTR\",\"temperature\":10,\"batteryVoltage\":\"3012\",\"initializationStatus\":\"2\",\"batteryAlarmStatus\":\"2\",\"temperatureAlarmStatus\":\"1\",\"spillAlarmStatus\":\"1\",\"connectionInfosMcc\":\"262\",\"connectionInfosMnc\":\"01\",\"connectionInfosChannelId\":\"1F00906\",\"connectionInfosTac\":\"D2FA\",\"connectionInfosBand\":\"LTE BAND 8\",\"connectionInfosBandTec\":\"NBIoT\",\"signalStrength\":\"-102\",\"telegramNumber\":\"183\"}",
                "targetAPI": "EVENT",
                "externalIdType": "c8y_Serial",
                "target": "{\"source\":{\"id\":\"909090\"},\"text\":\"This is a new test event.\",\"time\":\"2022-08-05T00:14:49.389+02:00\",\"type\":\"c8y_TestEvent\"}",
                "templateTopic": "panel",
                "qos": "AT_LEAST_ONCE",
                "substitutions": [
                    {
                        "definesIdentifier": false,
                        "pathTarget": "text",
                        "pathSource": "$join(['New device status: ',deviceStatus,'!'])"
                    },
                    {
                        "definesIdentifier": true,
                        "pathTarget": "source.id",
                        "pathSource": "deviceId"
                    },
                    {
                        "definesIdentifier": false,
                        "pathTarget": "time",
                        "pathSource": "$fromMillis($number(deviceTimestamp))"
                    }
                ],
                "snoopStatus": "NONE",
                "updateExistingDevice": false,
                "lastUpdate": 1666013314897,
                "snoopedTemplates": [],
                "createNonExistingDevice": false,
                "subscriptionTopic": "panel",
                "id": 7,
                "indexDeviceIdentifierInTemplateTopic": -1
            },
            {
                "tested": false,
                "mapDeviceIdentifier": true,
                "active": true,
                "source": "{\"device\":[\"d1_id\",\"d2_id\"],\"types\":{\"type_A\":\"type_A\",\"type_B\":\"type_B\"},\"used_name\":[\"Pressure_d1\",\"Pressure_d2\"]}",
                "targetAPI": "INVENTORY",
                "externalIdType": "c8y_Serial",
                "target": "{\"c8y_IsDevice\":{},\"name\":\"Pressure Sensor\",\"type\":\"maker_Pressure_Sensor\"}",
                "templateTopic": "special/devices",
                "qos": "AT_LEAST_ONCE",
                "substitutions": [
                    {
                        "definesIdentifier": true,
                        "pathTarget": "_DEVICE_IDENT_",
                        "pathSource": "device"
                    },
                    {
                        "definesIdentifier": false,
                        "pathTarget": "type",
                        "pathSource": "types.type_A"
                    },
                    {
                        "definesIdentifier": false,
                        "pathTarget": "name",
                        "pathSource": "$map(used_name, function($v, $i, $a) { $contains($v,'d1') ? $join(['Special_i0', $string($i)]) : $join([$string($v), $string($i)]) } )"
                    }
                ],
                "snoopStatus": "NONE",
                "updateExistingDevice": false,
                "lastUpdate": 1664823280448,
                "snoopedTemplates": [],
                "createNonExistingDevice": false,
                "subscriptionTopic": "special/devices",
                "id": 8,
                "indexDeviceIdentifierInTemplateTopic": -1
            },
            {
                "tested": false,
                "mapDeviceIdentifier": true,
                "active": true,
                "source": "{\"msg_type\":\"c8y_LoraBellAlarm\",\"tx\":\"Elevator was not called\",\"ts\":\"2022-09-08T16:21:53.389+02:00\"}",
                "targetAPI": "ALARM",
                "externalIdType": "c8y_Serial",
                "target": "{\"source\":{\"id\":\"909090\"},\"type\":\"c8y_TestAlarm\",\"text\":\"This is a new test alarm!\",\"severity\":\"MAJOR\",\"status\":\"ACTIVE\",\"time\":\"2022-08-05T00:14:49.389+02:00\"}",
                "templateTopic": "alarm/+",
                "qos": "AT_LEAST_ONCE",
                "substitutions": [
                    {
                        "definesIdentifier": true,
                        "pathTarget": "source.id",
                        "pathSource": "_DEVICE_IDENT_"
                    },
                    {
                        "definesIdentifier": false,
                        "pathTarget": "type",
                        "pathSource": "msg_type"
                    },
                    {
                        "definesIdentifier": false,
                        "pathTarget": "text",
                        "pathSource": "tx"
                    }
                ],
                "snoopStatus": "NONE",
                "updateExistingDevice": false,
                "lastUpdate": 1666007300920,
                "snoopedTemplates": [],
                "createNonExistingDevice": false,
                "subscriptionTopic": "alarm/#",
                "id": 9,
                "indexDeviceIdentifierInTemplateTopic": 2
            },
            {
                "tested": false,
                "mapDeviceIdentifier": true,
                "active": true,
                "source": "{\"channels\":[{\"id\":\"tableID\",\"value\":\"E00401509A195429\"},{\"id\":\"filllevel\",\"value\":\"-2\"},{\"id\":\"batteryState\",\"value\":\"4\"},{\"id\":\"glassID\",\"value\":\"E00401509A19552D\"},{\"id\":\"glassType\",\"value\":\"5\"},{\"id\":\"servicecall\",\"value\":\"1\"}],\"timestamp\":\"1651565510453\",\"version\":\"3\",\"deviceId\":\"862061044590700\"}",
                "targetAPI": "MEASUREMENT",
                "externalIdType": "c8y_Serial",
                "target": "{\"c8y_UseCase3\":{\"FillLevel\":{\"value\":110},\"BatteryState\":{\"value\":110},\"GlassType\":{\"value\":5},\"Servicecall\":{\"value\":5}},\"time\":\"2022-08-05T00:14:49.389+02:00\",\"source\":{\"id\":\"909090\"},\"type\":\"c8y_UseCase3\"}",
                "templateTopic": "uc3",
                "qos": "AT_LEAST_ONCE",
                "substitutions": [
                    {
                        "definesIdentifier": true,
                        "pathTarget": "source.id",
                        "pathSource": "deviceId"
                    },
                    {
                        "definesIdentifier": false,
                        "pathTarget": "c8y_UseCase3.GlassType.value",
                        "pathSource": "$number(channels[4].value)"
                    },
                    {
                        "definesIdentifier": false,
                        "pathTarget": "c8y_UseCase3.BatteryState.value",
                        "pathSource": "$number(channels[2].value)"
                    },
                    {
                        "definesIdentifier": false,
                        "pathTarget": "c8y_UseCase3.FillLevel.value",
                        "pathSource": "$number(channels[1].value)"
                    },
                    {
                        "definesIdentifier": false,
                        "pathTarget": "c8y_UseCase3.Servicecall.value",
                        "pathSource": "$number(channels[5].value)"
                    },
                    {
                        "definesIdentifier": false,
                        "pathTarget": "time",
                        "pathSource": "$fromMillis($number(timestamp))"
                    }
                ],
                "snoopStatus": "NONE",
                "updateExistingDevice": false,
                "lastUpdate": 1666013140134,
                "snoopedTemplates": [],
                "createNonExistingDevice": true,
                "subscriptionTopic": "uc3",
                "id": 10,
                "indexDeviceIdentifierInTemplateTopic": -1
            },
            {
                "tested": false,
                "mapDeviceIdentifier": true,
                "active": true,
                "source": "{\"schemaVersion\":1,\"deviceId\":\"HCN-9273019977\",\"deviceType\":\"FRIDGE:HCN\",\"deviceTimestamp\":1664298571,\"sensors\":[{\"DI-1\":0},{\"DI-2\":0},{\"DI-3\":1},{\"DI-4\":1},{\"DI-5\":0},{\"DI-6\":0},{\"DI-7\":0},{\"DI-8\":0},{\"DI-9\":0},{\"AI-1\":833},{\"AI-2\":599},{\"LS-1\":{\"lat\":51.70143,\"lng\":8.712522,\"speed\":0,\"hdop\":1,\"ts\":1664298571}}],\"rawTelegram\":\"20;FRIDGE:HCN-9273019977;22/09/27 17:09:31 +08;DI-1-DI1=0;DI-2-DI2=0;DI-3-DI3=1;DI-4-DI4=1;DI-5-DI5=0;DI-6-DI6=0;DI-7-DI7=0;DI-8-DI8=0;DI-9-DI9=0;AI-1-AI1=833,17;AI-2-AI2=599,2.8;LS-1-GPS={+51.701431,+8.712522,0,285,1.0},22/09/27 17:09:31 +08;\"}",
                "targetAPI": "MEASUREMENT",
                "externalIdType": "c8y_Serial",
                "target": "{\"c8y_UseCase4\":{\"TürOffen\":{\"value\":1},\"BelegungssensorVorneLinks\":{\"value\":1},\"BelegungssensorVorneMitte\":{\"value\":1},\"BelegungssensorVorneRechts\":{\"value\":1},\"BelegungssensorMitteLinks\":{\"value\":1},\"BelegungssensorMitteMitte\":{\"value\":1},\"BelegungssensorMitteRechts\":{\"value\":1},\"BelegungssensorHintenLinks\":{\"value\":1},\"BelegungssensorHintenMitte\":{\"value\":1},\"Temperatur\":{\"value\":5},\"PositionGetränkeschieber\":{\"value\":5}},\"time\":\"2022-08-05T00:14:49.389+02:00\",\"source\":{\"id\":\"909090\"},\"type\":\"c8y_UseCase4\"}",
                "templateTopic": "uc4",
                "qos": "AT_LEAST_ONCE",
                "substitutions": [
                    {
                        "definesIdentifier": false,
                        "pathTarget": "c8y_UseCase4.TürOffen.value",
                        "pathSource": "sensors[0].`DI-1`"
                    },
                    {
                        "definesIdentifier": false,
                        "pathTarget": "c8y_UseCase4.BelegungssensorVorneLinks.value",
                        "pathSource": "sensors[1].`DI-2`"
                    },
                    {
                        "definesIdentifier": false,
                        "pathTarget": "c8y_UseCase4.BelegungssensorVorneMitte.value",
                        "pathSource": "sensors[2].`DI-3`"
                    },
                    {
                        "definesIdentifier": false,
                        "pathTarget": "c8y_UseCase4.BelegungssensorVorneRechts.value",
                        "pathSource": "sensors[3].`DI-4`"
                    },
                    {
                        "definesIdentifier": false,
                        "pathTarget": "c8y_UseCase4.BelegungssensorMitteLinks.value",
                        "pathSource": "sensors[4].`DI-5`"
                    },
                    {
                        "definesIdentifier": false,
                        "pathTarget": "c8y_UseCase4.BelegungssensorMitteMitte.value",
                        "pathSource": "sensors[5].`DI-6`"
                    },
                    {
                        "definesIdentifier": false,
                        "pathTarget": "c8y_UseCase4.BelegungssensorMitteRechts.value",
                        "pathSource": "sensors[6].`DI-7`"
                    },
                    {
                        "definesIdentifier": false,
                        "pathTarget": "c8y_UseCase4.BelegungssensorHintenLinks.value",
                        "pathSource": "sensors[7].`DI-8`"
                    },
                    {
                        "definesIdentifier": false,
                        "pathTarget": "c8y_UseCase4.BelegungssensorHintenMitte.value",
                        "pathSource": "sensors[8].`DI-9`"
                    },
                    {
                        "definesIdentifier": false,
                        "pathTarget": "c8y_UseCase4.Temperatur.value",
                        "pathSource": "sensors[9].`AI-1`"
                    },
                    {
                        "definesIdentifier": false,
                        "pathTarget": "c8y_UseCase4.PositionGetränkeschieber.value",
                        "pathSource": "sensors[10].`AI-2`"
                    },
                    {
                        "definesIdentifier": true,
                        "pathTarget": "source.id",
                        "pathSource": "deviceId"
                    }
                ],
                "snoopStatus": "NONE",
                "updateExistingDevice": false,
                "lastUpdate": 1665584441907,
                "snoopedTemplates": [],
                "createNonExistingDevice": true,
                "subscriptionTopic": "uc4",
                "id": 11,
                "indexDeviceIdentifierInTemplateTopic": -1
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
