package mqtt.mapping.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import mqtt.mapping.processor.extension.ProcessorExtension;

import java.io.Serializable;

import javax.validation.constraints.NotNull;

import com.fasterxml.jackson.annotation.JsonIgnore;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@ToString()
public class ExtensionEntry implements Serializable {

    @NotNull
    private String event;

    @NotNull
    private String extension;

    @NotNull
    @JsonIgnore
    private ProcessorExtension extensionImplementation;
}
