package dynamic.mapper.processor.flow;

/**
 * Enumeration of Cumulocity object types
 */
public enum CumulocityType {
    MEASUREMENT("measurement"),
    EVENT("event"),
    ALARM("alarm"),
    OPERATION("operation"),
    MANAGED_OBJECT("managedObject");

    private final String value;

    CumulocityType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }

    /**
     * Get enum from string value
     */
    public static CumulocityType fromValue(String value) {
        for (CumulocityType type : CumulocityType.values()) {
            if (type.value.equalsIgnoreCase(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown CumulocityType: " + value);
    }
}