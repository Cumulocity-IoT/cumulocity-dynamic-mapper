# Cumulocity Dynamic Mapper

Cumulocity does not natively let devices and other systems exchange arbitrary payloads over
MQTT, Kafka, HTTP, AMQP, Apache Pulsar, or Google Cloud Pub/Sub. This project closes that gap by
providing the following artifacts:

* A **Microservice** - hosts connectors for all of the brokers/protocols above, listens for
  incoming messages, and applies the configured mappings in either direction. Exposes REST
  endpoints for the UI to manage connector configurations and mappings.
* A **Frontend Plugin** - uses those endpoints to configure broker connections and to perform
  mapping within the Cumulocity IoT UI, either graphically or in code. Mappings can be defined
  using [JSONata](https://jsonata.org/) expressions, as JavaScript ("Smart Functions"), or as a
  Java processor extension; an AI agent can also generate a first draft of a mapping from a
  sample payload.

Using the solution you are able to connect to any of the supported brokers and map arbitrary
payloads on any topic dynamically to the Cumulocity IoT Domain Model, without writing or
deploying custom device agents or firmware.

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
The Dynamic Mapper supports the following connectors:
<div class="table-responsive table-width-80">
    <table class="table _table-striped">
    <thead class="thead-light">
        <tr>
        <th style="width: 26%;">Connector</th>
        <th class="text-center" style="width: 12%;">Direction: Inbound</th>
        <th class="text-center" style="width: 12%;">Direction: Outbound</th>
        <th class="text-center" style="width: 12%;">Supports JavaScript</th>
        <th style="width: 26%;">Supported Mapping Types</th>
        </tr>
    </thead>
    <tbody>
        <tr>
        <td><strong>AMQP 0.9.1</strong><br><small class="text-muted">(RabbitMQ, etc.)</small></td>
        <td class="text-center"><strong>X</strong></td>
        <td class="text-center"><strong>X</strong></td>
        <td class="text-center"><strong>X</strong><br></td>
        <td>JSON, Flat File, Hex, Any Payload</td>
        </tr>
        <tr class="table-light">
        <td><strong>AMQP 1.0</strong><br><small class="text-muted">(Azure Service Bus, Artemis, Solace, etc.)</small></td>
        <td class="text-center"><strong>X</strong></td>
        <td class="text-center"><strong>X</strong></td>
        <td class="text-center"><strong>X</strong><br></td>
        <td>JSON, Flat File, Hex, Any Payload</td>
        </tr>
        <tr>
        <td><strong>Apache Pulsar</strong></td>
        <td class="text-center"><strong>X</strong></td>
        <td class="text-center"><strong>X</strong></td>
        <td class="text-center"><strong>X</strong><br></td>
        <td>JSON, Flat File, Hex, Any Payload</td>
        </tr>
        <tr class="table-light">
        <td><strong>Cumulocity API</strong></td>
        <td class="text-center text-muted">-</td>
        <td class="text-center"><strong>X</strong></td>
        <td class="text-center"><strong>X</strong><br></td>
        <td>JSON</td>
        </tr>
        <tr>
        <td><strong>Cumulocity MQTT Service </strong><small class="text-muted">(device
            isolation, only one instance per tenant exists)</small></td>
        <td class="text-center"><strong>X</strong></td>
        <td class="text-center"><strong>X</strong></td>
        <td class="text-center"><strong>X</strong><br></td>
        <td>JSON, Flat File, Hex, SparkPlugB (partially), Any Payload</td>
        </tr>
        <tr class="table-light">
        <td><strong>Generic MQTT</strong></td>
        <td class="text-center"><strong>X</strong></td>
        <td class="text-center"><strong>X</strong></td>
        <td class="text-center"><strong>X</strong><br></td>
        <td>JSON, Flat File, Hex, SparkPlugB, Any Payload</td>
        </tr>
        <tr>
        <td><strong>Google Cloud Pub/Sub</strong><br><small class="text-muted">(e.g. Google Manufacturing Data Engine)</small></td>
        <td class="text-center"><strong>X</strong></td>
        <td class="text-center"><strong>X</strong></td>
        <td class="text-center"><strong>X</strong><br></td>
        <td>JSON, Flat File, Hex, Any Payload</td>
        </tr>
        <tr class="table-light">
        <td><strong>HTTP Connector</strong><br><small class="text-muted">(only one instance per tenant
            exists)</small></td>
        <td class="text-center"><strong>X</strong></td>
        <td class="text-center text-muted">-</td>
        <td class="text-center"><strong>X</strong><br></td>
        <td>JSON, Flat File, Hex, Any Payload</td>
        </tr>
        <tr>
        <td><strong>Kafka</strong></td>
        <td class="text-center"><strong>X</strong></td>
        <td class="text-center"><strong>X</strong></td>
        <td class="text-center"><strong>X</strong><br></td>
        <td>JSON, Flat File, Hex, Any Payload</td>
        </tr>
        <tr class="table-light">
        <td><strong>Webhook</strong><br><small class="text-muted">(including Cumulocity Rest API)</small></td>
        <td class="text-center text-muted">-</td>
        <td class="text-center"><strong>X</strong></td>
        <td class="text-center"><strong>X</strong><br></td>
        <td>JSON</td>
        </tr>
    </tbody>
    </table>
</div>
<br>
<br>
For the complete documentation please check the GitHub project [cumulocity-dynamic-mapper](https://github.com/Cumulocity-IoT/cumulocity-dynamic-mapper).

**NOTE:** 
* This solution requires an additional microservice. The microservice `dynamic-mapper-service.zip` can be found in the [release section](https://github.com/Cumulocity-IoT/cumulocity-dynamic-mapper/releases) of the github project. Instructions on how to deploy the microservice can be found in the [Installation Guide](https://github.com/Cumulocity-IoT/cumulocity-dynamic-mapper/blob/main/docs/installation.md).
* The solution was renamed from **Mqtt-mapping** to **Cumulocity Dynamic Mapper**. If you still want to use previous releases (**Mqtt-mapping** < 4.0.0) you can find them in the [release section](https://github.com/Cumulocity-IoT/cumulocity-dynamic-mapper/releases?page=4) on page 4.