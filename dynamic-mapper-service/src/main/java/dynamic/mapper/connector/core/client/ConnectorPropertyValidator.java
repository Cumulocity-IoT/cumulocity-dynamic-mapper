/*
 * Copyright (c) 2022-2026 Cumulocity GmbH.
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

package dynamic.mapper.connector.core.client;

import java.util.ArrayList;
import java.util.List;

import dynamic.mapper.configuration.ConnectorConfiguration;
import lombok.extern.slf4j.Slf4j;

/**
 * Stateless property checks for connector configurations, extracted out of {@link AConnectorClient}.
 */
@Slf4j
final class ConnectorPropertyValidator {

    private ConnectorPropertyValidator() {
    }

    /**
     * @param tenant             tenant used as log prefix
     * @param configuration      the connector configuration to validate
     * @param requiredProperties names of properties that must be present and, for strings, non-blank
     * @return true if all required properties are present and non-empty
     */
    static boolean validateRequiredProperties(String tenant, ConnectorConfiguration configuration,
            String... requiredProperties) {
        if (requiredProperties == null || requiredProperties.length == 0) {
            return true;
        }

        List<String> missingProperties = new ArrayList<>();
        for (String propertyName : requiredProperties) {
            Object value = configuration.getProperties().get(propertyName);
            if (value == null || (value instanceof String && ((String) value).trim().isEmpty())) {
                missingProperties.add(propertyName);
            }
        }

        if (!missingProperties.isEmpty()) {
            log.warn("{} - Missing required configuration properties: {}", tenant, missingProperties);
            return false;
        }

        return true;
    }
}
