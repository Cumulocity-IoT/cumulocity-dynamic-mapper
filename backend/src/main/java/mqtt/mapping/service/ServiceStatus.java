package mqtt.mapping.service;

import lombok.AllArgsConstructor;
import lombok.Data;

enum Status {
    CONNECTED,
    ACTIVATED,
    CONFIGURED,
    NOT_READY
}

@Data
@AllArgsConstructor
public class ServiceStatus {
    private Status status;
    
    public static ServiceStatus connected() {
        return new ServiceStatus(Status.CONNECTED);
    }
    
    public static ServiceStatus activated() {
        return new ServiceStatus(Status.ACTIVATED);
    }
    
    public static ServiceStatus configured() {
        return new ServiceStatus(Status.CONFIGURED);
    }

    public static ServiceStatus notReady() {
        return new ServiceStatus(Status.NOT_READY);
    }
}
