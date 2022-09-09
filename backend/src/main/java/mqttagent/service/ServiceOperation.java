package mqttagent.service;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.NotNull;

enum Operation {
    RELOAD,
    CONNECT,
    DISCONNECT
}

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ServiceOperation {
    @NotNull
    private Operation operation;
    @NotNull
    private String tenant ;
    
    public static ServiceOperation reload(String tenant) {
        return new ServiceOperation(Operation.RELOAD, tenant);
    }   
    public static ServiceOperation connect() {
        return new ServiceOperation(Operation.CONNECT, null);
    }
}
