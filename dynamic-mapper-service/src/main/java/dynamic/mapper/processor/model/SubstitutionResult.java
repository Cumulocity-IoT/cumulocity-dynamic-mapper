package dynamic.mapper.processor.model;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class SubstitutionResult {
    public final Map<String, List<SubstituteValue>> substitutions;

    public Map<String, List<SubstituteValue>> getSubstitutions() {
        return substitutions;
    }

    public Set<String> alarms;

    public Set<String> getAlarms() {
        return alarms;
    }

    public SubstitutionResult(Map<String, List<SubstituteValue>> substitutions, Set<String> alarms) {
        if (substitutions == null) {
            throw new IllegalArgumentException("Substitutions map cannot be null");
        }
        this.substitutions = substitutions;
        this.alarms = alarms;
    }

    public SubstitutionResult(Map<String, List<SubstituteValue>> substitutions) {
        if (substitutions == null) {
            throw new IllegalArgumentException("Substitutions map cannot be null");
        }
        this.substitutions = substitutions;
        this.alarms = new HashSet<>();
    }

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
