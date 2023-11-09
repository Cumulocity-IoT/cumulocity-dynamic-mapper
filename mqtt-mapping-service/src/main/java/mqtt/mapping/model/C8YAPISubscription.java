package mqtt.mapping.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.validation.constraints.NotNull;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class C8YAPISubscription {

    @NotNull
    private API api;

    //@NotNull
    //private String connectorId;

    private List<Device> devices;
}
