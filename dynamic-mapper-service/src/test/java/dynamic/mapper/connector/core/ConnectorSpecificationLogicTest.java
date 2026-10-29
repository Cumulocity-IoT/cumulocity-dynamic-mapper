/*
 * Copyright (c) 2022-2025 Cumulocity GmbH.
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

package dynamic.mapper.connector.core;

import dynamic.mapper.configuration.ConnectorConfiguration;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectorSpecificationLogicTest {

    /** protocol (default mqtt://) -> trust (only for mqtts://) -> fingerprint (only while trust is on); user -> password. */
    private static ConnectorSpecification spec() {
        Map<String, ConnectorProperty> properties = new LinkedHashMap<>();
        properties.put("protocol", ConnectorPropertyBuilder.requiredOption().options("mqtt://", "mqtts://")
                .defaultValue("mqtt://").build());
        properties.put("trust", ConnectorPropertyBuilder.optionalBoolean().defaultValue(false)
                .condition("protocol", "mqtts://").build());
        properties.put("fingerprint", ConnectorPropertyBuilder.requiredString()
                .condition("trust", "true").build());
        properties.put("host", ConnectorPropertyBuilder.requiredString().build());
        properties.put("user", ConnectorPropertyBuilder.optionalString().build());
        properties.put("password", ConnectorPropertyBuilder.optionalSensitive().condition("user", "*").build());
        ConnectorSpecification spec = new ConnectorSpecification();
        spec.setProperties(properties);
        return spec;
    }

    @Test
    void aPropertyWithoutConditionIsAlwaysActive() {
        assertTrue(spec().isPropertyActive("host", Map.of()));
    }

    @Test
    void theConditionFallsBackToTheDefaultOfTheParent() {
        assertFalse(spec().isPropertyActive("trust", Map.of()));
        assertTrue(spec().isPropertyActive("trust", Map.of("protocol", "mqtts://")));
    }

    @Test
    void aPropertyBehindAnInactiveParentIsInactiveEvenWhenTheParentValueMatches() {
        // trust is still true from before, but protocol went back to mqtt://, which hides trust
        Map<String, Object> configured = Map.of("protocol", "mqtt://", "trust", true);

        assertFalse(spec().isPropertyActive("fingerprint", configured));
    }

    @Test
    void booleansMatchTheirStringForm() {
        Map<String, Object> configured = Map.of("protocol", "mqtts://", "trust", true);

        assertTrue(spec().isPropertyActive("fingerprint", configured));
    }

    @Test
    void theWildcardMeansAnyNonEmptyValue() {
        assertFalse(spec().isPropertyActive("password", Map.of()));
        assertFalse(spec().isPropertyActive("password", Map.of("user", "  ")));
        assertTrue(spec().isPropertyActive("password", Map.of("user", "admin")));
    }

    @Test
    void requiredPropertiesThatApplyAndHaveNoValueAreReported() {
        // protocol has a default; fingerprint does not apply; only host is missing
        assertEquals(List.of("host"), spec().findMissingRequired(new HashMap<>()));
    }

    @Test
    void aRequiredPropertyBecomesMissingOnlyWhileItsConditionHolds() {
        Map<String, Object> configured = new HashMap<>(Map.of("host", "h", "protocol", "mqtts://", "trust", true));

        assertEquals(List.of("fingerprint"), spec().findMissingRequired(configured));
        configured.put("fingerprint", "ab:cd");
        assertTrue(spec().findMissingRequired(configured).isEmpty());
    }

    @Test
    void aBlankStringCountsAsNoValue() {
        assertEquals(List.of("host"), spec().findMissingRequired(new HashMap<>(Map.of("host", " "))));
    }

    @Test
    void inactivePropertiesAreRemovedTogetherWithTheOnesBehindThem() {
        Map<String, Object> configured = new HashMap<>(Map.of(
                "protocol", "mqtt://", "trust", true, "fingerprint", "ab:cd", "host", "h", "unknown", "x"));

        List<String> removed = spec().removeInactiveProperties(configured);

        assertEquals(List.of("fingerprint", "trust"), removed.stream().sorted().toList());
        assertEquals(Map.of("protocol", "mqtt://", "host", "h", "unknown", "x"), configured);
    }

    @Test
    void cloningAnOptionPropertyKeepsTheOrderOfItsOptions() {
        ConnectorProperty property = ConnectorPropertyBuilder.requiredOption()
                .options("z", "y", "x", "w", "v", "u", "t", "s").build();

        assertEquals(List.of("z", "y", "x", "w", "v", "u", "t", "s"), List.copyOf(property.clone().getOptions().keySet()));
    }

    @Test
    void aSecretThatIsNotSetStaysUnsetInTheMaskedCopy() {
        ConnectorConfiguration configuration = new ConnectorConfiguration();
        configuration.setProperties(new HashMap<>());
        configuration.getProperties().put("user", "admin");
        configuration.getProperties().put("password", "s3cret");
        configuration.getProperties().put("token", null);
        Map<String, ConnectorProperty> properties = new LinkedHashMap<>(spec().getProperties());
        properties.put("token", ConnectorPropertyBuilder.optionalSensitive().build());
        ConnectorSpecification spec = spec();
        spec.setProperties(properties);

        ConnectorConfiguration masked = configuration.getCleanedConfig(spec);

        assertEquals("****", masked.getProperties().get("password"));
        assertNull(masked.getProperties().get("token"));
        assertEquals("admin", masked.getProperties().get("user"));
    }

    @Test
    void aMaskedSecretIsRestoredFromTheStoredConfiguration() {
        ConnectorConfiguration stored = new ConnectorConfiguration();
        stored.setProperties(new HashMap<>(Map.of("password", "s3cret")));
        ConnectorConfiguration incoming = new ConnectorConfiguration();
        incoming.setProperties(new HashMap<>(Map.of("password", "****", "user", "admin")));

        incoming.restoreMaskedSecrets(spec(), stored);

        assertEquals("s3cret", incoming.getProperties().get("password"));
        assertEquals("admin", incoming.getProperties().get("user"));
    }

    @Test
    void aMaskedSecretWithoutStoredValueIsDroppedInsteadOfKeepingThePlaceholder() {
        ConnectorConfiguration incoming = new ConnectorConfiguration();
        incoming.setProperties(new HashMap<>(Map.of("password", "****")));

        incoming.restoreMaskedSecrets(spec(), null);

        assertFalse(incoming.getProperties().containsKey("password"));
    }

    @Test
    void aSecretSentAsNullDoesNotFailTheRestore() {
        ConnectorConfiguration incoming = new ConnectorConfiguration();
        incoming.setProperties(new HashMap<>());
        incoming.getProperties().put("password", null);

        incoming.restoreMaskedSecrets(spec(), new ConnectorConfiguration());

        assertNull(incoming.getProperties().get("password"));
    }

    @Test
    void predefinedValuesWithoutDefaultRemoveTheKeyInsteadOfStoringNull() {
        Map<String, ConnectorProperty> properties = new LinkedHashMap<>();
        properties.put("fixed", ConnectorPropertyBuilder.optionalString().readonly(true).defaultValue("v").build());
        properties.put("empty", ConnectorPropertyBuilder.optionalString().readonly(true).build());
        ConnectorSpecification spec = new ConnectorSpecification();
        spec.setProperties(properties);
        ConnectorConfiguration configuration = new ConnectorConfiguration();
        configuration.setProperties(new HashMap<>(Map.of("empty", "stale", "fixed", "other")));

        configuration.copyPredefinedValues(spec);

        assertEquals("v", configuration.getProperties().get("fixed"));
        assertFalse(configuration.getProperties().containsKey("empty"));
    }
}
