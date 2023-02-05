package mqtt.mapping.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

import javax.validation.constraints.NotNull;

@Getter
@Setter
@NoArgsConstructor
public class C8YAPISubscription {

    @NotNull
    private API api;

    private List<Device> devices;
}
