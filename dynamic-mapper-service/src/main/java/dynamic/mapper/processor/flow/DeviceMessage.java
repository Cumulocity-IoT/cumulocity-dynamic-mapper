package dynamic.mapper.processor.flow;

import java.time.Instant;
import java.util.Map;

import lombok.Getter;
import lombok.Setter;

/**
 * A (Pulsar) message received from a device or sent to a device
 */
@Setter
@Getter
public class DeviceMessage {

    /**
     * Cloud IDP and first step of tedge always gets an ArrayBuffer, but might be a
     * JS object if passing intermediate messages between steps in thin-edge
     */
    private Object payload;

     /** External ID to lookup (and optionally create) */
    private Object externalSource; // ExternalSource[] | ExternalSource

    /** Identifier for the source/dest transport e.g. "mqtt", "opc-ua" etc. */
    private String transportId;

    /** The topic on the transport (e.g. MQTT topic) */
    private String topic;

    /** Transport/MQTT client ID */
    private String clientId;

    /** Dictionary of transport/MQTT-specific fields/properties/headers */
    private Map<String, String> transportFields;

    /** Timestamp of incoming Pulsar message; does nothing when sending */
    private Instant time;

}
