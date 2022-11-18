package mqtt.mapping.service;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.NotNull;

enum Operation {
    RELOAD_MAPPINGS,
    CONNECT,
    DISCONNECT, 
    RESFRESH_STATUS_MAPPING,
    RESET_STATUS_MAPPING,
    RELOAD_EXTENSIONS
}

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ServiceOperation {
    @NotNull
    private Operation operation;

    public static ServiceOperation reloadMappings() {
        return new ServiceOperation(Operation.RELOAD_MAPPINGS);
    }   
    public static ServiceOperation connect() {
        return new ServiceOperation(Operation.CONNECT);
    }
    public static ServiceOperation reloadExtensions() {
        return new ServiceOperation(Operation.RELOAD_EXTENSIONS);
    } 
}
