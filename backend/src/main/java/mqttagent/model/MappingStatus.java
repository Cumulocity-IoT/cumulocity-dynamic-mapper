package mqttagent.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.io.Serializable;

import javax.validation.constraints.NotNull;

@Data
@Getter
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = {})
public class MappingStatus implements Serializable {

  @NotNull
  public long id;

  @NotNull
  public long messagesReceived;

  @NotNull
  public long errors;

  @NotNull
  public long snoopedTemplatesActive;

  @NotNull
  public long snoopedTemplatesTotal;
  
  @Override
  public boolean equals(Object m) {
    return (m instanceof MappingStatus) && id == ((MappingStatus) m).id;
  }
}