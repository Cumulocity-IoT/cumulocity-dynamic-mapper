package mqtt.mapping.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.io.Serializable;

import javax.validation.constraints.NotNull;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;

@Getter
@NoArgsConstructor
@ToString()
public class MappingSubstitution implements Serializable {

    public static class SubstituteValue {
        public static enum TYPE {
            NUMBER,
            TEXTUAL
        }

        public String value;
        public TYPE type;

        public SubstituteValue(String value, TYPE type) {
            this.type = type;
            this.value = value;
        }

        public Object typedValue() {
            if (type.equals(TYPE.TEXTUAL)) {
                return value;
            } else {
                // check if int
                try {
                    return Integer.parseInt(value);
                } catch (NumberFormatException e1) {
                    // not int
                    try {
                        return Float.parseFloat(value);
                    } catch (NumberFormatException e2) {
                        return null;
                    }
                }
            }
        }
    }

    @NotNull
    public String pathSource;

    @NotNull
    public String pathTarget;
    @JsonSetter(nulls = Nulls.SKIP)
    public boolean definesIdentifier;
}
