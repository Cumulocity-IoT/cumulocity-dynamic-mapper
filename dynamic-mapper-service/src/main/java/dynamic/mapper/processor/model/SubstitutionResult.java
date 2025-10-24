package dynamic.mapper.processor.model;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import lombok.Data;

@Data
public class SubstitutionResult {
    public Map<String, List<SubstituteValue>> substitutions;
    public Set<String> alarms;

    public SubstitutionResult() {
        this.substitutions = new HashMap<String, List<SubstituteValue>>();
        this.alarms = new HashSet<>();
    }

    @Override
    public String toString() {
        return "SubstitutionResult{" +
                "substitutions=" + substitutions +
                '}';
    }
}
