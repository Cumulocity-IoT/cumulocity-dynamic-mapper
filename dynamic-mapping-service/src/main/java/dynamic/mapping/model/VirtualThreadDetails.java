package dynamic.mapping.model;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class VirtualThreadDetails {
    private long threadId;
    private String threadName;
    private Thread.State state;
    private long blockedTime;
    private long blockedCount;
    private long waitedTime;
    private long waitedCount;
    private String lockName;
    private long lockOwnerId;
    private String lockOwnerName;
    private boolean inNative;
    private boolean suspended;

    // Additional detailed information
    private String currentMethod;
    private List<String> stackTrace;
    private List<String> lockedMonitors;
    private List<String> lockedSynchronizers;

    // Computed fields
    public String getStateDescription() {
        return switch (state) {
            case NEW -> "Thread created but not yet started";
            case RUNNABLE -> "Thread is executing or ready to execute";
            case BLOCKED -> "Thread is blocked waiting for a monitor lock";
            case WAITING -> "Thread is waiting indefinitely for another thread";
            case TIMED_WAITING -> "Thread is waiting for a specified period of time";
            case TERMINATED -> "Thread has completed execution";
        };
    }

    public boolean isActive() {
        return state == Thread.State.RUNNABLE;
    }

    public boolean isWaiting() {
        return state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING;
    }

    public boolean isBlocked() {
        return state == Thread.State.BLOCKED;
    }
}
