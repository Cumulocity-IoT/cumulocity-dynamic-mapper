package mqtt.mapping.model;

import java.io.Serializable;
import java.util.ArrayList;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import mqtt.mapping.core.ServiceStatus;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class  MappingServiceRepresentation extends ManagedObjectRepresentation implements Serializable  {

  public static final String SERVICE_STATUS_FRAGMENT = "service_status";
  public static final String MAPPING_STATUS_FRAGMENT = "mapping_status";
  public static final String AGENT_ID = "MQTT_MAPPING_SERVICE";
  public static final String AGENT_NAME = "MQTT Mapping Service";

  // @JsonProperty("id")
  // private String id;

  // @JsonProperty("type")
  // private String type;

  // @JsonProperty(value = "name")
  // private String name;

  // @JsonProperty(value = "description")
  // private String description;

  @JsonProperty(value = MAPPING_STATUS_FRAGMENT)
  private ArrayList<MappingStatus> mappingStatus;

  @JsonProperty(value = SERVICE_STATUS_FRAGMENT)
  private ServiceStatus serviceStatus;

}
