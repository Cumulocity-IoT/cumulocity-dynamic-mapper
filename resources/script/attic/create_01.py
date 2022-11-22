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
        "ident": "627EA012-9D07-4E60-B0AC-0D830C30F0E3",
        "tested": false,
        "mapDeviceIdentifier": true,
        "active": true,
        "targetAPI": "MEASUREMENT",
        "source": "{\"value\":100}",
        "externalIdType": "c8y_Serial",
        "target": "{\"measure1_Type\":{\"V\":{\"value\":110,\"unit\":\"C\"}},\"time\":\"2022-08-05T00:14:49.389+02:00\",\"source\":{\"id\":\"909090\"},\"type\":\"c8y_TemperatureMeasurement\"}",
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
        "mappingType": "JSON",
        "lastUpdate": 1666602894015,
        "snoopedTemplates": [],
        "createNonExistingDevice": true,
        "subscriptionTopic": "/plant1/#",
        "id": 10
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
        "externalIdType": "c8y_Serial",
        "target": "{\"c8y_ProcessLoadMeasurement\":{\"L\":{\"value\":110,\"unit\":\"%\"}},\"time\":\"2022-08-05T00:14:49.389+02:00\",\"source\":{\"id\":\"909090\"},\"type\":\"c8y_ProcessLoadMeasurement\"}",
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
        "mappingType": "JSON",
        "lastUpdate": 1666551207391,
        "snoopedTemplates": [],
        "createNonExistingDevice": true,
        "subscriptionTopic": "devices/#",
        "id": 11
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
        "externalIdType": "c8y_Serial",
        "target": "{\"c8y_IsDevice\":{},\"name\":\"Vibration Sensor\",\"type\":\"maker_Vibration_Sensor\",\"capacity\":77}",
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
        "mappingType": "JSON",
        "lastUpdate": 1666551219757,
        "snoopedTemplates": [],
        "createNonExistingDevice": false,
        "subscriptionTopic": "device/#",
        "id": 12
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
        "externalIdType": "c8y_Serial",
        "target": "{\"source\":{\"id\":\"909090\"},\"text\":\"This is a new test event.\",\"time\":\"2022-08-05T00:14:49.389+02:00\",\"type\":\"c8y_TestEvent\"}",
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
        "mappingType": "JSON",
        "lastUpdate": 1666709501654,
        "snoopedTemplates": [],
        "createNonExistingDevice": true,
        "subscriptionTopic": "event/#",
        "id": 13
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
        "externalIdType": "c8y_Serial",
        "target": "{\"c8y_FuelMeasurement\":{\"Tank\":{\"value\":110,\"unit\":\"l\"}},\"time\":\"2022-08-05T00:14:49.389+02:00\",\"source\":{\"id\":\"909090\"},\"type\":\"c8y_FuelMeasurement\"}",
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
        "mappingType": "JSON",
        "lastUpdate": 1666551301416,
        "snoopedTemplates": [],
        "createNonExistingDevice": false,
        "subscriptionTopic": "measurement/#",
        "id": 14
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
        "externalIdType": "c8y_Serial",
        "target": "{\"c8y_IsDevice\":{},\"name\":\"Vibration Sensor\",\"type\":\"maker_Vibration_Sensor\"}",
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
        "mappingType": "JSON",
        "lastUpdate": 1666551290318,
        "snoopedTemplates": [],
        "createNonExistingDevice": false,
        "subscriptionTopic": "multiarray/devices",
        "id": 15
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
        "externalIdType": "c8y_Serial",
        "target": "{\"c8y_TemperatureMeasurement\":{\"T\":{\"value\":110,\"unit\":\"C\"}},\"time\":\"2022-08-05T00:14:49.389+02:00\",\"source\":{\"id\":\"909090\"},\"type\":\"c8y_TemperatureMeasurement\"}",
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
        "mappingType": "JSON",
        "lastUpdate": 1666551278176,
        "snoopedTemplates": [],
        "createNonExistingDevice": true,
        "subscriptionTopic": "arrayType/devices",
        "id": 16
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
        "externalIdType": "c8y_Serial",
        "target": "{\"source\":{\"id\":\"909090\"},\"text\":\"This is a new test event.\",\"time\":\"2022-08-05T00:14:49.389+02:00\",\"type\":\"c8y_TestEvent\",\"customProperties\":\"dummy\"}",
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
        "mappingType": "JSON",
        "lastUpdate": 1666820302312,
        "snoopedTemplates": [],
        "createNonExistingDevice": true,
        "subscriptionTopic": "eventObject/#",
        "id": 17
    },
    {
        "snoopStatus": "NONE",
        "templateTopicSample": "measurementObject/berlin_01/gazoline",
        "ident": "15699050-545d-41d1-a523-acf6de778739",
        "tested": false,
        "mapDeviceIdentifier": true,
        "active": true,
        "targetAPI": "MEASUREMENT",
        "source": "{\"fuel\":65,\"oil\":4.5,\"ts\":\"2022-08-05T00:14:49.389+02:00\",\"mea\":\"c8y_FuelMeasurement\"}",
        "externalIdType": "c8y_Serial",
        "target": "{\"c8y_FuelMeasurement\":{\"Tank\":{\"value\":110,\"unit\":\"l\"}},\"c8y_OilMeasurement\":\"Motor\",\"time\":\"2022-08-05T00:14:49.389+02:00\",\"source\":{\"id\":\"909090\"},\"type\":\"c8y_FuelMeasurement\"}",
        "templateTopic": "measurementObject/+/gazoline",
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
            },
            {
                "definesIdentifier": false,
                "pathSource": "(oil?{\"Motor\": {\"value\":oil, \"unit\":\"l\"}}:null)",
                "pathTarget": "c8y_OilMeasurement",
                "repairStrategy": "REMOVE_IF_MISSING"
            }
        ],
        "updateExistingDevice": false,
        "mappingType": "JSON",
        "lastUpdate": 1666853658739,
        "snoopedTemplates": [],
        "createNonExistingDevice": true,
        "subscriptionTopic": "measurementObject/#",
        "id": 18
    },
    {
        "snoopStatus": "STOPPED",
        "templateTopicSample": "eventCSV/berlin_01",
        "ident": "afe80cf3-49a1-4ddf-9ed9-8afd9bc0f372",
        "tested": false,
        "mapDeviceIdentifier": true,
        "active": true,
        "targetAPI": "MEASUREMENT",
        "source": "{\"message\":\"oil,100,1666863595\"}",
        "externalIdType": "c8y_Serial",
        "target": "{\"c8y_TemperatureMeasurement\":{\"T\":{\"value\":110,\"unit\":\"C\"}},\"time\":\"2022-08-05T00:14:49.389+02:00\",\"source\":{\"id\":\"909090\"},\"type\":\"c8y_TemperatureMeasurement\"}",
        "templateTopic": "eventCSV/+",
        "qos": "AT_LEAST_ONCE",
        "substitutions": [
            {
                "definesIdentifier": false,
                "pathSource": "$split(message,\",\")[1]",
                "pathTarget": "c8y_TemperatureMeasurement.T.value",
                "repairStrategy": "DEFAULT"
            },
            {
                "definesIdentifier": true,
                "pathSource": "_TOPIC_LEVEL_[1]",
                "pathTarget": "source.id",
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
        "mappingType": "FLAT_FILE",
        "lastUpdate": 1666865521593,
        "snoopedTemplates": [
            "oil,100,1666863595",
            "additive,101,1666863595"
        ],
        "createNonExistingDevice": true,
        "subscriptionTopic": "eventCSV/#",
        "id": 19
    },
    {
        "snoopStatus": "STOPPED",
        "templateTopicSample": "uc1",
        "tested": false,
        "ident": "a93fe7f0-cf39-4f85-afc1-85ad33b5cd19",
        "mapDeviceIdentifier": true,
        "active": true,
        "source": "{\"deviceId\":\"863859042393327\",\"version\":\"1\",\"deviceType\":\"20\",\"deviceTimestamp\":\"1664964865345\",\"deviceStatus\":\"BTR\",\"temperature\":\"10\",\"batteryVoltage\":\"3012\",\"initializationStatus\":\"2\",\"batteryAlarmStatus\":\"2\",\"temperatureAlarmStatus\":\"1\",\"spillAlarmStatus\":\"1\",\"connectionInfosMcc\":\"262\",\"connectionInfosMnc\":\"01\",\"connectionInfosChannelId\":\"1F00906\",\"connectionInfosTac\":\"D2FA\",\"connectionInfosBand\":\"LTE BAND 8\",\"connectionInfosBandTec\":\"NBIoT\",\"signalStrength\":\"-101\",\"telegramNumber\":\"183\"}",
        "targetAPI": "MEASUREMENT",
        "externalIdType": "c8y_Serial",
        "target": "{\"c8y_UseCase1\":{\"Temperature\":{\"value\":110,\"unit\":\"°C\"},\"BatteryVoltage\":{\"value\":110,\"unit\":\"mV\"},\"InitializationStatus\":{\"value\":2},\"BatteryAlarmStatus\":{\"value\":2},\"TemperaturAlarmStatus\":{\"value\":2},\"SpillAlarmStatus\":{\"value\":2},\"ConnectionInfosMcc\":{\"value\":2},\"ConnectionInfosMnc\":{\"value\":2},\"SignalStrength\":{\"value\":2,\"unit\":\"dBm\"},\"TelegramNumber\":{\"value\":2}},\"time\":\"2022-10-10T15:14:49.389+02:00\",\"source\":{\"id\":\"909090\"},\"type\":\"c8y_TemperatureMeasurement\"}",
        "templateTopic": "uc1",
        "qos": "AT_LEAST_ONCE",
        "substitutions": [
            {
                "definesIdentifier": true,
                "pathSource": "deviceId",
                "pathTarget": "source.id"
            },
            {
                "definesIdentifier": false,
                "pathSource": "$number(batteryVoltage)",
                "pathTarget": "c8y_UseCase1.BatteryVoltage.value"
            },
            {
                "definesIdentifier": false,
                "pathSource": "$number(initializationStatus)",
                "pathTarget": "c8y_UseCase1.InitializationStatus.value"
            },
            {
                "definesIdentifier": false,
                "pathSource": "$number(batteryAlarmStatus)",
                "pathTarget": "c8y_UseCase1.BatteryAlarmStatus.value"
            },
            {
                "definesIdentifier": false,
                "pathSource": "$number(temperatureAlarmStatus)",
                "pathTarget": "c8y_UseCase1.TemperaturAlarmStatus.value"
            },
            {
                "definesIdentifier": false,
                "pathSource": "$number(spillAlarmStatus)",
                "pathTarget": "c8y_UseCase1.SpillAlarmStatus.value"
            },
            {
                "definesIdentifier": false,
                "pathSource": "$number(connectionInfosMcc)",
                "pathTarget": "c8y_UseCase1.ConnectionInfosMcc.value"
            },
            {
                "definesIdentifier": false,
                "pathSource": "$number(connectionInfosMnc)",
                "pathTarget": "c8y_UseCase1.ConnectionInfosMnc.value"
            },
            {
                "definesIdentifier": false,
                "pathSource": "$number(signalStrength)",
                "pathTarget": "c8y_UseCase1.SignalStrength.value"
            },
            {
                "definesIdentifier": false,
                "pathSource": "$number(telegramNumber)",
                "pathTarget": "c8y_UseCase1.TelegramNumber.value"
            },
            {
                "definesIdentifier": false,
                "pathSource": "$number(temperature)",
                "pathTarget": "c8y_UseCase1.Temperature.value"
            }
        ],
        "mappingType": "JSON",
        "lastUpdate": 1666990617152,
        "snoopedTemplates": [
            "{\"deviceId\":\"863859042393327\",\"version\":\"1\",\"deviceType\":\"20\",\"deviceTimestamp\":\"1664964865345\",\"deviceStatus\":\"BTR\",\"temperature\":\"10\",\"batteryVoltage\":\"3012\",\"initializationStatus\":\"2\",\"batteryAlarmStatus\":\"2\",\"temperatureAlarmStatus\":\"1\",\"spillAlarmStatus\":\"1\",\"connectionInfosMcc\":\"262\",\"connectionInfosMnc\":\"01\",\"connectionInfosChannelId\":\"1F00906\",\"connectionInfosTac\":\"D2FA\",\"connectionInfosBand\":\"LTE BAND 8\",\"connectionInfosBandTec\":\"NBIoT\",\"signalStrength\":\"-101\",\"telegramNumber\":\"183\"}",
            "{\"deviceId\":\"863859042393327\",\"version\":\"1\",\"deviceType\":\"20\",\"deviceTimestamp\":\"1664964865345\",\"deviceStatus\":\"BTR\",\"temperature\":\"10\",\"batteryVoltage\":\"3012\",\"initializationStatus\":\"2\",\"batteryAlarmStatus\":\"2\",\"temperatureAlarmStatus\":\"1\",\"spillAlarmStatus\":\"1\",\"connectionInfosMcc\":\"262\",\"connectionInfosMnc\":\"01\",\"connectionInfosChannelId\":\"1F00906\",\"connectionInfosTac\":\"D2FA\",\"connectionInfosBand\":\"LTE BAND 8\",\"connectionInfosBandTec\":\"NBIoT\",\"signalStrength\":\"-101\",\"telegramNumber\":\"183\"}"
        ],
        "createNonExistingDevice": true,
        "subscriptionTopic": "uc1",
        "id": 20
    },
    {
        "snoopStatus": "NONE",
        "templateTopicSample": "uc2",
        "tested": false,
        "ident": "df7026a1-6039-4a6d-a100-c9ee9bc00eca",
        "mapDeviceIdentifier": true,
        "active": true,
        "targetAPI": "MEASUREMENT",
        "source": "{\"version\":\"1\",\"deviceID\":\"863859042399811\",\"messageCount\":\"33555\",\"timestampDevice\":\"1658526506\",\"firmwareVersion\":\"01.00.22\",\"configID\":\"57\",\"systemStatus\":[\"ALL_OK\"],\"battery\":\"3600\",\"batteryStatus\":\"\",\"operator\":\"23203\",\"signalstrength\":\"-71\",\"temperature\":\"26.7\",\"weight\":\"0\",\"rawWeight\":\"0.9\"}",
        "target": "{\"c8y_UseCase2\":{\"MessageCount\":{\"value\":110},\"ConfigID\":{\"value\":110},\"Battery\":{\"value\":110,\"unit\":\"mV\"},\"Operator\":{\"value\":110},\"SignalStrength\":{\"value\":110,\"unit\":\"dBm\"},\"Temperature\":{\"value\":110,\"unit\":\"°C\"},\"Weight\":{\"value\":110,\"unit\":\"kg\"},\"RawWeight\":{\"value\":110,\"unit\":\"kg\"}},\"time\":\"2022-08-05T00:14:49.389+02:00\",\"source\":{\"id\":\"909090\"},\"type\":\"c8y_UseCase2\"}",
        "externalIdType": "c8y_Serial",
        "templateTopic": "uc2",
        "qos": "AT_LEAST_ONCE",
        "substitutions": [
            {
                "definesIdentifier": false,
                "pathSource": "$number(battery)",
                "pathTarget": "c8y_UseCase2.Battery.value"
            },
            {
                "definesIdentifier": false,
                "pathSource": "$number(operator)",
                "pathTarget": "c8y_UseCase2.Operator.value"
            },
            {
                "definesIdentifier": false,
                "pathSource": "$number(weight)",
                "pathTarget": "c8y_UseCase2.Weight.value"
            },
            {
                "definesIdentifier": false,
                "pathSource": "$number(rawWeight)",
                "pathTarget": "c8y_UseCase2.RawWeight.value"
            },
            {
                "definesIdentifier": true,
                "pathSource": "deviceID",
                "pathTarget": "source.id"
            },
            {
                "definesIdentifier": false,
                "pathSource": "$number(temperature)",
                "pathTarget": "c8y_UseCase2.Temperature.value"
            },
            {
                "definesIdentifier": false,
                "pathSource": "$number(messageCount)",
                "pathTarget": "c8y_UseCase2.MessageCount.value"
            }
        ],
        "mappingType": "JSON",
        "lastUpdate": 1666781695334,
        "snoopedTemplates": [],
        "createNonExistingDevice": true,
        "id": 21,
        "subscriptionTopic": "uc2"
    },
    {
        "snoopStatus": "NONE",
        "templateTopicSample": "uc3",
        "tested": false,
        "ident": "99635fb8-9525-41c1-8e7e-5c1e2a8c2337",
        "mapDeviceIdentifier": true,
        "active": true,
        "targetAPI": "MEASUREMENT",
        "source": "{\"channels\":[{\"id\":\"tableID\",\"value\":\"E00401509A195429\"},{\"id\":\"filllevel\",\"value\":\"-1\"},{\"id\":\"batteryState\",\"value\":\"4\"},{\"id\":\"glassID\",\"value\":\"E00401509A19552D\"},{\"id\":\"glassType\",\"value\":\"5\"},{\"id\":\"servicecall\",\"value\":\"1\"}],\"timestamp\":\"1651565510453\",\"version\":\"3\",\"deviceId\":\"862061044590700\"}",
        "target": "{\"c8y_UseCase3\":{\"FillLevel\":{\"value\":110},\"BatteryState\":{\"value\":110},\"GlassType\":{\"value\":5},\"Servicecall\":{\"value\":5}},\"time\":\"2022-08-05T00:14:49.389+02:00\",\"source\":{\"id\":\"909090\"},\"type\":\"c8y_UseCase3\"}",
        "externalIdType": "c8y_Serial",
        "templateTopic": "uc3",
        "qos": "AT_LEAST_ONCE",
        "substitutions": [
            {
                "definesIdentifier": false,
                "pathSource": "$number(channels[2].value)",
                "pathTarget": "c8y_UseCase3.BatteryState.value"
            },
            {
                "definesIdentifier": false,
                "pathSource": "$number(channels[1].value)",
                "pathTarget": "c8y_UseCase3.FillLevel.value"
            },
            {
                "definesIdentifier": false,
                "pathSource": "$number(channels[5].value)",
                "pathTarget": "c8y_UseCase3.Servicecall.value"
            },
            {
                "definesIdentifier": false,
                "pathSource": "$number(channels[4].value)",
                "pathTarget": "c8y_UseCase3.GlassType.value"
            },
            {
                "definesIdentifier": true,
                "pathSource": "deviceId",
                "pathTarget": "source.id"
            }
        ],
        "mappingType": "JSON",
        "lastUpdate": 1666078320537,
        "snoopedTemplates": [],
        "createNonExistingDevice": true,
        "id": 22,
        "subscriptionTopic": "uc3"
    },
    {
        "snoopStatus": "NONE",
        "templateTopicSample": "uc4",
        "tested": false,
        "ident": "16b768e9-f926-414b-b2a9-c531811e9023",
        "mapDeviceIdentifier": true,
        "active": true,
        "targetAPI": "MEASUREMENT",
        "source": "{\"schemaVersion\":1,\"deviceId\":\"HCN-9273019977\",\"deviceType\":\"FRIDGE:HCN\",\"deviceTimestamp\":1664298571,\"sensors\":[{\"DI-99\":0},{\"DI-2\":0},{\"DI-3\":1},{\"DI-4\":1},{\"DI-5\":0},{\"DI-6\":0},{\"DI-7\":0},{\"DI-8\":0},{\"DI-9\":0},{\"AI-1\":833},{\"AI-2\":599},{\"LS-1\":{\"lat\":51.70143,\"lng\":8.712522,\"speed\":0,\"hdop\":1,\"ts\":1664298571}}],\"rawTelegram\":\"20;FRIDGE:HCN-9273019977;22/09/27 17:09:31 +08;DI-1-DI1=0;DI-2-DI2=0;DI-3-DI3=1;DI-4-DI4=1;DI-5-DI5=0;DI-6-DI6=0;DI-7-DI7=0;DI-8-DI8=0;DI-9-DI9=0;AI-1-AI1=833,17;AI-2-AI2=599,2.8;LS-1-GPS={+51.701431,+8.712522,0,285,1.0},22/09/27 17:09:31 +08;\"}",
        "target": "{\"c8y_UseCase4\":{\"TürOffen\":{\"value\":1},\"BelegungssensorVorneLinks\":{\"value\":1},\"BelegungssensorVorneMitte\":{\"value\":1},\"BelegungssensorVorneRechts\":{\"value\":1},\"BelegungssensorMitteLinks\":{\"value\":1},\"BelegungssensorMitteMitte\":{\"value\":1},\"BelegungssensorMitteRechts\":{\"value\":1},\"BelegungssensorHintenLinks\":{\"value\":1},\"BelegungssensorHintenMitte\":{\"value\":1},\"Temperatur\":{\"value\":5},\"PositionGetränkeschieber\":{\"value\":5}},\"time\":\"2022-08-05T00:14:49.389+02:00\",\"source\":{\"id\":\"909090\"},\"type\":\"c8y_UseCase4\"}",
        "externalIdType": "c8y_Serial",
        "templateTopic": "uc4",
        "qos": "AT_LEAST_ONCE",
        "substitutions": [
            {
                "definesIdentifier": false,
                "pathSource": "sensors[0].'DI-1'",
                "pathTarget": "c8y_UseCase4.TürOffen.value"
            },
            {
                "definesIdentifier": false,
                "pathSource": "sensors[9].'AI-1'",
                "pathTarget": "c8y_UseCase4.Temperatur.value"
            },
            {
                "definesIdentifier": false,
                "pathSource": "sensors[10].'AI-2'",
                "pathTarget": "c8y_UseCase4.PositionGetränkeschieber.value"
            },
            {
                "definesIdentifier": true,
                "pathSource": "deviceId",
                "pathTarget": "source.id"
            },
            {
                "pathSource": "$exists(sensors[1].'DI-2')? {\"value\": sensors[1].'DI-2'}: null",
                "pathTarget": "c8y_UseCase4.BelegungssensorVorneLinks",
                "repairStrategy": "REMOVE_IF_MISSING"
            },
            {
                "pathSource": "$exists(sensors[2].'DI-3')? {\"value\": sensors[2].'DI-3'}: null",
                "pathTarget": "c8y_UseCase4.BelegungssensorVorneMitte",
                "repairStrategy": "REMOVE_IF_MISSING"
            },
            {
                "pathSource": "$exists(sensors[3].'DI-4')? {\"value\": sensors[3].'DI-4'}: null",
                "pathTarget": "c8y_UseCase4.BelegungssensorVorneRechts",
                "repairStrategy": "REMOVE_IF_MISSING"
            },
            {
                "pathSource": "$exists(sensors[4].'DI-5')? {\"value\": sensors[4].'DI-5'}: null",
                "pathTarget": "c8y_UseCase4.BelegungssensorMitteLinks",
                "repairStrategy": "REMOVE_IF_MISSING"
            },
            {
                "pathSource": "$exists(sensors[5].'DI-6')? {\"value\": sensors[5].'DI-6'}: null",
                "pathTarget": "c8y_UseCase4.BelegungssensorMitteMitte",
                "repairStrategy": "REMOVE_IF_MISSING"
            },
            {
                "pathSource": "$exists(sensors[6].'DI-7')? {\"value\": sensors[6].'DI-7'}: null",
                "pathTarget": "c8y_UseCase4.BelegungssensorMitteRechts",
                "repairStrategy": "REMOVE_IF_MISSING"
            },
            {
                "pathSource": "$exists(sensors[7].'DI-8')? {\"value\": sensors[7].'DI-8'}: null",
                "pathTarget": "c8y_UseCase4.BelegungssensorHintenLinks",
                "repairStrategy": "REMOVE_IF_MISSING"
            },
            {
                "pathSource": "$exists(sensors[8].'DI-9')? {\"value\": sensors[8].'DI-9'}: null",
                "pathTarget": "c8y_UseCase4.BelegungssensorHintenMitte",
                "repairStrategy": "REMOVE_IF_MISSING"
            }
        ],
        "mappingType": "JSON",
        "lastUpdate": 1667026960183,
        "snoopedTemplates": [],
        "createNonExistingDevice": true,
        "id": 23,
        "subscriptionTopic": "uc4"
    },
    {
        "snoopStatus": "NONE",
        "templateTopicSample": "uc1",
        "tested": false,
        "ident": "ca4eeefe-ac21-4687-8258-dd78c17f6d06",
        "mapDeviceIdentifier": true,
        "active": true,
        "targetAPI": "INVENTORY",
        "source": "{\"deviceId\":\"863859042393327\",\"version\":\"1\",\"deviceType\":\"20\",\"deviceTimestamp\":\"1664964865345\",\"deviceStatus\":\"BTR\",\"temperature\":\"20\",\"batteryVoltage\":\"3012\",\"initializationStatus\":\"2\",\"batteryAlarmStatus\":\"2\",\"temperatureAlarmStatus\":\"1\",\"spillAlarmStatus\":\"1\",\"connectionInfosMcc\":\"262\",\"connectionInfosMnc\":\"01\",\"connectionInfosChannelId\":\"1F00906\",\"connectionInfosTac\":\"D2FA\",\"connectionInfosBand\":\"LTE BAND 8\",\"connectionInfosBandTec\":\"NBIoT\",\"signalStrength\":\"-101\",\"telegramNumber\":\"183\"}",
        "target": "{\"connectionInfosChannelId\":\"\",\"deviceStatus\":\"BTR\",\"connectionInfosTac\":\"D2FA\",\"connectionInfosBand\":\"LTE BAND 8\",\"connectionInfosBandTec\":\"NBIoT\"}",
        "externalIdType": "c8y_Serial",
        "templateTopic": "uc1",
        "qos": "AT_LEAST_ONCE",
        "substitutions": [
            {
                "definesIdentifier": true,
                "pathSource": "deviceId",
                "pathTarget": "_DEVICE_IDENT_"
            },
            {
                "definesIdentifier": false,
                "pathSource": "deviceStatus",
                "pathTarget": "deviceStatus"
            },
            {
                "definesIdentifier": false,
                "pathSource": "connectionInfosChannelId",
                "pathTarget": "connectionInfosChannelId"
            },
            {
                "definesIdentifier": false,
                "pathSource": "connectionInfosTac",
                "pathTarget": "connectionInfosTac"
            },
            {
                "definesIdentifier": false,
                "pathSource": "connectionInfosBand",
                "pathTarget": "connectionInfosBand"
            },
            {
                "definesIdentifier": false,
                "pathSource": "connectionInfosBandTec",
                "pathTarget": "connectionInfosBandTec"
            }
        ],
        "updateExistingDevice": true,
        "mappingType": "JSON",
        "lastUpdate": 1665578188592,
        "snoopedTemplates": [],
        "createNonExistingDevice": false,
        "id": 24,
        "subscriptionTopic": "uc1"
    },
    {
        "snoopStatus": "NONE",
        "templateTopicSample": "uc1",
        "tested": false,
        "ident": "0345a02b-f7e5-4548-92a4-8390ca95e713",
        "mapDeviceIdentifier": true,
        "active": true,
        "targetAPI": "EVENT",
        "source": "{\"deviceId\":\"863859042393327\",\"version\":\"1\",\"deviceType\":\"20\",\"deviceTimestamp\":\"1664964865345\",\"deviceStatus\":\"BTR\",\"temperature\":\"20\",\"batteryVoltage\":\"3012\",\"initializationStatus\":\"2\",\"batteryAlarmStatus\":\"2\",\"temperatureAlarmStatus\":\"1\",\"spillAlarmStatus\":\"1\",\"connectionInfosMcc\":\"262\",\"connectionInfosMnc\":\"01\",\"connectionInfosChannelId\":\"1F00906\",\"connectionInfosTac\":\"D2FA\",\"connectionInfosBand\":\"LTE BAND 8\",\"connectionInfosBandTec\":\"NBIoT\",\"signalStrength\":\"-101\",\"telegramNumber\":\"183\"}",
        "target": "{\"source\":{\"id\":\"909090\"},\"text\":\"Neuer Status vom Gerät.\",\"time\":\"2022-08-05T00:14:49.389+02:00\",\"type\":\"zustand\",\"status\":\"\"}",
        "externalIdType": "c8y_Serial",
        "templateTopic": "uc1",
        "qos": "AT_LEAST_ONCE",
        "substitutions": [
            {
                "definesIdentifier": true,
                "pathSource": "deviceId",
                "pathTarget": "source.id"
            },
            {
                "definesIdentifier": false,
                "pathSource": "$fromMillis($number(deviceTimestamp))",
                "pathTarget": "time"
            },
            {
                "definesIdentifier": false,
                "pathSource": "deviceStatus",
                "pathTarget": "status"
            },
            {
                "definesIdentifier": false,
                "pathSource": "$join([\"Neuer Status vom Gerät: \",deviceStatus])",
                "pathTarget": "text"
            }
        ],
        "updateExistingDevice": false,
        "mappingType": "JSON",
        "lastUpdate": 1665578792958,
        "snoopedTemplates": [],
        "createNonExistingDevice": true,
        "id": 25,
        "subscriptionTopic": "uc1"
    },
    {
        "snoopStatus": "NONE",
        "templateTopicSample": "uc2",
        "tested": false,
        "ident": "3892eb64-9ab7-48f6-8443-ee08ca411092",
        "mapDeviceIdentifier": true,
        "active": true,
        "targetAPI": "EVENT",
        "source": "{\"version\":\"1\",\"deviceID\":\"863859042399811\",\"messageCount\":\"33555\",\"timestampDevice\":\"1658526506\",\"firmwareVersion\":\"01.00.22\",\"configID\":\"57\",\"systemStatus\":[\"ALL_OK\"],\"battery\":\"3600\",\"batteryStatus\":\"\",\"operator\":\"23203\",\"signalstrength\":\"-71\",\"temperature\":\"26.7\",\"weight\":\"0\",\"rawWeight\":\"0.9\"}",
        "target": "{\"source\":{\"id\":\"909090\"},\"text\":\"This is a new test event.\",\"time\":\"2022-08-05T00:14:49.389+02:00\",\"type\":\"uc2_status\",\"status\":\"\"}",
        "externalIdType": "c8y_Serial",
        "templateTopic": "uc2",
        "qos": "AT_LEAST_ONCE",
        "substitutions": [
            {
                "definesIdentifier": true,
                "pathSource": "deviceID",
                "pathTarget": "source.id"
            },
            {
                "definesIdentifier": false,
                "pathSource": "systemStatus[0]",
                "pathTarget": "status"
            },
            {
                "definesIdentifier": false,
                "pathSource": "$join([\"Neuer Status \",systemStatus[0]])",
                "pathTarget": "text",
                "repairStrategy": "DEFAULT"
            }
        ],
        "updateExistingDevice": false,
        "mappingType": "JSON",
        "lastUpdate": 1666899083733,
        "snoopedTemplates": [],
        "createNonExistingDevice": true,
        "id": 26,
        "subscriptionTopic": "uc2"
    },
    {
        "snoopStatus": "NONE",
        "templateTopicSample": "uc3",
        "tested": false,
        "ident": "66735aa5-647d-4cd9-8a10-50721823699b",
        "mapDeviceIdentifier": true,
        "active": true,
        "targetAPI": "INVENTORY",
        "source": "{\"channels\":[{\"id\":\"tableID\",\"value\":\"E00401509A195429\"},{\"id\":\"filllevel\",\"value\":\"1\"},{\"id\":\"batteryState\",\"value\":\"4\"},{\"id\":\"glassID\",\"value\":\"E00401509A19552D\"},{\"id\":\"glassType\",\"value\":\"5\"},{\"id\":\"servicecall\",\"value\":\"1\"}],\"timestamp\":\"1651565510453\",\"version\":\"3\",\"deviceId\":\"862061044590700\"}",
        "target": "{\"glassID\":\"TemplateId\",\"glassType\":5,\"tableID\":\"TableId\",\"serviceCall\":0}",
        "externalIdType": "c8y_Serial",
        "templateTopic": "uc3",
        "qos": "AT_LEAST_ONCE",
        "substitutions": [
            {
                "definesIdentifier": false,
                "pathSource": "channels[3].value",
                "pathTarget": "glassID"
            },
            {
                "definesIdentifier": false,
                "pathSource": "$number(channels[4].value)",
                "pathTarget": "glassType"
            },
            {
                "definesIdentifier": false,
                "pathSource": "channels[0].value",
                "pathTarget": "tableID"
            },
            {
                "definesIdentifier": false,
                "pathSource": "$number(channels[5].value)",
                "pathTarget": "serviceCall"
            },
            {
                "definesIdentifier": true,
                "pathSource": "deviceId",
                "pathTarget": "_DEVICE_IDENT_"
            }
        ],
        "updateExistingDevice": true,
        "mappingType": "JSON",
        "lastUpdate": 1665582006836,
        "snoopedTemplates": [],
        "createNonExistingDevice": true,
        "id": 27,
        "subscriptionTopic": "uc3"
    },
    {
        "snoopStatus": "NONE",
        "templateTopicSample": "uc2",
        "tested": false,
        "ident": "872670f3-9f2b-49dd-97b7-bdb30ed71dfd",
        "mapDeviceIdentifier": true,
        "active": true,
        "targetAPI": "INVENTORY",
        "source": "{\"version\":\"1\",\"deviceID\":\"863859042399811\",\"messageCount\":\"33555\",\"timestampDevice\":\"1658526506\",\"firmwareVersion\":\"01.00.22\",\"configID\":\"57\",\"systemStatus\":[\"ALL_OK\"],\"battery\":\"3600\",\"batteryStatus\":\"\",\"operator\":\"23203\",\"signalstrength\":\"-71\",\"temperature\":\"26.7\",\"weight\":\"0\",\"rawWeight\":\"0.9\"}",
        "target": "{\"c8y_IsDevice\":{},\"name\":\"Vibration Sensor\",\"type\":\"maker_Vibration_Sensor\",\"sys_status\":\"temp\"}",
        "externalIdType": "c8y_Serial",
        "templateTopic": "uc2",
        "qos": "EXACTLY_ONCE",
        "substitutions": [
            {
                "definesIdentifier": true,
                "pathSource": "deviceID",
                "pathTarget": "_DEVICE_IDENT_"
            },
            {
                "definesIdentifier": false,
                "pathSource": "systemStatus[0]",
                "pathTarget": "sys_status"
            },
            {
                "definesIdentifier": false,
                "pathSource": "\"iScale\"",
                "pathTarget": "type"
            },
            {
                "definesIdentifier": false,
                "pathSource": "$join([\"iScale_\",deviceID])",
                "pathTarget": "name"
            }
        ],
        "updateExistingDevice": true,
        "mappingType": "JSON",
        "lastUpdate": 1666589025596,
        "snoopedTemplates": [],
        "createNonExistingDevice": false,
        "id": 28,
        "subscriptionTopic": "uc2"
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
