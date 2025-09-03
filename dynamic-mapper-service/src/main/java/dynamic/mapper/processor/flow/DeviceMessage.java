package dynamic.mapper.processor.flow;

import org.graalvm.polyglot.Value;
import java.time.Instant;
import java.util.Map;

/**
 * A (Pulsar) message received from a device or sent to a device
 */
public class DeviceMessage {

    /**
     * Cloud IDP and first step of tedge always gets an ArrayBuffer, but might be a
     * JS object if passing intermediate messages between steps in thin-edge
     */
    private Object payload;

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

    // Constructors
    public DeviceMessage() {
    }

    public DeviceMessage(Object payload, String topic) {
        this.payload = payload;
        this.topic = topic;
    }

    // Getters and Setters
    public Object getPayload() {
        return payload;
    }

    public void setPayload(Object payload) {
        this.payload = payload;
    }

    public String getTransportId() {
        return transportId;
    }

    public void setTransportId(String transportId) {
        this.transportId = transportId;
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public Map<String, String> getTransportFields() {
        return transportFields;
    }

    public void setTransportFields(Map<String, String> transportFields) {
        this.transportFields = transportFields;
    }

    public Instant getTime() {
        return time;
    }

    public void setTime(Instant time) {
        this.time = time;
    }
}
