package mqttagent.service;

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
    
    public static ServiceOperation reload() {
        return new ServiceOperation(Operation.RELOAD);
    }   
    public static ServiceOperation connect() {
        return new ServiceOperation(Operation.CONNECT);
    }
}
