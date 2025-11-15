package dynamic.mapper.processor.flow;

/**
 * Enumeration of possible destinations for Cumulocity messages
 */
public enum Destination {
    CUMULOCITY("cumulocity"),
    ICEFLOW("iceflow"),
    STREAMING_ANALYTICS("streaming-analytics");

    private final String value;

    Destination(String value) {
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
    public static Destination fromValue(String value) {
        for (Destination dest : Destination.values()) {
            if (dest.value.equalsIgnoreCase(value)) {
                return dest;
            }
        }
        throw new IllegalArgumentException("Unknown Destination: " + value);
    }
}