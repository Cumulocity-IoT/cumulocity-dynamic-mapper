package mqtt.mapping.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import mqtt.mapping.processor.extension.ProcessorExtension;

import java.io.Serializable;

import javax.validation.constraints.NotNull;

import com.fasterxml.jackson.annotation.JsonIgnore;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString()
public class ExtensionEntry implements Serializable {

    @NotNull
    private String event;

    @NotNull
    private String name;

    @NotNull
    @JsonIgnore
    private ProcessorExtension extensionImplementation;

    @NotNull
    public boolean loaded;
}
