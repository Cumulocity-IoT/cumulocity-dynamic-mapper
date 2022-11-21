package mqtt.mapping.core;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.NotNull;

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
