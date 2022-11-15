package mqtt.mapping.model;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import javax.validation.constraints.NotNull;

@Getter
@Setter
@ToString()
public class Extension implements Serializable {

    public Extension() {
        extensions = new ArrayList<ExtensionEntry>();
    }
    
    public Extension(String name) {
        this();
        this.name = name;
    }

    @NotNull
    public boolean loadedSuccessfully;

    @NotNull
    public String name;

    @NotNull
    public List<ExtensionEntry> extensions;
}
