package mqttagent.services;

import lombok.AllArgsConstructor;
import lombok.Data;

enum Status {
    NOT_CONNECTED,
    READY
}

@Data
@AllArgsConstructor
public class ServiceStatus {
    private Status status;

    public static ServiceStatus notAuthenticated() {
        return new ServiceStatus(Status.NOT_CONNECTED);
    }

    public static ServiceStatus ready() {
        return new ServiceStatus(Status.READY);
    }
}
