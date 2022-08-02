package mqttagent.services;

import lombok.AllArgsConstructor;
import lombok.Data;

enum Status {
    ONLY_CONFIGURED,
    ACTIVATED,
    NOT_READY
}

@Data
@AllArgsConstructor
public class ServiceStatus {
    private Status status;

    public static ServiceStatus onlyConfigured() {
        return new ServiceStatus(Status.ONLY_CONFIGURED);
    }

    public static ServiceStatus activated() {
        return new ServiceStatus(Status.ACTIVATED);
    }

    public static ServiceStatus notReady() {
        return new ServiceStatus(Status.NOT_READY);
    }
}
