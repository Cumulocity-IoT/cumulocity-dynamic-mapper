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

  public static MappingStatus UNSPECIFIED_MAPPING_STATUS;
  public static String IDENT_UNSPECIFIED_MAPPING = "UNSPECIFIED";

  static {
    UNSPECIFIED_MAPPING_STATUS = new MappingStatus(IDENT_UNSPECIFIED_MAPPING, IDENT_UNSPECIFIED_MAPPING, "#", 0, 0, 0,
        0);
  }

  @NotNull
  public String id;

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