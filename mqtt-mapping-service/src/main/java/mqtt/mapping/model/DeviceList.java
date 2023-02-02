package mqtt.mapping.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.validation.constraints.NotNull;
import java.io.Serializable;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class DeviceList implements Serializable {

    @NotNull
    public List<Device> deviceList;
}
