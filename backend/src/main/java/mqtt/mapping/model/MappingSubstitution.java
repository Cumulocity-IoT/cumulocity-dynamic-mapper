package mqtt.mapping.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import mqtt.mapping.processor.RepairStrategy;

import java.io.Serializable;

import javax.validation.constraints.NotNull;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.databind.JsonNode;

@Getter
@NoArgsConstructor
@ToString()
public class MappingSubstitution implements Serializable {

    public static class SubstituteValue implements Cloneable {
        public static enum TYPE {
            NUMBER,
            TEXTUAL, OBJECT, IGNORE
        }

        public JsonNode value;
        public TYPE type;
        public RepairStrategy repairStrategy;

        public SubstituteValue(JsonNode value, TYPE type, RepairStrategy repair) {
            this.type = type;
            this.value = value;
            this.repairStrategy = repair;
        }

        public Object typedValue() {
            if (type.equals(TYPE.TEXTUAL)) {
                return value.textValue();
            } else if (type.equals(TYPE.OBJECT)) {
                return value;
            } else {
                // check if int
                try {
                    return Integer.parseInt(value.textValue());
                } catch (NumberFormatException e1) {
                    // not int
                    try {
                        return Float.parseFloat(value.textValue());
                    } catch (NumberFormatException e2) {
                        // not int
                        try {
                            return Double.parseDouble(value.textValue());
                        } catch (NumberFormatException e3) {
                            return value;
                        }
                    }
                }
            }
        }

        @Override
        public SubstituteValue clone() {
            return new SubstituteValue(this.value, this.type, this.repairStrategy);
        }
    }

    @NotNull
    public String pathSource;

    @NotNull
    public String pathTarget;

    @NotNull
    public RepairStrategy repairStrategy;
    
    @JsonSetter(nulls = Nulls.SKIP)
    public boolean definesIdentifier;
}
