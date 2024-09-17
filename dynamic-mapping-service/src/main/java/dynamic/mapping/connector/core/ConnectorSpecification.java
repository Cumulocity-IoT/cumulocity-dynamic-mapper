package dynamic.mapping.connector.core;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;

import dynamic.mapping.connector.core.client.ConnectorType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

import javax.validation.constraints.NotNull;
import java.util.Map;

@Slf4j
@Data
@ToString()
@AllArgsConstructor
public class ConnectorSpecification implements Cloneable {

	@NotNull
	@JsonSetter(nulls = Nulls.SKIP)
	public String name;

	@NotNull
	@JsonSetter(nulls = Nulls.SKIP)
	public String description;

	@NotNull
	@JsonSetter(nulls = Nulls.SKIP)
	public ConnectorType connectorType;

	@NotNull
	@JsonSetter(nulls = Nulls.SKIP)
	public Map<String, ConnectorProperty> properties;

	@NotNull
	@JsonSetter(nulls = Nulls.SKIP)
	public boolean supportsMessageContext;

	public boolean isPropertySensitive(String property) {
		try {
			ConnectorProperty propertyType = properties.get(property);
			if (propertyType != null) {
				return ConnectorPropertyType.SENSITIVE_STRING_PROPERTY == propertyType.type;
			} else {
				return false;
			}
		} catch (NullPointerException e) {
			log.error("NullPointerException occurred: ({}:{})",
					name,
					connectorType, e);
			return false;
		}
	}

	public Object clone() {
		try {
			return super.clone();
		} catch (CloneNotSupportedException e) {
			return null;
		}
	}
}
