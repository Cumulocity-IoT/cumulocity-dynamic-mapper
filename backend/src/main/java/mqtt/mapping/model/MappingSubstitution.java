package mqtt.mapping.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;

import javax.validation.constraints.NotNull;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;

@Getter
@Setter
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
