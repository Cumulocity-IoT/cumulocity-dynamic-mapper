# Enhance and Extensions

## Custom message broker connector

Additional connectors supporting different message brokers can be added to the dynamic mapper.
For that an abstract Class [AConnectorClient](dynamic-mapper-service/src/main/java/dynamic/mapper/connector/core/client/AConnectorClient.java) must be implemented handling the basic methods of a message broker like  `connect`, `subscribe` and `disconnect`.
In addition, a Callback must be implemented handling the message broker typical messages and forwarding it to a [GenericMessageCallback](dynamic-mapper-service/src/main/java/dynamic/mapper/connector/core/callback/GenericMessageCallback.java)

Check out the [MQTTCallback](dynamic-mapper-service/src/main/java/dynamic/mapper/connector/mqtt/MQTTCallback.java) as an example implementation.

## Mapper Extensions - general
In the folder [dynamic.mapper.processor.extension](dynamic-mapper-service/src/main/java/dynamic/mapper/processor/extension) you can implement  the Interface `ProcessorExtensionSource<O>` to implement the processing of your own messages. Together with the Java representation of your message you can build your own processor extension. This needs to be packages in a `jar` file. <br>
The extension packaged as a `jar` you can upload this extension using the tab `Processor Extension`, see [Processing Extensions (Protobuf, ...)](#processing-extensions-protobuf) for details.
In order for the mapper backend (`dynamic-mapper-service`) to find your extension you need to add the properties file `extension-external.properties`. The content could be as follows:
```
CustomEvent=external.extension.processor.dynamic.mapper.ProcessorExtensionCustomEvent
CustomMeasurement=external.extension.processor.dynamic.mapper.ProcessorExtensionCustomMeasurement
```

The steps required for an external extension are as follows. The extension:
1. has to implement the interface `ProcessorExtensionSource<O>`
2. be registered in the properties file <code>dynamic-mapper-extension/src/main/resources/extension-external.properties</code>
3. be developed /packed in the maven module <code>dynamic-mapper-extension</code>. **Not** in the maven module <code>dynamic-mapper-service</code>. This is reserved for internal extensions.
4. be uploaded through the Web UI.

> **_NOTE:_** When you implement `ProcessorExtensionSource<O>` an additional <code>RepairStrategy.CREATE_IF_MISSING</code> can be used. This helps to address mapping cases, where you want to create a mapping that adapts to different structures of source payloads. It is used to create a node in the target if it doesn't exist and allows for using mapping with dynamic content. See [sample 25](./resources/script/mapping/sampleMapping/SampleMappings_06.pdf).

A sample how to build an extension is contained in the maven module [dynamic-mapper-extension](dynamic-mapper-extension).
The following diagram shows how the dispatcher handles messages with different format:


<p align="center">
<img src="resources/image/Dynamic_Mapper_Diagram_Dispatcher.png"  style="width: 70%;" />
</p>
<br/>

The following diagram gives an overview on the step to build and use your own extension:

<p align="center">
<img src="resources/image/Dynamic_Mapper_Diagram_ProcessorExtensionSource_Guide.png"  style="width: 70%;" />
</p>
<br/>

## Mapper Extensions - portobuf

To process your own Protobuf message, you always need to write a Java class.
The workflow is as follows:

1. Describe the structure of your Protobuf message in a proto file, for example:

```
package processor.protobuf;

option java_package = "dynamic.mapper.processor.extension.external";
option java_outer_classname = "CustomEventOuter";

message CustomEvent {
  int64 timestamp = 1;
  string txt = 2;
  string unit = 3;
  string externalIdType = 4;
  string externalId = 5;
  string eventType = 6;
}
```

2. Generate Java binding classes using the `protoc` compiler, resulting in `CustomEventOuter.java`
Write your own extension in Java, for example `ProcessorExtensionCustomEvent.java`, by implementing the <code>ProcessorExtensionSource<byte[]></code> interface.

The actual mapping consists of the following lines:
```
javaCopycontext.addSubstitution("time", new DateTime(
        payloadProtobuf.getTimestamp())
        .toString(), TYPE.TEXTUAL, RepairStrategy.DEFAULT, false);
context.addSubstitution("text",
        payloadProtobuf.getTxt(), TYPE.TEXTUAL, RepairStrategy.DEFAULT, false);
context.addSubstitution("type", 
        payloadProtobuf.getEventType(), TYPE.TEXTUAL, RepairStrategy.DEFAULT, false);

// as the mapping uses useExternalId we have to map the id to
// _IDENTITY_.externalId
context.addSubstitution(context.getMapping().getGenericDeviceIdentifier(),
        payloadProtobuf.getExternalId()
                .toString(),
        TYPE.TEXTUAL, RepairStrategy.DEFAULT, false);
```
3. Create a property file named extension-external.properties with the following information:

```
<YOUR_EVENT_NAME>=<FQN_NAME_EXTENSION_JAVA_CLASS>
# For example: CustomEvent=dynamic.mapper.processor.extension.external.ProcessorExtensionCustomEvent
```

4. Package the class as a JAR file and upload it via the UI: Configuration -> Processor extension -> Add extension (button)
5. To use the extension, select a mapping of type "Extension Source" and choose the extension uploaded in step 5 in the "Define Substitutions" section.