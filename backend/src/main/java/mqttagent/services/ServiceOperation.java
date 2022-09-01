package mqttagent.services;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

enum Operation {
    RELOAD,
    CONNECT,
    DISCONNECT
}

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ServiceOperation {
    private Operation operation;
    private String tenant ;
    
    public static ServiceOperation reload(String tenant) {
        return new ServiceOperation(Operation.RELOAD, tenant);
    }   
    public static ServiceOperation connect() {
        return new ServiceOperation(Operation.CONNECT, null);
    }
}
