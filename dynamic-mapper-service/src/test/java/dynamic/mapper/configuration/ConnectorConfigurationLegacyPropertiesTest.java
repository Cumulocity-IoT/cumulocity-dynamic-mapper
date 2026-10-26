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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConnectorConfigurationLegacyPropertiesTest {

    @Test
    void legacyNamesAreRenamedOnSetProperties() {
        Map<String, Object> props = new HashMap<>();
        props.put("useSelfSignedCertificate", true);
        props.put("fingerprintSelfSignedCertificate", "AA:BB");
        ConnectorConfiguration config = new ConnectorConfiguration();
        config.setProperties(props);

        assertEquals(true, config.getProperties().get("trustCustomCertificate"));
        assertEquals("AA:BB", config.getProperties().get("trustedCertificateFingerprint"));
        assertFalse(config.getProperties().containsKey("useSelfSignedCertificate"));
        assertFalse(config.getProperties().containsKey("fingerprintSelfSignedCertificate"));
    }

    @Test
    void newNameWinsOverLegacyName() {
        Map<String, Object> props = new HashMap<>();
        props.put("useSelfSignedCertificate", true);
        props.put("trustCustomCertificate", false);
        ConnectorConfiguration config = new ConnectorConfiguration();
        config.setProperties(props);

        assertEquals(false, config.getProperties().get("trustCustomCertificate"));
        assertFalse(config.getProperties().containsKey("useSelfSignedCertificate"));
    }

    @Test
    void legacyNamesAreRenamedWhenDeserializing() throws Exception {
        String json = "{\"identifier\":\"c1\",\"connectorType\":\"KAFKA\",\"enabled\":true,\"name\":\"n\","
                + "\"properties\":{\"useSelfSignedCertificate\":true,\"fingerprintSelfSignedCertificate\":\"AA:BB\"}}";
        ConnectorConfiguration config = new ObjectMapper().readValue(json, ConnectorConfiguration.class);

        assertEquals(true, config.getProperties().get("trustCustomCertificate"));
        assertEquals("AA:BB", config.getProperties().get("trustedCertificateFingerprint"));
        assertFalse(config.getProperties().containsKey("useSelfSignedCertificate"));
    }
}
