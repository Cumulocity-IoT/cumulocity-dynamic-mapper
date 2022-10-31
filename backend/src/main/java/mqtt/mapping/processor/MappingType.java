package mqtt.mapping.processor;

public enum MappingType {
    JSON ("JSON", String.class),
    FLAT_FILE ( "FLAT_FILE", String.class),
    GENERIC_BINARY ( "GENERIC_BINARY", byte[].class);


    public final String name;
    public final Class<?> payloadType;

    private MappingType (String name, Class<?> payloadType){
        this.name = name;
        this.payloadType = payloadType;
    }

    public String getName() {
        return this.name;
    }

    public Class<?> getPayloadType() {
        return this.payloadType;
    }
}
