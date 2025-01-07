package dynamic.mapping.connector.core.callback;

import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Builder
public class ConnectorMessage {
    private byte[] payload;

    private byte[] key;

    private String[] headers;

    @NotNull
    private String tenant;

    private String topic;

    @NotNull
    private String connectorIdentifier;

    private boolean sendPayload;

    private boolean supportsMessageContext;
}
