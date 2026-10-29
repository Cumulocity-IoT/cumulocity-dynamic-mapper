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
import java.io.Serializable;
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
    private Boolean enabled;

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "A meaningful name of the connector", example = "MQTT Connector")
    @NotNull
    @JsonProperty("name")
    private String name;

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The properties of the connector used to establish a connection", example = "{ \"protocol\": \"mqtt://\", \"mqttHost\": \"mqtt.example.com\" , \"mqttPort\": 1883, \"clientId\": \"dynamic_mapper_client1\" }")
    @NotNull
    @JsonProperty("properties")
    private Map<String, Object> properties = new HashMap<>();

    /**
     * Legacy property names (key) and the names that replace them (value). Stored configurations and
     * imports may still carry the old names, so they are renamed whenever the properties are set.
     */
    private static final Map<String, String> LEGACY_PROPERTY_NAMES = Map.of(
            "useSelfSignedCertificate", "trustCustomCertificate",
            "fingerprintSelfSignedCertificate", "trustedCertificateFingerprint");

    public void setProperties(Map<String, Object> properties) {
        this.properties = properties;
        migrateLegacyProperties();
    }

    /**
     * Renames deprecated property names to their current names. A value under the new name wins over
     * one under the old name.
     */
    private void migrateLegacyProperties() {
        if (properties == null) {
            return;
        }
        LEGACY_PROPERTY_NAMES.forEach((legacy, current) -> {
            if (properties.containsKey(legacy)) {
                Object value = properties.remove(legacy);
                properties.putIfAbsent(current, value);
            }
        });
    }

    @Override
    public ConnectorConfiguration clone() {
        ConnectorConfiguration cloned = new ConnectorConfiguration();
        cloned.setIdentifier(this.identifier);
        cloned.setConnectorType(this.connectorType);
        cloned.setEnabled(this.enabled);
        cloned.setName(this.name);
        cloned.setProperties(new HashMap<>(this.properties));
        return cloned;
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
                if (p.defaultValue == null) {
                    properties.remove(prop.getKey());
                } else {
                    properties.put(prop.getKey(), p.defaultValue);
                }
            }
        });
    }

    /** Value that stands for a stored secret in responses; sending it back leaves the stored secret unchanged. */
    public static final String SENSITIVE_PLACEHOLDER = "****";

    private static boolean hasValue(Object value) {
        return value != null && !(value instanceof String string && string.isEmpty());
    }

    /**
     * Replaces every sensitive property that still carries {@link #SENSITIVE_PLACEHOLDER} by the value stored in the
     * original configuration, since the placeholder means "unchanged".
     *
     * @param original the stored configuration, may be {@code null} for a configuration that was never saved
     */
    public void restoreMaskedSecrets(ConnectorSpecification spec, ConnectorConfiguration original) {
        if (spec == null || properties == null) {
            return;
        }
        for (String property : new java.util.ArrayList<>(properties.keySet())) {
            if (spec.isPropertySensitive(property) && SENSITIVE_PLACEHOLDER.equals(properties.get(property))) {
                Object stored = original == null || original.getProperties() == null ? null
                        : original.getProperties().get(property);
                if (stored == null) {
                    properties.remove(property);
                } else {
                    properties.put(property, stored);
                }
            }
        }
    }

    public ConnectorConfiguration getCleanedConfig(ConnectorSpecification connectorSpecification) {
        ConnectorConfiguration clonedConfig = this.clone();
        for (String property : clonedConfig.getProperties().keySet()) {
            // An unset secret stays unset, so a masked value always means "a value is stored".
            if (connectorSpecification != null && connectorSpecification.isPropertySensitive(property)
                    && hasValue(clonedConfig.getProperties().get(property))) {
                clonedConfig.getProperties().replace(property, SENSITIVE_PLACEHOLDER);
            }
        }
        return clonedConfig;
    }
}