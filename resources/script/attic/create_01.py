[
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
                "pathSource": "_TOPIC_LEVEL_[0]&\"_\"&_TOPIC_LEVEL_[1]&\"_\"&$substringBefore(_TOPIC_LEVEL_[2],\"_\")",
                "pathTarget": "source.id",
                "repairStrategy": "DEFAULT",
                "expandArray": false
            },
            {
                "pathSource": "$substringAfter(_TOPIC_LEVEL_[2],\"_\")",
                "pathTarget": "type",
                "repairStrategy": "DEFAULT",
                "expandArray": false
            },
            {
                "pathSource": "$now() + 1000",
                "pathTarget": "time",
                "repairStrategy": "DEFAULT",
                "expandArray": false
            },
            {
                "pathSource": "value",
                "pathTarget": "measure1_Type.V.value",
                "repairStrategy": "DEFAULT",
                "expandArray": false
            }
        ],
        "updateExistingDevice": false,
        "lastUpdate": 1668979858758,
        "mappingType": "JSON",
        "snoopedTemplates": [],
        "createNonExistingDevice": true,
        "subscriptionTopic": "/plant1/#",
        "id": "50086727"
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
                "pathSource": "mea.values[0].value",
                "pathTarget": "c8y_ProcessLoadMeasurement.L.value",
                "repairStrategy": "DEFAULT",
                "expandArray": true
            },
            {
                "pathSource": "$map($map(mea.values[0].timestamp, $number), function($v, $i, $a) { $fromMillis($v) })",
                "pathTarget": "time",
                "repairStrategy": "DEFAULT",
                "expandArray": true
            },
            {
                "pathSource": "_TOPIC_LEVEL_[1]",
                "pathTarget": "source.id",
                "repairStrategy": "DEFAULT",
                "expandArray": false
            }
        ],
        "updateExistingDevice": false,
        "lastUpdate": 1667995548939,
        "mappingType": "JSON",
        "snoopedTemplates": [],
        "createNonExistingDevice": true,
        "subscriptionTopic": "devices/#",
        "id": "50088617"
    },
    {
        "snoopStatus": "NONE",
        "templateTopicSample": "device/express/berlin_01",
        "ident": "38c5ebbd-990c-4eeb-b556-75109b37c904",
        "tested": false,
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
                "pathSource": "_TOPIC_LEVEL_[2]",
                "pathTarget": "_DEVICE_IDENT_",
                "repairStrategy": "DEFAULT",
                "expandArray": false
            },
            {
                "pathSource": "capacity",
                "pathTarget": "capacity",
                "repairStrategy": "DEFAULT",
                "expandArray": false
            },
            {
                "pathSource": "customType",
                "pathTarget": "type",
                "repairStrategy": "DEFAULT",
                "expandArray": false
            },
            {
                "pathSource": "operator&\"-\"&line",
                "pathTarget": "name",
                "repairStrategy": "DEFAULT",
                "expandArray": false
            }
        ],
        "updateExistingDevice": false,
        "lastUpdate": 1667925977880,
        "mappingType": "JSON",
        "snoopedTemplates": [],
        "createNonExistingDevice": false,
        "subscriptionTopic": "device/#",
        "id": "50087720"
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
                "pathSource": "_TOPIC_LEVEL_[1]",
                "pathTarget": "source.id",
                "repairStrategy": "DEFAULT",
                "expandArray": false
            },
            {
                "pathSource": "txt",
                "pathTarget": "text",
                "repairStrategy": "DEFAULT",
                "expandArray": false
            },
            {
                "pathSource": "msg_type",
                "pathTarget": "type",
                "repairStrategy": "DEFAULT",
                "expandArray": false
            },
            {
                "pathSource": "$now()",
                "pathTarget": "time",
                "repairStrategy": "DEFAULT",
                "expandArray": false
            }
        ],
        "updateExistingDevice": false,
        "lastUpdate": 1668574191732,
        "mappingType": "JSON",
        "snoopedTemplates": [],
        "createNonExistingDevice": true,
        "subscriptionTopic": "event/#",
        "id": "50086750"
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
                "pathSource": "_TOPIC_LEVEL_[1]",
                "pathTarget": "source.id",
                "repairStrategy": "DEFAULT",
                "expandArray": false
            },
            {
                "pathSource": "mea",
                "pathTarget": "type",
                "repairStrategy": "DEFAULT",
                "expandArray": false
            },
            {
                "pathSource": "$now()",
                "pathTarget": "time",
                "repairStrategy": "DEFAULT",
                "expandArray": false
            },
            {
                "pathSource": "fuel*3.78541",
                "pathTarget": "c8y_FuelMeasurement.Tank.value",
                "repairStrategy": "DEFAULT",
                "expandArray": false
            }
        ],
        "updateExistingDevice": false,
        "lastUpdate": 1667758218993,
        "mappingType": "JSON",
        "snoopedTemplates": [],
        "createNonExistingDevice": true,
        "subscriptionTopic": "measurement/#",
        "id": "50086751"
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
                "pathSource": "device",
                "pathTarget": "_DEVICE_IDENT_",
                "repairStrategy": "DEFAULT",
                "expandArray": true
            },
            {
                "pathSource": "types.type_A",
                "pathTarget": "type",
                "repairStrategy": "DEFAULT",
                "expandArray": false
            },
            {
                "pathSource": "$map(used_name, function($v, $i, $a) { $contains($v,'d1') ? $join(['Special_i0', $string($i)]) : $join([$string($v), $string($i)]) } )",
                "pathTarget": "name",
                "repairStrategy": "DEFAULT",
                "expandArray": true
            }
        ],
        "updateExistingDevice": false,
        "lastUpdate": 1668635844108,
        "mappingType": "JSON",
        "snoopedTemplates": [],
        "createNonExistingDevice": false,
        "subscriptionTopic": "multiarray/devices",
        "id": "50090058"
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
                "pathSource": "$substringBefore($[0].devicePath,\"_AL\")",
                "pathTarget": "source.id",
                "repairStrategy": "DEFAULT",
                "expandArray": false
            },
            {
                "pathSource": "$[].values[0].value",
                "pathTarget": "c8y_TemperatureMeasurement.T.value",
                "repairStrategy": "DEFAULT",
                "expandArray": true
            },
            {
                "pathSource": "$map($map($[].values[0].timestamp, $number), function($v) { $fromMillis($v)})",
                "pathTarget": "time",
                "repairStrategy": "DEFAULT",
                "expandArray": true
            }
        ],
        "updateExistingDevice": true,
        "lastUpdate": 1668101581794,
        "mappingType": "JSON",
        "snoopedTemplates": [],
        "createNonExistingDevice": true,
        "subscriptionTopic": "arrayType/devices",
        "id": "50087726"
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
                "pathSource": "_TOPIC_LEVEL_[1]",
                "pathTarget": "source.id",
                "repairStrategy": "DEFAULT",
                "expandArray": false
            },
            {
                "pathSource": "txt",
                "pathTarget": "text",
                "repairStrategy": "DEFAULT",
                "expandArray": false
            },
            {
                "pathSource": "msg_type",
                "pathTarget": "type",
                "repairStrategy": "DEFAULT",
                "expandArray": false
            },
            {
                "pathSource": "$now()",
                "pathTarget": "time",
                "repairStrategy": "DEFAULT",
                "expandArray": false
            },
            {
                "pathSource": "model",
                "pathTarget": "customProperties",
                "repairStrategy": "REMOVE_IF_MISSING",
                "expandArray": false
            }
        ],
        "updateExistingDevice": false,
        "lastUpdate": 1666820302312,
        "mappingType": "JSON",
        "snoopedTemplates": [],
        "createNonExistingDevice": true,
        "subscriptionTopic": "eventObject/#",
        "id": "50086756"
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
                "pathSource": "_TOPIC_LEVEL_[1]",
                "pathTarget": "source.id",
                "repairStrategy": "DEFAULT",
                "expandArray": false
            },
            {
                "pathSource": "mea",
                "pathTarget": "type",
                "repairStrategy": "DEFAULT",
                "expandArray": false
            },
            {
                "pathSource": "$now()",
                "pathTarget": "time",
                "repairStrategy": "DEFAULT",
                "expandArray": false
            },
            {
                "pathSource": "fuel*3.78541",
                "pathTarget": "c8y_FuelMeasurement.Tank.value",
                "repairStrategy": "DEFAULT",
                "expandArray": false
            },
            {
                "pathSource": "(oil?{\"Motor\": {\"value\":oil, \"unit\":\"l\"}}:null)",
                "pathTarget": "c8y_OilMeasurement",
                "repairStrategy": "REMOVE_IF_MISSING",
                "expandArray": false
            }
        ],
        "updateExistingDevice": false,
        "lastUpdate": 1667765097912,
        "mappingType": "JSON",
        "snoopedTemplates": [],
        "createNonExistingDevice": true,
        "subscriptionTopic": "measurementObject/#",
        "id": "50089267"
    },
    {
        "snoopStatus": "STOPPED",
        "templateTopicSample": "uc1",
        "ident": "a93fe7f0-cf39-4f85-afc1-85ad33b5cd19",
        "tested": false,
        "mapDeviceIdentifier": true,
        "active": true,
        "targetAPI": "MEASUREMENT",
        "source": "{\"deviceId\":\"863859042393327\",\"version\":\"1\",\"deviceType\":\"20\",\"deviceTimestamp\":\"1664964865345\",\"deviceStatus\":\"BTR\",\"temperature\":\"10\",\"batteryVoltage\":\"3012\",\"initializationStatus\":\"2\",\"batteryAlarmStatus\":\"2\",\"temperatureAlarmStatus\":\"1\",\"spillAlarmStatus\":\"1\",\"connectionInfosMcc\":\"262\",\"connectionInfosMnc\":\"01\",\"connectionInfosChannelId\":\"1F00906\",\"connectionInfosTac\":\"D2FA\",\"connectionInfosBand\":\"LTE BAND 8\",\"connectionInfosBandTec\":\"NBIoT\",\"signalStrength\":\"-101\",\"telegramNumber\":\"183\"}",
        "externalIdType": "c8y_Serial",
        "target": "{\"c8y_UseCase1\":{\"Temperature\":{\"value\":110,\"unit\":\"\u00b0C\"},\"BatteryVoltage\":{\"value\":110,\"unit\":\"mV\"},\"InitializationStatus\":{\"value\":2},\"BatteryAlarmStatus\":{\"value\":2},\"TemperaturAlarmStatus\":{\"value\":2},\"SpillAlarmStatus\":{\"value\":2},\"ConnectionInfosMcc\":{\"value\":2},\"ConnectionInfosMnc\":{\"value\":2},\"SignalStrength\":{\"value\":2,\"unit\":\"dBm\"},\"TelegramNumber\":{\"value\":2}},\"time\":\"2022-10-10T15:14:49.389+02:00\",\"source\":{\"id\":\"909090\"},\"type\":\"c8y_TemperatureMeasurement\"}",
        "templateTopic": "uc1",
        "qos": "AT_LEAST_ONCE",
        "substitutions": [
            {
                "pathSource": "deviceId",
                "pathTarget": "source.id",
                "repairStrategy": "DEFAULT",
                "expandArray": false
            },
            {
                "pathSource": "$number(batteryVoltage)",
                "pathTarget": "c8y_UseCase1.BatteryVoltage.value",
                "repairStrategy": "DEFAULT",
                "expandArray": false
            },
            {
                "pathSource": "$number(initializationStatus)",
                "pathTarget": "c8y_UseCase1.InitializationStatus.value",
                "repairStrategy": "DEFAULT",
                "expandArray": false
            },
            {
                "pathSource": "$number(batteryAlarmStatus)",
                "pathTarget": "c8y_UseCase1.BatteryAlarmStatus.value",
                "repairStrategy": "DEFAULT",
                "expandArray": false
            },
            {
                "pathSource": "$number(temperatureAlarmStatus)",
                "pathTarget": "c8y_UseCase1.TemperaturAlarmStatus.value",
                "repairStrategy": "DEFAULT",
                "expandArray": false
            },
            {
                "pathSource": "$number(spillAlarmStatus)",
                "pathTarget": "c8y_UseCase1.SpillAlarmStatus.value",
                "repairStrategy": "DEFAULT",
                "expandArray": false
            },
            {
                "pathSource": "$number(connectionInfosMcc)",
                "pathTarget": "c8y_UseCase1.ConnectionInfosMcc.value",
                "repairStrategy": "DEFAULT",
                "expandArray": false
            },
            {
                "pathSource": "$number(connectionInfosMnc)",
                "pathTarget": "c8y_UseCase1.ConnectionInfosMnc.value",
                "repairStrategy": "DEFAULT",
                "expandArray": false
            },
            {
                "pathSource": "$number(signalStrength)",
                "pathTarget": "c8y_UseCase1.SignalStrength.value",
                "repairStrategy": "DEFAULT",
                "expandArray": false
            },
            {
                "pathSource": "$number(telegramNumber)",
                "pathTarget": "c8y_UseCase1.TelegramNumber.value",
                "repairStrategy": "DEFAULT",
                "expandArray": false
            },
            {
                "pathSource": "$number(temperature)",
                "pathTarget": "c8y_UseCase1.Temperature.value",
                "repairStrategy": "DEFAULT",
                "expandArray": false
            }
        ],
        "updateExistingDevice": false,
        "lastUpdate": 1666990617152,
        "mappingType": "JSON",
        "snoopedTemplates": [
            "{\"deviceId\":\"863859042393327\",\"version\":\"1\",\"deviceType\":\"20\",\"deviceTimestamp\":\"1664964865345\",\"deviceStatus\":\"BTR\",\"temperature\":\"10\",\"batteryVoltage\":\"3012\",\"initializationStatus\":\"2\",\"batteryAlarmStatus\":\"2\",\"temperatureAlarmStatus\":\"1\",\"spillAlarmStatus\":\"1\",\"connectionInfosMcc\":\"262\",\"connectionInfosMnc\":\"01\",\"connectionInfosChannelId\":\"1F00906\",\"connectionInfosTac\":\"D2FA\",\"connectionInfosBand\":\"LTE BAND 8\",\"connectionInfosBandTec\":\"NBIoT\",\"signalStrength\":\"-101\",\"telegramNumber\":\"183\"}",
            "{\"deviceId\":\"863859042393327\",\"version\":\"1\",\"deviceType\":\"20\",\"deviceTimestamp\":\"1664964865345\",\"deviceStatus\":\"BTR\",\"temperature\":\"10\",\"batteryVoltage\":\"3012\",\"initializationStatus\":\"2\",\"batteryAlarmStatus\":\"2\",\"temperatureAlarmStatus\":\"1\",\"spillAlarmStatus\":\"1\",\"connectionInfosMcc\":\"262\",\"connectionInfosMnc\":\"01\",\"connectionInfosChannelId\":\"1F00906\",\"connectionInfosTac\":\"D2FA\",\"connectionInfosBand\":\"LTE BAND 8\",\"connectionInfosBandTec\":\"NBIoT\",\"signalStrength\":\"-101\",\"telegramNumber\":\"183\"}"
        ],
        "createNonExistingDevice": true,
        "subscriptionTopic": "uc1",
        "id": "50088634"
    },
    {
        "snoopStatus": "NONE",
        "templateTopicSample": "uc4",
        "ident": "16b768e9-f926-414b-b2a9-c531811e9023",
        "tested": false,
        "mapDeviceIdentifier": true,
        "active": true,
        "targetAPI": "MEASUREMENT",
        "source": "{\"schemaVersion\":1,\"deviceId\":\"HCN-9273019977\",\"deviceType\":\"FRIDGE:HCN\",\"deviceTimestamp\":1664298571,\"sensors\":[{\"DI-99\":0},{\"DI-2\":0},{\"DI-3\":1},{\"DI-4\":1},{\"DI-5\":0},{\"DI-6\":0},{\"DI-7\":0},{\"DI-8\":0},{\"DI-9\":0},{\"AI-1\":833},{\"AI-2\":599},{\"LS-1\":{\"lat\":51.70143,\"lng\":8.712522,\"speed\":0,\"hdop\":1,\"ts\":1664298571}}],\"rawTelegram\":\"20;FRIDGE:HCN-9273019977;22/09/27 17:09:31 +08;DI-1-DI1=0;DI-2-DI2=0;DI-3-DI3=1;DI-4-DI4=1;DI-5-DI5=0;DI-6-DI6=0;DI-7-DI7=0;DI-8-DI8=0;DI-9-DI9=0;AI-1-AI1=833,17;AI-2-AI2=599,2.8;LS-1-GPS={+51.701431,+8.712522,0,285,1.0},22/09/27 17:09:31 +08;\"}",
        "externalIdType": "c8y_Serial",
        "target": "{\"c8y_UseCase4\":{\"T\u00fcrOffen\":{\"value\":1},\"BelegungssensorVorneLinks\":{\"value\":1},\"BelegungssensorVorneMitte\":{\"value\":1},\"BelegungssensorVorneRechts\":{\"value\":1},\"BelegungssensorMitteLinks\":{\"value\":1},\"BelegungssensorMitteMitte\":{\"value\":1},\"BelegungssensorMitteRechts\":{\"value\":1},\"BelegungssensorHintenLinks\":{\"value\":1},\"BelegungssensorHintenMitte\":{\"value\":1},\"Temperatur\":{\"value\":5},\"PositionGetr\u00e4nkeschieber\":{\"value\":5}},\"time\":\"2022-08-05T00:14:49.389+02:00\",\"source\":{\"id\":\"909090\"},\"type\":\"c8y_UseCase4\"}",
        "templateTopic": "uc4",
        "qos": "AT_LEAST_ONCE",
        "substitutions": [
            {
                "pathSource": "sensors[0].'DI-1'",
                "pathTarget": "c8y_UseCase4.T\u00fcrOffen.value",
                "repairStrategy": "DEFAULT",
                "expandArray": false
            },
            {
                "pathSource": "sensors[9].'AI-1'",
                "pathTarget": "c8y_UseCase4.Temperatur.value",
                "repairStrategy": "DEFAULT",
                "expandArray": false
            },
            {
                "pathSource": "sensors[10].'AI-2'",
                "pathTarget": "c8y_UseCase4.PositionGetr\u00e4nkeschieber.value",
                "repairStrategy": "DEFAULT",
                "expandArray": false
            },
            {
                "pathSource": "deviceId",
                "pathTarget": "source.id",
                "repairStrategy": "DEFAULT",
                "expandArray": false
            },
            {
                "pathSource": "$exists(sensors[1].'DI-2')? {\"value\": sensors[1].'DI-2'}: null",
                "pathTarget": "c8y_UseCase4.BelegungssensorVorneLinks",
                "repairStrategy": "REMOVE_IF_MISSING",
                "expandArray": false
            },
            {
                "pathSource": "$exists(sensors[2].'DI-3')? {\"value\": sensors[2].'DI-3'}: null",
                "pathTarget": "c8y_UseCase4.BelegungssensorVorneMitte",
                "repairStrategy": "REMOVE_IF_MISSING",
                "expandArray": false
            },
            {
                "pathSource": "$exists(sensors[3].'DI-4')? {\"value\": sensors[3].'DI-4'}: null",
                "pathTarget": "c8y_UseCase4.BelegungssensorVorneRechts",
                "repairStrategy": "REMOVE_IF_MISSING",
                "expandArray": false
            },
            {
                "pathSource": "$exists(sensors[4].'DI-5')? {\"value\": sensors[4].'DI-5'}: null",
                "pathTarget": "c8y_UseCase4.BelegungssensorMitteLinks",
                "repairStrategy": "REMOVE_IF_MISSING",
                "expandArray": false
            },
            {
                "pathSource": "$exists(sensors[5].'DI-6')? {\"value\": sensors[5].'DI-6'}: null",
                "pathTarget": "c8y_UseCase4.BelegungssensorMitteMitte",
                "repairStrategy": "REMOVE_IF_MISSING",
                "expandArray": false
            },
            {
                "pathSource": "$exists(sensors[6].'DI-7')? {\"value\": sensors[6].'DI-7'}: null",
                "pathTarget": "c8y_UseCase4.BelegungssensorMitteRechts",
                "repairStrategy": "REMOVE_IF_MISSING",
                "expandArray": false
            },
            {
                "pathSource": "$exists(sensors[7].'DI-8')? {\"value\": sensors[7].'DI-8'}: null",
                "pathTarget": "c8y_UseCase4.BelegungssensorHintenLinks",
                "repairStrategy": "REMOVE_IF_MISSING",
                "expandArray": false
            },
            {
                "pathSource": "$exists(sensors[8].'DI-9')? {\"value\": sensors[8].'DI-9'}: null",
                "pathTarget": "c8y_UseCase4.BelegungssensorHintenMitte",
                "repairStrategy": "REMOVE_IF_MISSING",
                "expandArray": false
            }
        ],
        "updateExistingDevice": false,
        "lastUpdate": 1667026960183,
        "mappingType": "JSON",
        "snoopedTemplates": [],
        "createNonExistingDevice": true,
        "subscriptionTopic": "uc4",
        "id": "50088635"
    },
    {
        "snoopStatus": "NONE",
        "templateTopicSample": "uc1",
        "ident": "0345a02b-f7e5-4548-92a4-8390ca95e713",
        "tested": false,
        "mapDeviceIdentifier": true,
        "active": true,
        "targetAPI": "EVENT",
        "source": "{\"deviceId\":\"863859042393327\",\"version\":\"1\",\"deviceType\":\"20\",\"deviceTimestamp\":\"1664964865345\",\"deviceStatus\":\"BTR\",\"temperature\":\"20\",\"batteryVoltage\":\"3012\",\"initializationStatus\":\"2\",\"batteryAlarmStatus\":\"2\",\"temperatureAlarmStatus\":\"1\",\"spillAlarmStatus\":\"1\",\"connectionInfosMcc\":\"262\",\"connectionInfosMnc\":\"01\",\"connectionInfosChannelId\":\"1F00906\",\"connectionInfosTac\":\"D2FA\",\"connectionInfosBand\":\"LTE BAND 8\",\"connectionInfosBandTec\":\"NBIoT\",\"signalStrength\":\"-101\",\"telegramNumber\":\"183\"}",
        "externalIdType": "c8y_Serial",
        "target": "{\"source\":{\"id\":\"909090\"},\"text\":\"Neuer Status vom Ger\u00e4t.\",\"time\":\"2022-08-05T00:14:49.389+02:00\",\"type\":\"zustand\",\"status\":\"\"}",
        "templateTopic": "uc1",
        "qos": "AT_LEAST_ONCE",
        "substitutions": [
            {
                "pathSource": "deviceId",
                "pathTarget": "source.id",
                "repairStrategy": "DEFAULT",
                "expandArray": false
            },
            {
                "pathSource": "$fromMillis($number(deviceTimestamp))",
                "pathTarget": "time",
                "repairStrategy": "DEFAULT",
                "expandArray": false
            },
            {
                "pathSource": "deviceStatus",
                "pathTarget": "status",
                "repairStrategy": "DEFAULT",
                "expandArray": false
            },
            {
                "pathSource": "$join([\"Neuer Status vom Ger\u00e4t: \",deviceStatus])",
                "pathTarget": "text",
                "repairStrategy": "DEFAULT",
                "expandArray": false
            }
        ],
        "updateExistingDevice": false,
        "lastUpdate": 1665578792958,
        "mappingType": "JSON",
        "snoopedTemplates": [],
        "createNonExistingDevice": true,
        "subscriptionTopic": "uc1",
        "id": "50090066"
    },
    {
        "snoopStatus": "STARTED",
        "templateTopicSample": "binary/berlin_01",
        "ident": "5c5d2ea9-b0ce-4237-ad23-be5b393ca14c",
        "tested": false,
        "mapDeviceIdentifier": true,
        "active": true,
        "targetAPI": "EVENT",
        "source": "{}",
        "externalIdType": "c8y_Serial",
        "target": "{\"c8y_TemperatureMeasurement\":{\"T\":{\"value\":110,\"unit\":\"C\"}},\"time\":\"2022-08-05T00:14:49.389+02:00\",\"source\":{\"id\":\"909090\"},\"type\":\"c8y_TemperatureMeasurement\"}",
        "templateTopic": "binary/+",
        "qos": "AT_LEAST_ONCE",
        "substitutions": [],
        "updateExistingDevice": false,
        "lastUpdate": 1667244378733,
        "mappingType": "GENERIC_BINARY",
        "snoopedTemplates": [
            "{\"message\":\"5a75207370c3a47420303821\"}",
            "{\"message\":\"5a75207370c3a47420303921\"}",
            "{\"message\":\"5a75207370c3a47420303921\"}",
            "{\"message\":\"5a75207370c3a47420313921\"}"
        ],
        "createNonExistingDevice": false,
        "subscriptionTopic": "binary/+",
        "id": "50087738"
    },
    {
        "snoopStatus": "NONE",
        "templateTopicSample": "operation/berlin_01",
        "ident": "ef19e1db-fd9a-481c-aa83-ee000eb2ed5e",
        "tested": false,
        "mapDeviceIdentifier": true,
        "active": true,
        "targetAPI": "OPERATION",
        "source": "{\"text\":\"Special operation\"}",
        "target": "{\"deviceId\":\"909090\",\"description\":\"New camera operation!\",\"type\":\"maintenance_operation\"}",
        "externalIdType": "c8y_Serial",
        "templateTopic": "operation/+",
        "qos": "AT_LEAST_ONCE",
        "substitutions": [
            {
                "pathSource": "_TOPIC_LEVEL_[1]",
                "pathTarget": "deviceId",
                "repairStrategy": "DEFAULT"
            },
            {
                "pathSource": "$join([text,\"_\",$now()])",
                "pathTarget": "description",
                "repairStrategy": "DEFAULT"
            }
        ],
        "updateExistingDevice": false,
        "mappingType": "JSON",
        "lastUpdate": 1667293150214,
        "snoopedTemplates": [],
        "createNonExistingDevice": true,
        "id": "50089275",
        "subscriptionTopic": "operation/+"
    },
    {
        "snoopStatus": "STOPPED",
        "templateTopicSample": "binaryEvent/berlin_01",
        "ident": "df794b94-7921-42de-b612-aa7951b1410b",
        "tested": true,
        "mapDeviceIdentifier": true,
        "active": true,
        "targetAPI": "EVENT",
        "source": "{\"message\":\"5a75207370c3a47420303821\"}",
        "externalIdType": "c8y_Serial",
        "target": "{\"source\":{\"id\":\"909090\"},\"text\":\"This is a new test event.\",\"time\":\"2022-08-05T00:14:49.389+02:00\",\"type\":\"c8y_TestEvent\"}",
        "templateTopic": "binaryEvent/+",
        "qos": "AT_LEAST_ONCE",
        "substitutions": [
            {
                "pathSource": "\"Temp: \"&$parseInteger($string(\"0x\"&$substring(message,0,2)),\"0\")&\" C\"",
                "pathTarget": "text",
                "repairStrategy": "DEFAULT"
            },
            {
                "pathSource": "_TOPIC_LEVEL_[1]",
                "pathTarget": "source.id",
                "repairStrategy": "DEFAULT"
            },
            {
                "pathSource": "$now()",
                "pathTarget": "time",
                "repairStrategy": "DEFAULT"
            }
        ],
        "updateExistingDevice": false,
        "lastUpdate": 1667307859886,
        "mappingType": "GENERIC_BINARY",
        "snoopedTemplates": [
            "{\"message\":\"5a75207370c3a47420303821\"}",
            "{\"message\":\"5a75207370c3a47420303921\"}",
            "{\"message\":\"5a75207370c3a47420303921\"}",
            "{\"message\":\"5a75207370c3a47420313921\"}"
        ],
        "createNonExistingDevice": false,
        "subscriptionTopic": "binaryEvent/+",
        "id": "50089277"
    },
    {
        "snoopStatus": "NONE",
        "templateTopicSample": "simple/painter_pro_01",
        "ident": "5bc0dabd-b223-4bce-97f7-876774a88a04",
        "tested": false,
        "mapDeviceIdentifier": true,
        "active": true,
        "targetAPI": "MEASUREMENT",
        "source": "{\"paint\":1010,\"temp\":50}",
        "target": "{\"c8y_TemperatureMeasurement\":{\"T\":{\"value\":110,\"unit\":\"C\"}},\"time\":\"2022-08-05T00:14:49.389+02:00\",\"source\":{\"id\":\"909090\"},\"type\":\"c8y_TemperatureMeasurement\"}",
        "externalIdType": "c8y_Serial",
        "templateTopic": "simple/+",
        "qos": "AT_LEAST_ONCE",
        "substitutions": [
            {
                "pathSource": "temp",
                "pathTarget": "c8y_TemperatureMeasurement.T.value",
                "repairStrategy": "DEFAULT",
                "expandArray": false
            },
            {
                "pathSource": "_TOPIC_LEVEL_[1]",
                "pathTarget": "source.id",
                "repairStrategy": "DEFAULT",
                "expandArray": false
            }
        ],
        "updateExistingDevice": false,
        "mappingType": "JSON",
        "lastUpdate": 1667818256640,
        "snoopedTemplates": [],
        "createNonExistingDevice": false,
        "id": "50090074",
        "subscriptionTopic": "simple/#"
    },
    {
        "snoopStatus": "NONE",
        "templateTopicSample": "device/update/berlin_01",
        "ident": "4e62d9df-7eb0-4ee5-92f6-8a7a93cbd99b",
        "tested": false,
        "mapDeviceIdentifier": true,
        "active": true,
        "targetAPI": "INVENTORY",
        "source": "{\"customType\":\"type_Overnight\"}",
        "target": "{\"type\":\"type\"}",
        "externalIdType": "c8y_Serial",
        "templateTopic": "device/update/+",
        "qos": "AT_LEAST_ONCE",
        "substitutions": [
            {
                "pathSource": "_TOPIC_LEVEL_[2]",
                "pathTarget": "_DEVICE_IDENT_",
                "repairStrategy": "DEFAULT",
                "expandArray": false
            },
            {
                "pathSource": "customType",
                "pathTarget": "type",
                "repairStrategy": "DEFAULT",
                "expandArray": false
            }
        ],
        "updateExistingDevice": true,
        "mappingType": "JSON",
        "lastUpdate": 1667839346115,
        "snoopedTemplates": [],
        "createNonExistingDevice": false,
        "id": "50088645",
        "subscriptionTopic": "device/update/+"
    },
    {
        "snoopStatus": "NONE",
        "templateTopicSample": "protobuf/measurement",
        "ident": "7b97922b-7229-4620-bc7c-19cf55f1b5f3",
        "tested": false,
        "mapDeviceIdentifier": true,
        "active": true,
        "targetAPI": "MEASUREMENT",
        "source": "{}",
        "target": "{\"c8y_GenericMeasurement\":{\"Module\":{\"value\":110,\"unit\":\"l\"}},\"time\":\"2022-08-05T00:14:49.389+02:00\",\"source\":{\"id\":\"909090\"},\"type\":\"c8y_GenericMeasurement_type\"}",
        "externalIdType": "c8y_Serial",
        "templateTopic": "protobuf/measurement",
        "qos": "AT_LEAST_ONCE",
        "substitutions": [],
        "updateExistingDevice": false,
        "mappingType": "PROTOBUF_STATIC",
        "lastUpdate": 1668288598021,
        "snoopedTemplates": [],
        "createNonExistingDevice": false,
        "id": "50089283",
        "subscriptionTopic": "protobuf/measurement"
    },
    {
        "snoopStatus": "NONE",
        "extension": {
            "name": "mqtt-mapping-extension",
            "event": "CustomEvent"
        },
        "templateTopicSample": "protobuf/event",
        "ident": "93c93bb2-2ea3-4e7b-9f36-3b4a028ccc7b",
        "tested": false,
        "mapDeviceIdentifier": true,
        "active": true,
        "targetAPI": "EVENT",
        "source": "{}",
        "target": "{\"source\":{\"id\":\"909090\"},\"text\":\"This is a new test event.\",\"time\":\"2022-08-05T00:14:49.389+02:00\",\"type\":\"c8y_TestEvent\"}",
        "externalIdType": "c8y_Serial",
        "templateTopic": "protobuf/event",
        "qos": "AT_LEAST_ONCE",
        "substitutions": [],
        "updateExistingDevice": false,
        "mappingType": "PROCESSOR_EXTENSION",
        "lastUpdate": 1668983252450,
        "snoopedTemplates": [],
        "createNonExistingDevice": false,
        "id": "50089285",
        "subscriptionTopic": "protobuf/event"
    }
]