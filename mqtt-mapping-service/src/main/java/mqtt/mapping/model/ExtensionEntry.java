package mqtt.mapping.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.io.Serializable;

import javax.validation.constraints.NotNull;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@ToString()
public class ExtensionEntry implements Serializable {

    @NotNull
    public String event;

    @NotNull
    public String extension;

}
