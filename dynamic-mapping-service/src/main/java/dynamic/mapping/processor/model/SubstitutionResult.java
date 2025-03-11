package dynamic.mapping.processor.model;

import java.util.List;

public class SubstitutionResult {
    public final List<Substitution> substitutions;

    public SubstitutionResult(List<Substitution> substitutions) {
      this.substitutions = substitutions;
    }

    @Override
    public String toString() {
        return "SubstitutionResult{" +
                "substitutions=" + substitutions +
                '}';
    }
}
