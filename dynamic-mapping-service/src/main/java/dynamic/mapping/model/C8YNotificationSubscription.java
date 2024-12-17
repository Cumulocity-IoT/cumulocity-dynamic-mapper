package dynamic.mapping.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import jakarta.validation.constraints.NotNull;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class C8YNotificationSubscription {

    @NotNull
    private API api;

    //@NotNull
    //private String connectorIdentifier;

    private List<Device> devices;
}
