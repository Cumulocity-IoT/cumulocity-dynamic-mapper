package mqttagent.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.io.Serializable;

import javax.validation.constraints.NotNull;

@Data
@AllArgsConstructor
@NoArgsConstructor
@ToString()
public class MappingSubstitution implements Serializable {

    @NotNull
    public String pathSource;

    @NotNull
    public String pathTarget;
}
