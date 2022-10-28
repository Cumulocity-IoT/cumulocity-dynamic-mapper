package mqtt.mapping.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import mqtt.mapping.processor.MappingType;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.validation.constraints.NotNull;

@Getter
@Setter
@NoArgsConstructor
@ToString(exclude = { "source", "target", "snoopedTemplates" })
public class Mapping implements Serializable {

  public static int SNOOP_TEMPLATES_MAX = 5;
  public static String SPLIT_TOPIC_REGEXP = "((?<=/)|(?=/))";

  @NotNull
  public long id;

  @NotNull
  public String ident;

  @NotNull
  public String subscriptionTopic;

  @NotNull
  public String templateTopic;

  @NotNull
  public String templateTopicSample;

  @NotNull
  public API targetAPI;

  @NotNull
  public String source;

  @NotNull
  public String target;

  @NotNull
  public boolean active;

  @NotNull
  public boolean tested;

  @NotNull
  public QOS qos;

  @NotNull
  public MappingSubstitution[] substitutions;

  @NotNull
  public boolean mapDeviceIdentifier;

  @NotNull
  public boolean createNonExistingDevice;

  @NotNull
  public boolean updateExistingDevice;

  @NotNull
  public String externalIdType;

  @NotNull
  public SnoopStatus snoopStatus;

  @NotNull
  public ArrayList<String> snoopedTemplates;

  @NotNull
  public MappingType mappingType;

  @NotNull
  public long lastUpdate;

  @Override
  public boolean equals(Object m) {
    return (m instanceof Mapping) && id == ((Mapping) m).id;
  }

  public void copyFrom(Mapping mapping) {
    this.subscriptionTopic = mapping.subscriptionTopic;
    this.templateTopic = mapping.templateTopic;
    this.targetAPI = mapping.targetAPI;
    this.source = mapping.source;
    this.target = mapping.target;
    this.active = mapping.active;
    this.tested = mapping.tested;
    this.qos = mapping.qos;
    this.substitutions = mapping.substitutions;
    this.mapDeviceIdentifier = mapping.mapDeviceIdentifier;
    this.externalIdType = mapping.externalIdType;
    this.snoopStatus = mapping.snoopStatus;
    this.snoopedTemplates = mapping.snoopedTemplates;
  }

  public void addSnoopedTemplate(String payloadMessage) {
    snoopedTemplates.add(payloadMessage);
    if (snoopedTemplates.size() >= SNOOP_TEMPLATES_MAX) {
      // remove oldest payload
      snoopedTemplates.remove(0);
    } else {
      snoopStatus = SnoopStatus.STARTED;
    }
  }

  public void sortSubstitutions() {
    MappingSubstitution[] sortedSubstitutions = Arrays.stream(substitutions).sorted(
        (s1, s2) -> -(Boolean.valueOf(s1.isDefinesIdentifier()).compareTo(Boolean.valueOf(s2.isDefinesIdentifier()))))
        .toArray(size -> new MappingSubstitution[size]);
    substitutions = sortedSubstitutions;
  }

  public static String[] splitTopicIncludingSeparatorAsArray(String topic) {
    topic = topic.trim().replaceAll("(\\/{1,}$)|(^\\/{1,})", "/");
    return topic.split(SPLIT_TOPIC_REGEXP);
  }

  public static List<String> splitTopicIncludingSeparatorAsList(String topic) {
    return new ArrayList<String>(
        Arrays.asList(Mapping.splitTopicIncludingSeparatorAsArray(topic)));
  }

  public static String[] splitTopicExcludingSeparatorAsArray(String topic) {
    topic = topic.trim().replaceAll("(\\/{1,}$)|(^\\/{1,})", "");
    return topic.split("\\/");
  }

  public static List<String> splitTopicExcludingSeparatorAsList(String topic) {
    return new ArrayList<String>(
        Arrays.asList(Mapping.splitTopicExcludingSeparatorAsArray(topic)));
  }
}
