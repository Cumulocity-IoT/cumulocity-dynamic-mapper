package dynamic.mapping.connector.core.callback;

import javax.validation.constraints.NotNull;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class ConnectorMessage {
    private byte[] payload;

    private String[] headers;

    @NotNull
    private String tenant;

    private String topic;
    
    @NotNull
    private String connectorIdent;

    private boolean sendPayload;
}
