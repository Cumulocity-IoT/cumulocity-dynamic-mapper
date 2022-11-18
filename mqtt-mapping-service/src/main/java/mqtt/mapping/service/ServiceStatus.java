package mqtt.mapping.service;

import lombok.Data;

@Data
// @NoArgsConstructor
// @AllArgsConstructor
public class ServiceStatus {
    private Status status;

    public ServiceStatus(Status status){
        this.status = status;
    }

    public ServiceStatus(){
        this.status = Status.NOT_READY;
    }
    
    public static ServiceStatus connected() {
        return new ServiceStatus(Status.CONNECTED);
    }
    
    public static ServiceStatus activated() {
        return new ServiceStatus(Status.ENABLED);
    }
    
    public static ServiceStatus configured() {
        return new ServiceStatus(Status.CONFIGURED);
    }

    public static ServiceStatus notReady() {
        return new ServiceStatus(Status.NOT_READY);
    }
}
