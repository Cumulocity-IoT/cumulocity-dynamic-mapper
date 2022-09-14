package mqttagent.websocket;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;

import javax.validation.constraints.NotNull;

@Data
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class StatusMessage implements Serializable {
    @NotNull
    public long count;
}
