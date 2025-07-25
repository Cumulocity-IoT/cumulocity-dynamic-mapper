# Cumulocity Dynamic Mapper


Cumulocity has a MQTT endpoint, but does not yet allow devices to send generic MQTT payloads. This project addresses
this gap by providing the following artifacts:

* A **Microservice** - contains connectors for generic MQTT brokers, the Cumulocity MQTT Service and Kafka brokers. The microservice listens to incoming messages and applies the appropriate mappings. Exposes REST endpoints for the UI to manage connector configurations and mappings.
* A **Frontend Plugin** - uses the exposed endpoints of the microservice to configure a MQTT broker connection & to perform 
graphical MQTT Data Mappings within the Cumulocity IoT UI. Mappings are defined using [JSONata](https://jsonata.org/) expressions.
In addition, substitutions can be defined in JavaScript.  

Using the solution you are able to connect to any MQTT broker and map any JSON-based payload on any topic dynamically to
the Cumulocity IoT Domain Model in a graphical way.

The mapper processes messages in both directions:
1. `INBOUND`: from external source to C8Y
2. `OUTBOUND`: from C8Y to external source

Different mappings types can be used:
<br>
<br>
![Add mapping](image/Dynamic_Mapper_Mapping_Table_Add_Modal.png)
<br>
<br>
Mappings are defined in a graphical editor using JSONata expressions:
<br>
<br>
![Define mappings](image/Dynamic_Mapper_Mapping_Stepper_Substitution_Basic.png)
<br>
<br>
For the complete documentation please check the GitHub project [cumulocity-dynamic-mqtt-mapper](https://github.com/Cumulocity-IoT/cumulocity-dynamic-mqtt-mapper).

**NOTE:** 
* This solution requires an additional microservice. The microservice `dynamic-mapper-service.zip` can be found in the [release section](https://github.com/Cumulocity-IoT/cumulocity-dynamic-mqtt-mapper/releases) of the github project. Instruction how to the deploy the microservice can be found in the [documentation](https://github.com/Cumulocity-IoT/cumulocity-dynamic-mapper#microservice).
* The solution was renamed from **Mqtt-mapping** to **Cumulocity Dynamic Mapper**. If you still want to use previous releases (**Mqtt-mapping** < 4.0.0) you can find them in the [release section](https://github.com/Cumulocity-IoT/cumulocity-dynamic-mapper/releases?page=4) on page 4.