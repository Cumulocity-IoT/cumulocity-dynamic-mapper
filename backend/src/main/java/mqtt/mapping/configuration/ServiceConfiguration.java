package mqtt.mapping.configuration;
import javax.validation.constraints.NotNull;

import org.apache.commons.lang3.StringUtils;

import lombok.Data;
import lombok.ToString;

@Data
@ToString ()
public class ServiceConfiguration implements Cloneable {

    @NotNull
    public boolean logPayload;

}

