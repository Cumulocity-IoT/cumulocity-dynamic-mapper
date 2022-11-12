package mqtt.mapping.processor;

public enum MappingType {
    JSON ("JSON", String.class, null),
    FLAT_FILE ( "FLAT_FILE", String.class, null),
    GENERIC_BINARY ( "GENERIC_BINARY", byte[].class, null),
    PROTOBUF ( "PROTOBUF", byte[].class, "protobuf");

    public final String name;
    public final Class<?> payloadType;
    public final String packageName;

    private MappingType (String name, Class<?> payloadType, String packageName){
        this.name = name;
        this.payloadType = payloadType;
        this.packageName = packageName;
    }

    public String getName() {
        return this.name;
    }

    public Class<?> getPayloadType() {
        return this.payloadType;
    }
}
