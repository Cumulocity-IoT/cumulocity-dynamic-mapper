package mqttagent.model;
import javax.validation.constraints.NotNull;

import lombok.Data;

@Data
public class MQTTConfiguration implements Cloneable {

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

    @NotNull
    public boolean active;


    public Object clone() throws CloneNotSupportedException
    {
        return super.clone();
    }
}

