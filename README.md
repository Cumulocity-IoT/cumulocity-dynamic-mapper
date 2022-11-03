# Dynamic MQTT Mapping Service for Cumulocity

# Content
- [Overview](#overview)
  * [Architecture](#architecture)
  * [Known Limitation and Disclaimer](#known-limitation-and-disclaimer)
  * [Prerequisites](#prerequisites)
- [Installation](#installation)
  * [Microservice](#microservice)
  * [Web App Plugin](#web-app-plugin)
  * [Build, Deploy, Run](#build-deploy-run)
- [Configuration MQTT connection to broker](#configuration-mqtt-connection-to-broker)
- [Definition and Deployment of MQTT mappings](#definition-and-deployment-of-mqtt-mappings)
  * [Table of MQTT mappings](#table-of-mqtt-mappings)
  * [Define mappings from source to target format (Cumulocity REST format)](#define-mappings-from-source-to-target-format-cumulocity-rest-format)
    + [Wizzard to define a mapping](#wizzard-to-define-a-mapping)
    + [Snooping payloads on source topic](#snooping-payloads-on-source-topic)
    + [Enable snooping payloads on source topic](#enable-snooping-payloads-on-source-topic)
    + [Define templates and substitutions for source and target payload](#define-templates-and-substitutions-for-source-and-target-payload)
    + [Different type of substitutions](#different-type-of-substitutions)
  * [Test transformation from source to target format](#test-transformation-from-source-to-target-format)
  * [Send transformed test message to test device in Cumulocity](#send-transformed-test-message-to-test-device-in-cumulocity)
  * [Use snooped payloads in source templates](#use-snooped-payloads-in-source-templates)
- [Monitoring](#monitoring)
- [REST API](#rest-api)
- [Load Test](#load-test)
- [Setup Sample MQTT mappings](#setup-sample-mqtt-mappings)



## Overview

Cumulocity IoT has a MQTT endpoint, but does not yet allow devices to send generic MQTT payloads. This project addresses
this gap by providing the following artifcats:

* A **Microservice** - exposes REST endpoints, uses the [PAHO MQTT Client](https://github.com/eclipse/paho.mqtt.java) to
connect to a MQTT broker, a generic Data Mapper & Expression Language  for data mapping and the
[Cumulocity Microservice SDK](https://cumulocity.com/guides/microservice-sdk/introduction/) to connect to Cumulocity.
* A **Frontend Plugin** - uses the exposed endpoints of the microservice to configure a MQTT broker connection & to perform 
graphical MQTT Data Mappings within the Cumumlocity IoT UI.

Using the solution you are able to connect to any MQTT broker and map any JSON-based payload on any topic dynamically to
the Cumulocity IoT Domain Model in a graphical way.

### Architecture
![Architecture](resources/image/Generic_MQTT_Architecture.png)
The grey components are part of this project which are:

* **MQTT Client** - using [PAHO MQTT Client](https://github.com/eclipse/paho.mqtt.java) to connect and subscribe to a MQTT broker
* **Data Mapper** - handling of received messages via MQTT and mapping them to a target data format for Cumulocity IoT. 
Also includes an expression runtime [JSONata](https://jsonata.org) to execute expressions
* **C8Y Client** - implements part of the Cumulocity IoT REST API to integrate data
* **REST Endpoints** - custom endpoints which are used by the MQTT Frontend or can be used to add mappings programmatically
* **MQTT Frontend** - A plugin for Cumulocity IoT to provide an UI for MQTT Configuration & Data Mapping

> **Please Note:** A required MQTT Broker is not part of this repo and must be provided to make use of this component.
> In upcoming releases a *Generic MQTT Broker* will be part of Cumulocity IoT. If necessary we will adapt this component
> to work with it seamlessly!

The following diagram describes what happens in the microservice if a new MQTT mapping is added.
![Mapping](resources/image/Generic_MQTT_Diagram_Map.png)

The following diagram describes what happens in the microservice if a new messages arrives and how the payload is transformed.
![Transform](resources/image/Generic_MQTT_Diagram_Transform1.png)
![Transform](resources/image/Generic_MQTT_Diagram_Transform2.png)

### Known Limitation and Disclaimer

Currently this project is focussing on JSON Payload only. Any other payload sent via MQTT must be mapped programmatically.
See chapter [Enhance](#enhance) for more details.

As we already have a very good C8Y API coverage for mapping not all complex cases might be supported. Currently the 
following Mappings are supported:

* Inventory
* Events
* Measurements
* Alarms
* Operations (Downstream)

Beside that complex JSON objects & arrays are supported but not fully tested.

Due to two different libraries to evaluate JSONata in:
1. frontend (nodejs): [npmjs JSONata](https://www.npmjs.com/package/jsonata) and
1. backend (java): [JSONata4Java](https://github.com/IBM/JSONata4Java)

differences in more advanced expressions can occur. Please test your expressions before you use advanced elements.

The Paho java client uses memory persistence to persit its state (used to store outbound and inbound messages while they are in flight). When the microservice restarts this information is lost. Micorservice can not use the default ```MqttDefaultFilePersistence```.

> **Pull Requests adding mappings for other data formats or additional functionaly are welcomed!**

## Prerequisites
In your Cumulocity IoT Tenant you must have the **microservice** feature subscribed. Per default this feature is not
avilable and must be provided by administrators of the instance you are using.

Make sure to use an user with admin privileges in your Tenant.

## Installation

You need to install two components to your Cumulocity IoT Tenant:

* Microservice
* WebApp Plugin

Both are provided as binaries in [Releases](https://github.com/SoftwareAG/cumulocity-generic-mqtt-agent/releases). Take 
the binaries from the latest release and upload them to your Cumulocity IoT Tenant.

### Microservice

In Administration App go to Ecosystem -> Microservices and click on "Add Microservice" on the top right.

![Upload Microservice](resources/image/Generic_MQTT_UploadMicroservice.png).

Select the "mqtt.mapping.service.zip".
Make sure that you subscribe the microservice to your tenant when prompted

### Web App Plugin

In Adminstration App go to Ecosystem -> Packages and click on "Add Application" on the top right.

> **_NOTE:_** If you don't see the Packages Menu you have to add "?beta=true" in your URL.
> Example: {{url}}/apps/administration?beta=true

Select "mqtt-mapping.zip" and wait until it is uploaded.

> **_NOTE:_** We need to clone the Administration app to add the plugin to it

After succesful upload go to "All Applications" and click on "Add Application". Select "Duplicate existing application"
and afterwards "Administration".

![Duplicate App](resources/image/Generic_MQTT_DuplicateApp.png).

Now select the cloned Administration App and go to the "Plugin" Tab. Click on "Install Plugin" and select "MQTT configuration plugin"

![Plugin Installed](resources/image/Generic_MQTT_PluginInstalled.png)

## Build, Deploy, Run
Make sure that [Docker](https://www.docker.com/) and [Apache Maven](https://maven.apache.org/) are installed and running on your Computer.

### Backend - Microservice
Run `mvn clean package` in folder `backend` to build the Microservice which will create a ZIP archive you can upload to Cumulocity.
Just deploy the ZIP to the Cumulocity Tenant like described [here](https://cumulocity.com/guides/users-guide/administration/#uploading-microservices).

### Frondend - Plugin
Run `npm run build` in folder `frontend/mqtt-mapping` to build the Front End (plugin) for the Administration which will build a plugin.
Run `npm run deploy` in folder `frontend/mqtt-mapping` to deploy the Front End (plugin) to your Cumulocity istration which will build a plugin.
The Frontend is build as Plugin [here](https://cumulocity.com/guides/web/tutorials/#add-a-custom-widget-with-plugin).

## Configuration MQTT connection to broker
The MQTT broker configuration is persisted in the tenant options of a Cumulocity IoT Tenant and can be configured by the following UI.\
Furthermore, connections to the MQTT broker can be enabled or disabled.

<!-- <br/>
<p align="center" style="text-indent:70px;">
  <a>
    <img width="100%" src="http://g.recordit.co/dm3Qah19Ar.gif">
  </a>
</p>
<br/> -->
![MQTT connection](resources/image/Generic_MQTT_Connection.png)

## Definition and Deployment of MQTT mappings

### Table of MQTT mappings

Once the connection to a MQTT broker is configured and successfully enabled you can start defining MQTT mappings. The MQTT mappings table is the entry point for:
1. Creating new MQTT mappings: Press button ```Add mapping```
1. Updating exsiting MQTT mapping: Press the pencil in the row of the relevant mapping
1. Deleting exsiting MQTT mapping: Press the "-" icon in the row of the relevant mapping to delete an existing mappings

After every change the mappings are automatically updated in the microservice.

![Table of MQTT mappings](resources/image/Generic_MQTT_MappingTable.png)

### Define mappings from source to target format (Cumulocity REST format)

Mappings are persisted as Managed Objects and can be easily changed, deleted or migrated.

#### Expression Language

In addition to using plain properties of the source payload, you can apply functions on the payload properties. This covers a scenario where a device name should be a combination of a generic name and an external device Id.
Complex mapping expressions are supported by using [JSONata](https://jsonata.org). \
In this case the following function could be used:
```$join([device_name, _DEVICE_IDENT_])```. 

Further example for JSONata expressions are:
* to convert a UNIX timestamp to ISO date format use:
      <code>$fromMillis($number(deviceTimestamp))</code>
* to join substring starting at position 5 of property <code>txt</code> with device
      identifier use: <code>$join([$substring(txt,5), "-", _DEVICE_IDENT_])</code>

>**_NOTE:_**
> * escape properties with special characters with <code>`</code>. The property
        <code>customer-1</code> becomes <code>`customer-1`</code>
> * function chaining using <code>~></code> is not supported, instead use function
        notation. The expression <code>Account.Product.(Price * Quantity) ~> $sum()</code>
        becomes <code>$sum(Account.Product.(Price * Quantity))</code>

### Wizzard to define a mapping

The wizzard to define a mapping consists of the steps:

1. Select the type of mapping:
  *  JSON
  *  FLAT_FILE
  *  GENERIC_BINARY

![Mappingtype](resources/image/Generic_MQTT_MappingType.png)

Payload for ```FLAT_FILE``` and ```GENERIC_BINARY``` are wrapped.
For flat file messages:

```
{
  "message": "oil,100,1666863595",
}
```
You can use the JSONata function ```$split(str, separator)``` for splitting the payload, e.g:
```
$split(message,",")[1]
```
splits the payload and return the second field: ```100```.

And for the binary payload is encoded as hex string:
```
{
  "message": "5a75207370c3a47420303821",
}
```
Using appropriate JSONata expression you can parse the payload:
```
$parseInteger($string("0x"&$substring(message,0,2)),"0")&" C"
```


2. Define the properties of the topic and API to be used
3. Define the templates for the source and target, in JSON format. The soure payload can be in any custom JSON format. the target format has to follow the schemsa for Alarm, Events, Measurements or Inventory, [see Cumulocity OpenAPI](https://cumulocity.com/api/).
4. Test the mapping by applying the transformation and send the result to a test device.

#### Define MQTT topic properties

In the first wizzard step properties for the topic are defined.

![Define Properties](resources/image/Generic_MQTT_TopicDefinition.png)

For the mappings we differentiate between a **subscription topic** and a **template topic**:

#### Subscription Topic

This is the topic which is actually subscribed on in the MQTT broker. It can contain wildcards, either single level "+" or multilevel "#".

>**_NOTE:_** Multi-level wildcards can only appear at the end of topic. The topic "/device/#/west" is not valid.
Examples of valid topics are: "device/#", "device/data/#", "device/12345/data" etc.

#### Template Topic

The template topic is the key of the persisted mapping. The main difference to the subscription topic is that
a template topic can have a path behind the wildcard for the reason as we can receive multiple topics on a wildcard which might be mapped differently.\
Examples are: "device/+/data, "device/express/+", "device/+"\
In order to use sample data instead of the wildcard you can add a Template Topic Sample, which must have the same structure, i.e. same level in the topic and when explicit name are used at a topic level in the Template Topic they must exactly be the same in the Template Topic Sample.
The levels of the Template Topic are split and added to the payload:
```
  "_TOPIC_LEVEL_": [
    "device",
    "express",
    "berlin_01"
  ]
```
The entries in the ```_TOPIC_LEVEL_``` can be used to resolve the external device identifier to the internal Cumulocity Id.
The additinal property ```_TOPIC_LEVEL_``` is added to the source template shown in the next wizzard step. It must not be deleted when editing the JSON source template.

#### Snooping payloads on source topic

Very often you want to use the payloads of existing JSON messages as a sample to define the source template. This can be achieved by listening and recording - snooping- to messages on a topic.

In order to record JSON payloads on the defined topic a subscrition records the payloads and saves them for later use in a source template.

The snooping process goes through the steps **ENABLED** -> **STARTED** -> **STOPPED**.

If a payload is found the status moves to **STARTED**. This is indicated in the last column of the mappping table, where the number of payloads snooped so far is shown.

#### Enable snooping payloads on source topic

To enable snooping select ```ENABLED``` in the drop down as shown in the screenshot below. This starts the snooping process and the microservice subscribes to the related topic and records the received payloads.

![Enable Snooping](resources/image/Generic_MQTT_EnableSnooping.png)

#### Map Device Idenfifier

Connected devices send their data using an external device identifier, e.g. IMEI, serial number, ... In this case the external id has to be mapped to the device id used by Cumulocity. To achieve this the entries in the ```_TOPIC_LEVEL_``` can be used to resolve the external device identifier to the internal Cumulocity Ids. When a payload from this device arrives at runtime the external id is translated to the internal Cumulocity id.


#### Define templates and substitutions for source and target payload

In the second wizzard step, shown on the screenshot below the mapping is furher defined:
1. Editing the source template directly or use a snooped template by pressing button ```Snooped templates```
2. Editing the target template directly or use a sample template by pressing button ```Sample target template```
3. Adding substitutions

![Define Templates](resources/image/Generic_MQTT_MappingTemplate.png)

In order to define a substitution ( substitute values in the target payload with values extracted at runtime from the source payload), the UI offers the following features:
1. Add mapping (button with "+" sign)
1. Show & Select already defined substitutions (button with skip symbol). A selected substitution is colored and can be deleted by pressing the button with "-" sign
1. Delete mapping (button wiht one "-" sign), the selected substitution is deleted
1. Delete all mappings (button wiht two "--" signs). In this case the substitution to define the deviceIdentifier is automatically added again. This is the case when a template topic contains a wildcard, eithe "+"- singel level or "#" - multi level

![Define Templates](resources/image/Generic_MQTT_MappingTemplate_annnotated.png)

To define a new substitution the following steps have to be performed:
1. Select a property in the source JSON payload by click on the respective property. Then the JSONpath is appears in the field with the label ```Evaluate expression on source```
1. Select a property in the target JSON payload by click on the respective property. Then the JSONpath is appears in the field with the label ```Substitute in target```
1. Select  ```Expand Array``` if the result of the source expression is an array and you want to generate any of the following substitutions:
  * **multi-device-single-value**
  * **multi-device-multi-value**
  * **single-device-multi-value**\
  Otherwise an extracted array is treated as a single valie, see [Different type of substitutions](#different-type-of-substitutions).
4. Press the add button with the ```+``` sign, to add the substitution to the list.

>**_NOTE:_** When adding a new substitution the following two consistency rules are checked:
>1. Does another substitution for the same target property exist? If so, a modal dialog appears and asks the user for confirmation to overwrite the existing substitution.
>1. If the new substitution defines the device identifier, it is checked if another substitution already withe the same proprty exists. If so, a modal dialog appears and asks for confirmation to overwrite the existing substitution.


To avoid inconsistent JSON being send to the Cumulocity APIS schemas are defined for For all target payloads (Measurement, Event, Alarm, Inventory). The schemas validate if requred properties are defined and if the time is in the correct format.

In the sample below, e.g. a warning is shown since the required property ```c8y_IsDevice``` is  missing in the payload.

![Enable Snooping](resources/image/Generic_MQTT_SchemaValidation.png)

#### Different type of substitutions
When you define an expression or a path in the source payload for a substitution the result can be one of the following cases:
1. **if** the result is a scalar value, e.g. ```10.4``` for a single value **and**
     * **if** only one device is identified in the payload \
      **then** only one Cumulocity MEA-resquest is generated from this payload.\
     This is a **single-device-single-value** mapping.
     * **if** multiple devices are identified, e.g. ```["device_101023", "device_101024"]``` in the payload \
      **then** multiple Cumulocity MEA-requests or inventory requests - depending on the used targetAPI in the mapping - are generated from this payload. This only makes sense for creating multiple devices.\
      This is a **multi-device-single-value** mapping.
2. **if** the result is an array, e.g. ```[10.4, 20.9]``` for multiple measurements values **and**
    * **if** multiple devices are identified , e.g. ```["device_101023","device_101024"]``` \
      **then**  multiple Cumulocity MEA-requests are generated from this single payload. In this case two requests: 
      1. request: for device ```"device_101023"``` and value ```10.4```
      2. request: for device ```"device_101024"``` and value ```20.9``` 

      This is a **multi-device-multi-value** mapping.

    * **if** a single devices is identified , e.g. ```"device_101023"``` \
     **then**  multiple Cumulocity MEA-requests are generated from this single payload. In this case two requests: 
      1. request: for device ```"device_101023"``` and value ```10.4```
      2. request: for device ```"device_101023"``` and value ```20.9```

      This is a **single-device-multi-value** mapping.

3. the result is an object: this is not supported.

This is illustrated on the following diagram:
![Different type of substitutions](resources/image/Generic_MQTT_DifferentSubstitutions.png)

___
  **NOTE:** If the size of all extracted arrays do not match, then the first values in the array with less items is taken to fill the missing values.\
  To illustrate this behavior, take the following case where:
  * the first expression returns 2 values ```[10.4, 20.9]```
  * the second expression returns 3 dates ```["2022-10-30T04:10:00.000Z", "2022-10-30T04:11:00.000Z", "2022-10-30T04:12:00.000Z"]```
  * the third expression returns 3 ids ```["device_101023","device_101024","device_101025"]```

  then three requests are generated:
  1. request: for device ```"device_101023"```, timestamp ```2022-10-30T04:10:00.000Z``` and value ```10.4```
  1. request: for device ```"device_101024"```, timestamp ```2022-10-30T04:11:00.000Z``` and value ```20.9```
  1. request: for device ```"device_101025"```, timestamp ```2022-10-30T04:12:00.000Z``` and value ```10.4```
___

### Test transformation from source to target format

To test the defined transformation, press the button ```Transform test message```. The result of the transformation and any error are displayed.

![Test Transformation](resources/image/Generic_MQTT_TestTransformation.png)

### Send transformed test message to test device in Cumulocity

To send the a transformed payload to a test device, press the button ```Send test message```. If an error occurs this is shown in the UI.

![Send Test Message](resources/image/Generic_MQTT_SendTestMessageToCumulocity.png)

### Use snooped payloads in source templates

In order to use a previously snooped payload click the button
```Snooped templates```. Multiples activation of this button iterates over all the recorded templates.

![Enable Snooping](resources/image/Generic_MQTT_UseSnoopedPayload.png)

### Monitoring

On the monitoring tab ```Monitoring``` you can see how a specific MQTT mapping performs since the last activation in the microservice.

![Monitoring](resources/image/Generic_MQTT_Monitoring.png)


### REST API

The mapping microservice provides endpoints to control the lifecycle and manage mappings. in details these endpoint are:
1. ```.../configuration/connection```: retrieve and change the connection details to the MQTT broker
1. ```.../configuration/serice```: retrieve and change the configuration details, e.g. loglevel of the mapping service
1. ```.../operation```: execute operation: reload mappings, connect to broker, diconnect from broker, reset the monitoring statistic
1. ```.../status/service```: retrieve service status: is microservice connected to broker, are connection details loaded
1. ```.../status/mapping```: retrieve mapping status: number of messages, errors processed per mapping
1. ```.../mapping```: retrieve, delete, update mappings
1. ```.../tree```: all mappings are organised in a tree for efficient processing and resolving the mappings at runtime. This tree can be retrieved for debugging purposes.
1. ```.../test/{method}?topic=URL_ENCODED_TOPIC```: this endpoint allows testing of a payload. The send parameter (boolen)  indicates if the transfromed payload should be send to Cumulocity after processing. The call return a list of ```ProcessingConext``` to record which mapping processed the payload and the otcome of the mapping process as well as error

### Load Test
In the resource section you find a test profil [jmeter_test_01.jmx](resources/script/jmeter_test_01.jmx) using jmeter and an extention for mqtt: [emqx/mqtt-jmete](https://github.com/emqx/mqtt-jmeter).
This was used to run simple loadtest.

## Setup Sample MQTT mappings

A script to create sample MQTT mappings can be found [here](resources/script/createSampleMappings.sh).

## Enhance
In the folder [Callbacks](./backend/src/main/java/mqttagent/callbacks) you can either overwrite the existing `JSONCallback.class` or add a new Handler in the handler folder.
As an example see the [SysHandler](./backend/src/main/java/mqttagent/callbacks/handler/SysHandler.java) which subscribes and handles all topics for $SYS and creates Measurements in Cumulocity for the received data.

______________________
These tools are provided as-is and without warranty or support. They do not constitute part of the Software AG product suite. Users are free to use, fork and modify them, subject to the license agreement. While Software AG welcomes contributions, we cannot guarantee to include every contribution in the master project.

Contact us at [TECHcommunity](mailto:technologycommunity@softwareag.com?subject=Github/SoftwareAG) if you have any questions.


