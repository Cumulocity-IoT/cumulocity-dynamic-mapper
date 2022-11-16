package mqtt.mapping.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.io.Serializable;

import javax.validation.constraints.NotNull;


@Getter
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = {})
public class MappingStatus implements Serializable {

  @NotNull
  public long id;

  @NotNull
  public String ident;

  @NotNull
  public String subscriptionTopic;

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

public void reset() {
  messagesReceived = 0;
  errors = 0;
  snoopedTemplatesActive = 0;
  snoopedTemplatesTotal = 0;
}
}