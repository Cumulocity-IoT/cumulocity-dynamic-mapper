package mqtt.mapping.processor.model;

public enum ProcessingType {
    UNDEFINED,
    ONE_DEVICE_ONE_VALUE,
    ONE_DEVICE_MULTIPLE_VALUE,
    MULTIPLE_DEVICE_ONE_VALUE,
    MULTIPLE_DEVICE_MULTIPLE_VALUE,
}
