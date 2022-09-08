package mqttagent.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.io.Serializable;
import java.util.ArrayList;

import javax.validation.constraints.NotNull;

@Data
@Getter
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = { "id", "topic", "targetAPI", "source", "target", "active", "tested", "createNoExistingDevice",
    "qos", "substitutions", "mapDeviceIdentifier", "externalIdType", "snoopTemplates", "snoopedTemplates",
    "lastUpdate" })
public class MQTTMapping implements Serializable {

  @NotNull
  public long id;

  @NotNull
  public String topic;

  @NotNull
  public String templateTopic;

  @NotNull
  public long indexDeviceIdentifierInTemplateTopic;

  @NotNull
  public String targetAPI;

  @NotNull
  public String source;

  @NotNull
  public String target;

  @NotNull
  public boolean active;

  @NotNull
  public boolean tested;

  @NotNull
  public boolean createNoExistingDevice;

  @NotNull
  public long qos;

  @NotNull
  public MQTTMappingSubstitution[] substitutions;

  @NotNull
  public boolean mapDeviceIdentifier;

  @NotNull
  public String externalIdType;

  @NotNull
  public SnoopStatus snoopTemplates;

  @NotNull
  public ArrayList<String> snoopedTemplates;

  @NotNull
  public long lastUpdate;

  @Override
  public boolean equals(Object m) {
    return (m instanceof MQTTMapping) && id == ((MQTTMapping) m).id;
  }

  public void copyFrom(MQTTMapping mapping) {
    this.topic = mapping.topic;
    this.templateTopic = mapping.templateTopic;
    this.indexDeviceIdentifierInTemplateTopic = mapping.indexDeviceIdentifierInTemplateTopic;
    this.targetAPI = mapping.targetAPI;
    this.source = mapping.source;
    this.target = mapping.target;
    this.active = mapping.active;
    this.tested = mapping.tested;
    this.createNoExistingDevice = mapping.createNoExistingDevice;
    this.qos = mapping.qos;
    this.substitutions = mapping.substitutions;
    this.snoopTemplates = mapping.snoopTemplates;
    this.snoopedTemplates = mapping.snoopedTemplates;
  }
}

/**
 * export interface MQTTMapping {
 * id: number,
 * topic: string,
 * targetAPI: string,
 * source: string,
 * target: string,
 * lastUpdate: number
 * }
 */