/*
 * Copyright (c) 2025 Cumulocity GmbH.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *  @authors Christof Strack, Stefan Witschel
 *
 */

package dynamic.mapper.configuration;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;

import dynamic.mapper.connector.core.ConnectorProperty;
import dynamic.mapper.connector.core.ConnectorSpecification;
import dynamic.mapper.connector.core.client.ConnectorType;
import lombok.Data;
import lombok.ToString;

import jakarta.validation.constraints.NotNull;
import java.io.*;
import java.util.HashMap;
import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;

@Data
@ToString()
public class ConnectorConfiguration implements Cloneable, Serializable {

    public ConnectorConfiguration() {
        super();
    }

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "A unique identifier to identify the connector", example = "o5rhgj4g")
    @NotNull
    @JsonSetter(nulls = Nulls.SKIP)
    @JsonProperty("identifier")
    private String identifier;

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The type of a connector. Must be one of MQTT, CUMULOCITY_MQTT_SERVICE, KAFKA, HTTP, WEB_HOOK", example = "MQTT")
    @NotNull
    @JsonSetter(nulls = Nulls.SKIP)
    @JsonProperty("connectorType")
    private ConnectorType connectorType;

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Flag if the connector is enabled or not", example = "false")
    @NotNull
    @JsonProperty("enabled")
    private boolean enabled;

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "A meaningful name of the connector", example = "MQTT Connector")
    @NotNull
    @JsonProperty("name")
    private String name;

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The properties of the connector used to establish a connection", example = "{ \"protocol\": \"mqtt://\", \"mqttHost\": \"mqtt.example.com\" , \"mqttPort\": 1883, \"clientId\": \"dynamic_mapper_client1\" }")
    @NotNull
    @JsonProperty("properties")
    private Map<String, Object> properties = new HashMap<>();

    public Object clone() {
        Object result = null;
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(bos);
            oos.writeObject(this);
            oos.flush();
            oos.close();
            bos.close();
            byte[] byteData = bos.toByteArray();
            ByteArrayInputStream bais = new ByteArrayInputStream(byteData);
            result = new ObjectInputStream(bais).readObject();
        } catch (Exception e) {
            return null;
        }
        return result;
    }

    /**
     * Copy the properties that are readonly from the specification to the
     * configuration
     * 
     * @param spec the connectorSpecification to use as a template and copy
     *             predefined from to the connectorConfiguration
     */
    public void copyPredefinedValues(ConnectorSpecification spec) {
        spec.getProperties().entrySet().forEach(prop -> {
            ConnectorProperty p = prop.getValue();
            if (p.readonly) {
                properties.put(prop.getKey(), p.defaultValue);
            }
        });
    }

    public ConnectorConfiguration getCleanedConfig(ConnectorSpecification connectorSpecification) {
        ConnectorConfiguration clonedConfig = (ConnectorConfiguration) this.clone();
        for (String property : clonedConfig.getProperties().keySet()) {
            if (connectorSpecification != null && connectorSpecification.isPropertySensitive(property)) {
                clonedConfig.getProperties().replace(property, "****");
            }
        }
        return clonedConfig;
    }
}