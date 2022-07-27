package mqttagent.configuration;
import javax.validation.constraints.NotNull;

import lombok.Data;

@Data
public class MQTTConfiguration {

    @NotNull
    public String mqttHost;
    
    @NotNull
    public int mqttPort;

    @NotNull
    public String user;

    @NotNull
    public String password;

    @NotNull
    public String clientId;

    @NotNull
    public boolean useTLS;
}

