package dynamic.mapping.connector.core.callback;

public class ConnectorMessage {
    byte[] payload;
    String[] headers;
    String tenant;
    String topic;
    boolean sendPayload;

    public byte[] getPayload() {
        return payload;
    }

    public void setPayload(byte[] payload) {
        this.payload = payload;
    }

    public String[] getHeaders() {
        return headers;
    }

    public void setHeaders(String[] headers) {
        this.headers = headers;
    }

    public String getTenant() {
        return tenant;
    }

    public void setTenant(String tenant) {
        this.tenant = tenant;
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public boolean isSendPayload() {
        return sendPayload;
    }

    public void setSendPayload(boolean sendPayload) {
        this.sendPayload = sendPayload;
    }
}
