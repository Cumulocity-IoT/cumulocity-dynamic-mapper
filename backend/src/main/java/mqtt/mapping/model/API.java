package mqtt.mapping.model;

public enum API {
    ALARM ( "ALARM",  "source.id" ),
    EVENT ( "EVENT",  "source.id" ),
    MEASUREMENT ( "MEASUREMENT",  "source.id" ),
    INVENTORY ( "INVENTORY",  "_DEVICE_IDENT_" ),
    OPERATION ( "OPERATION",  "deviceId" );

    public final String name;
    public final String identifier;

    private API (String name, String identifier){
        this.name = name;
        this.identifier = identifier;
    }
}