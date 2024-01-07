package dynamic.mapping.connector.core.callback;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ConnectorMessage {
    byte[] payload;
    String[] headers;
    String tenant;
    String topic;
    String connectorIdent;
    boolean sendPayload;
}
