package mqtt.mapping.model;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import javax.validation.constraints.NotNull;

@Getter
@Setter
@ToString()
public class Extension implements Serializable {

    public Extension() {
        extensionEntries = new HashMap<String, ExtensionEntry>();
    }
    
    public Extension(String id, String name) {
        this();
        this.name = name;
        this.id = id;
    }

    public Extension(String id, String name, boolean external) {
        this(id, name);
        this.external = external;
    }

    @NotNull
    public String id;

    @NotNull
    public ExtensionStatus loaded;

    @NotNull
    public String name;

    @NotNull
    public boolean external;

    @NotNull
    public Map<String, ExtensionEntry> extensionEntries;
}
