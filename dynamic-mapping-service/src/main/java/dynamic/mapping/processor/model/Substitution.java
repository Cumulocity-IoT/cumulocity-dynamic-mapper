package dynamic.mapping.processor.model;

public class Substitution {
    String key;
    Object value;
    String type;
    String repairStrategy;
    public Substitution(String key, Object value, String type, String repairStrategy) {
        this.key = key;
        this.value = value;
        this.type = type;
        this.repairStrategy = repairStrategy;
    }
    public String getKey() {
        return key;
    }
    public void setKey(String key) {
        this.key = key;
    }
    public Object getValue() {
        return value;
    }
    public void setValue(Object value) {
        this.value = value;
    }
    public String getType() {
        return type;
    }
    public void setType(String type) {
        this.type = type;
    }
    public String getRepairStrategy() {
        return repairStrategy;
    }
    public void setRepairStrategy(String repairStrategy) {
        this.repairStrategy = repairStrategy;
    }
}
