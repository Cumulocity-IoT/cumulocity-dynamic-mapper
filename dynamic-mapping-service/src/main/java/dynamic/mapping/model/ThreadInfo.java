package dynamic.mapping.model;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ThreadInfo {
    private String threadName;
    private long threadId;
    private boolean isVirtual;
    private long startTime;
    private String connectorType;
    private String connectorId;
    private String connectorName;
    private boolean called;
    
    public long getDurationMs() {
        return System.currentTimeMillis() - startTime;
    }
    
    public String getStatus() {
        long duration = getDurationMs();
        if (duration > 300000) return "VERY_LONG"; // > 5 minutes
        if (duration > 60000) return "LONG";       // > 1 minute
        if (duration > 10000) return "MEDIUM";     // > 10 seconds
        return "NORMAL";
    }
}
