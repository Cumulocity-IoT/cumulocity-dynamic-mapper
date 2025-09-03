package dynamic.mapper.processor.flow;

import org.graalvm.polyglot.Value;

/**
 * A request going to or coming from Cumulocity core (or IceFlow/offloading)
 */
public class CumulocityMessage {

    /** The same payload that would be used in the C8Y REST/SmartREST API */
    private Object payload;

    /**
     * Which type in the C8Y api is being modified. Singular not plural. e.g.
     * "measurement"
     */
    private String cumulocityType;

    /** What kind of operation is being performed on this type */
    private String action; // "create" | "update"

    /** External ID to lookup (and optionally create) */
    private Object externalSource; // ExternalSource[] | ExternalSource

    /** Internal Cumulocity source */
    private Object internalSource; // CumulocitySource[] | CumulocitySource

    /** Destination for the message */
    private String destination; // "cumulocity" | "iceflow" | "streaming-analytics"

    // Constructors
    public CumulocityMessage() {
    }

    public CumulocityMessage(Object payload, String cumulocityType, String action) {
        this.payload = payload;
        this.cumulocityType = cumulocityType;
        this.action = action;
    }

    // Getters and Setters
    public Object getPayload() {
        return payload;
    }

    public void setPayload(Object payload) {
        this.payload = payload;
    }

    public String getCumulocityType() {
        return cumulocityType;
    }

    public void setCumulocityType(String cumulocityType) {
        this.cumulocityType = cumulocityType;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public Object getExternalSource() {
        return externalSource;
    }

    public void setExternalSource(Object externalSource) {
        this.externalSource = externalSource;
    }

    public Object getInternalSource() {
        return internalSource;
    }

    public void setInternalSource(Object internalSource) {
        this.internalSource = internalSource;
    }

    public String getDestination() {
        return destination;
    }

    public void setDestination(String destination) {
        this.destination = destination;
    }
}