package mqtt.mapping.service;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.NotNull;

enum Operation {
    RELOAD,
    CONNECT,
    DISCONNECT, 
    RESFRESH_MAPPING_STATUS
}

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ServiceOperation {
    @NotNull
    private Operation operation;

    public static ServiceOperation reload() {
        return new ServiceOperation(Operation.RELOAD);
    }   
    public static ServiceOperation connect() {
        return new ServiceOperation(Operation.CONNECT);
    }
}
