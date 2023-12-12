package dynamic.mapping.processor;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import dynamic.mapping.model.API;

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