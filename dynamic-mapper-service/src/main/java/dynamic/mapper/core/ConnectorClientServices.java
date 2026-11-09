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

package dynamic.mapper.core;

import org.springframework.stereotype.Component;

import dynamic.mapper.configuration.ConnectorConfigurationService;
import dynamic.mapper.configuration.ServiceConfigurationService;
import dynamic.mapper.mapping.MappingService;
import lombok.Getter;

/**
 * The Spring services a connector client needs besides {@link ServiceRegistry}.
 * <p>
 * {@link ConnectorClientFactory} passes this bean to every client it creates. They are kept out of
 * {@code ServiceRegistry} because {@code ServiceRegistry} is injected into many beans these services
 * depend on, which would close dependency cycles that only {@code @Lazy} could break.
 */
@Getter
@Component
public class ConnectorClientServices {

    private final MappingService mappingService;
    private final ServiceConfigurationService serviceConfigurationService;
    private final ConnectorConfigurationService connectorConfigurationService;
    private final ConnectorStatusEventService connectorStatusEventService;

    public ConnectorClientServices(MappingService mappingService,
            ServiceConfigurationService serviceConfigurationService,
            ConnectorConfigurationService connectorConfigurationService,
            ConnectorStatusEventService connectorStatusEventService) {
        this.mappingService = mappingService;
        this.serviceConfigurationService = serviceConfigurationService;
        this.connectorConfigurationService = connectorConfigurationService;
        this.connectorStatusEventService = connectorStatusEventService;
    }
}
