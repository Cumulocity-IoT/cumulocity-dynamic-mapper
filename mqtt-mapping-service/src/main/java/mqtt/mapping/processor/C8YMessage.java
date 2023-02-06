package mqtt.mapping.processor;

import javax.validation.constraints.NotNull;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import mqtt.mapping.model.API;

@Getter
@Setter
@NoArgsConstructor
public class C8YMessage {
    @NotNull
    private String payload; 
    
    @NotNull
    private API api; 
}