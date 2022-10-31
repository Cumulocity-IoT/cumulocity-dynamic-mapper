package mqtt.mapping.configuration;
import javax.validation.constraints.NotNull;

import org.apache.commons.lang3.StringUtils;

import lombok.Data;
import lombok.ToString;

@Data
@ToString ()
public class ConfigurationConnection implements Cloneable {

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
    public boolean enabled;

    @NotNull
    public boolean useSelfSignedCertificate;

    public String fingerprintSelfSignedCertificate;

    @NotNull
    public String nameCertificate;

    public Object clone() 
    {
        try {
            return super.clone();
        } catch (CloneNotSupportedException e) {
            return null;
        }
    }

    public static boolean isValid (ConfigurationConnection mc){
        return (mc != null) && !StringUtils.isEmpty(mc.mqttHost) &&
        !(mc.mqttPort == 0) &&
        !StringUtils.isEmpty(mc.user) &&
        !StringUtils.isEmpty(mc.password) &&
        !StringUtils.isEmpty(mc.clientId);
    }

    public static boolean isEnabled(ConfigurationConnection mc) {
        return ConfigurationConnection.isValid(mc) && mc.enabled;
    }
}

