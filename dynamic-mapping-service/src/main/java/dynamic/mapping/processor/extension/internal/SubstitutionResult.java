package dynamic.mapping.processor.extension.internal;

public class SubstitutionResult {
    private final Object result;

    public SubstitutionResult(Object result) {
      this.result = result;
    }

    @Override
    public String toString() {
        return "SubstitutionResult{" +
                "result=" + result +
                '}';
    }
}
