package mqttagent.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.io.Serializable;

import javax.validation.constraints.NotNull;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;

@Data
@AllArgsConstructor
@NoArgsConstructor
@ToString()
public class MappingSubstitution implements Serializable {

    @NotNull
    public String pathSource;

    @NotNull
    public String pathTarget;
    @JsonSetter(nulls = Nulls.SKIP)
    public boolean definesIdentifier;
}
