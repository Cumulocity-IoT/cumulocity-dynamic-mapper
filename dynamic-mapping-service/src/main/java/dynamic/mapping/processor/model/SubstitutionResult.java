package dynamic.mapping.processor.model;

import java.util.List;
import java.util.Map;

public class SubstitutionResult {
    public final Map<String, List<SubstituteValue>>substitutions;

    public Map<String, List<SubstituteValue>> getSubstitutions() {
        return substitutions;
    }

    public SubstitutionResult(Map<String, List<SubstituteValue>> substitutions) {
      this.substitutions = substitutions;
    }

    @Override
    public String toString() {
        return "SubstitutionResult{" +
                "substitutions=" + substitutions +
                '}';
    }
}
