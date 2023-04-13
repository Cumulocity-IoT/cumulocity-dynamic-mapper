package mqtt.mapping.model;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import javax.validation.constraints.NotNull;

@Getter
@Setter
@ToString()
public class Feature {
    @NotNull
    public boolean outputMappingEnabled;

    @NotNull
    public boolean externalExtensionsEnabled;
}
