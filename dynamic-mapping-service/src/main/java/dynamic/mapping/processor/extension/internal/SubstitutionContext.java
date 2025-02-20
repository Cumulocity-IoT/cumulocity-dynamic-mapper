package dynamic.mapping.processor.extension.internal;

public class SubstitutionContext {
    private final String name;
    private final String value;
  
    public SubstitutionContext(String name, String value) {
      this.name = name;
      this.value = value;
    }
  
    public String getName() {
      return name;
    }
  
    public String getValue() {
      return value;
    }
  }
