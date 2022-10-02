package mqttagent.configuration;
import javax.validation.constraints.NotNull;

import lombok.Data;
import lombok.ToString;

@Data
@ToString ()
public class MQTTConfiguration implements Cloneable {

    @NotNull
    public String mqttHost;
    
    @NotNull
    public int mqttPort;

    @NotNull
    public String user;

    @NotNull
    @ToString.Exclude
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

