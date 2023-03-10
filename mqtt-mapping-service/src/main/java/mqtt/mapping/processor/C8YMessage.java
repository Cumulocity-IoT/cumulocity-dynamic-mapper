package mqtt.mapping.processor;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import mqtt.mapping.model.API;

import javax.validation.constraints.NotNull;

@Getter
@Setter
@NoArgsConstructor
public class C8YMessage {
    @NotNull
    private String payload; 
    
    @NotNull
    private API api; 
}