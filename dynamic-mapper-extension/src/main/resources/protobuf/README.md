# Protocol Buffer Definitions

This directory contains Protocol Buffer (protobuf) definitions for external extensions.

## Files

- **CustomEvent.proto** - Protobuf definition for custom event messages from devices

## Package Structure

After the refactoring, protobuf-generated Java classes are placed in direction-specific packages:

- **Inbound** (Device → Cumulocity): `dynamic.mapper.processor.extension.external.inbound`
- **Outbound** (Cumulocity → Device): `dynamic.mapper.processor.extension.external.outbound`

### CustomEvent.proto

This defines the structure for custom events received from devices:

```protobuf
message CustomEvent {
  int64 timestamp = 1;
  string txt = 2;
  string unit = 3;
  string externalIdType = 4;
  string externalId = 5;
  string eventType = 6;
}
```

**Generated class:** `CustomEventOuter.java`
**Package:** `dynamic.mapper.processor.extension.external.inbound`
**Used by:** `ProcessorExtensionCustomEvent.java`

## Regenerating Java Classes

When you modify a `.proto` file, regenerate the Java classes:

### From Repository Root

```bash
cd /Users/ck/work/git/cumulocity-dynamic-mapper
./resources/script/protobuf/generate_protobuf.sh extension
```

### What This Does

1. Runs `protoc` compiler on all `.proto` files in this directory
2. Generates Java classes in `src/main/java` with the package defined in `java_package` option
3. Creates `.desc` descriptor files for runtime reflection

### Requirements

- Protocol Buffer Compiler (`protoc`) must be installed
- Version should match the protobuf-java dependency in `pom.xml`

#### Install protoc on macOS
```bash
brew install protobuf
```

#### Install protoc on Linux
```bash
# Ubuntu/Debian
sudo apt-get install protobuf-compiler

# RHEL/CentOS
sudo yum install protobuf-compiler
```

## Creating New Protobuf Definitions

### For Inbound Processing (Device → Cumulocity)

1. Create a new `.proto` file in this directory:

```protobuf
syntax = "proto3";
package processor.protobuf;

option java_package = "dynamic.mapper.processor.extension.external.inbound";
option java_outer_classname = "MyDeviceMessageOuter";

message MyDeviceMessage {
  int64 timestamp = 1;
  float value = 2;
  string deviceId = 3;
}
```

2. Regenerate Java classes:
```bash
cd /Users/ck/work/git/cumulocity-dynamic-mapper
./resources/script/protobuf/generate_protobuf.sh extension
```

3. Create an extension that uses it:
```java
package dynamic.mapper.processor.extension.external.inbound;

public class ProcessorExtensionMyDevice implements ProcessorExtensionSource<byte[]> {
    @Override
    public void extractFromSource(ProcessingContext<byte[]> context) {
        try {
            MyDeviceMessageOuter.MyDeviceMessage message =
                MyDeviceMessageOuter.MyDeviceMessage.parseFrom(context.getPayload());

            // Extract fields and add substitutions
            context.addSubstitution("timestamp",
                new DateTime(message.getTimestamp()).toString(),
                TYPE.TEXTUAL, RepairStrategy.DEFAULT, false);
        } catch (InvalidProtocolBufferException e) {
            throw new ProcessingException(e.getMessage());
        }
    }
}
```

### For Outbound Processing (Cumulocity → Device)

1. Create a new `.proto` file with outbound package:

```protobuf
syntax = "proto3";
package processor.protobuf;

option java_package = "dynamic.mapper.processor.extension.external.outbound";
option java_outer_classname = "DeviceCommandOuter";

message DeviceCommand {
  string commandType = 1;
  string targetId = 2;
  bytes payload = 3;
}
```

2. Follow the same regeneration and extension creation steps

## Important Notes

### Package Configuration

The `java_package` option in the `.proto` file determines where the generated Java class will be placed:

- **Must start with:** `dynamic.mapper.processor.extension.external`
- **Inbound:** Add `.inbound` suffix
- **Outbound:** Add `.outbound` suffix

### Security Validation

The framework validates that external extensions are in the allowed package:
```properties
APP.externalExtensionsAllowedPackage=dynamic.mapper.processor.extension.external
```

Both `inbound` and `outbound` subpackages are allowed.

### Generated Files

After running the generation script, you'll have:

1. **Java class:** `src/main/java/dynamic/mapper/processor/extension/external/{direction}/*.java`
2. **Descriptor:** `src/main/resources/protobuf/*.desc`

**Important:** The Java class is auto-generated. Don't edit it manually - edit the `.proto` file instead.

### Version Compatibility

Ensure the protobuf version matches across:
- `protoc` compiler version
- `protobuf-java` dependency in `pom.xml`
- Protocol syntax version in `.proto` files

Current setup uses **proto3** syntax.

## Example: CustomEvent Flow

1. **Device** sends binary protobuf payload
2. **ProcessorExtensionCustomEvent** parses it:
   ```java
   CustomEventOuter.CustomEvent payloadProtobuf =
       CustomEventOuter.CustomEvent.parseFrom(payload);
   ```
3. **Extracts fields** and creates substitutions
4. **Substitution processor** applies them to template
5. **Result** sent to Cumulocity as Event

## Troubleshooting

### "Cannot find symbol CustomEventOuter"

- Run the generation script: `./resources/script/protobuf/generate_protobuf.sh extension`
- Refresh your IDE/rebuild the project

### "Package does not match expected"

- Check `java_package` option in `.proto` file
- Must be `dynamic.mapper.processor.extension.external.inbound` or `.outbound`
- Regenerate after fixing

### "InvalidProtocolBufferException"

- Device payload doesn't match protobuf definition
- Verify device is sending correct format
- Check field numbers and types match
- Use `protoc --decode` to debug:
  ```bash
  protoc --decode=processor.protobuf.CustomEvent CustomEvent.proto < payload.bin
  ```

### Generation Script Fails

- Verify `protoc` is installed: `protoc --version`
- Ensure you're in the repository root
- Check `.proto` file syntax: `protoc --lint CustomEvent.proto`

## Best Practices

1. **Version your protobuf definitions** - Breaking changes require coordination with devices
2. **Use field numbers carefully** - Never reuse field numbers
3. **Add new fields at the end** - For backward compatibility
4. **Document field meanings** - Add comments in `.proto` files
5. **Test with real payloads** - Before deploying to devices
6. **Keep it simple** - Use simple types when possible
7. **Consider optional fields** - For flexible device implementations

## Further Reading

- [Protocol Buffers Documentation](https://developers.google.com/protocol-buffers)
- [Proto3 Language Guide](https://developers.google.com/protocol-buffers/docs/proto3)
- [Java Generated Code Guide](https://developers.google.com/protocol-buffers/docs/reference/java-generated)
- Extension framework: `../java/dynamic/mapper/processor/extension/external/README.md`
