package mqtt.mapping.connector.callback;

public class ConnectorMessage {
    byte[] payload;
    String[] headers;

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
}
