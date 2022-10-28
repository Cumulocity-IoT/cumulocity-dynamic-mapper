package mqtt.mapping.processor;

public enum RepairStrategy {
    DEFAULT,
    USE_FIRST_VALUE_OF_ARRAY,
    USE_LAST_VALUE_OF_ARRAY,
    IGNORE,
    REMOVE_IF_MISSING,
}
