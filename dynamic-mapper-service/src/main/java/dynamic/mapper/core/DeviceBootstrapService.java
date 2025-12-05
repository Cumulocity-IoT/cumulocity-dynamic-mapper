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

package dynamic.mapper.core;

import java.util.ArrayList;
import java.util.HashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.cumulocity.model.Agent;
import com.cumulocity.model.ID;
import com.cumulocity.rest.representation.identity.ExternalIDRepresentation;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;

import dynamic.mapper.core.facade.IdentityFacade;
import dynamic.mapper.core.facade.InventoryFacade;
import dynamic.mapper.model.DeviceToClientMapRepresentation;
import dynamic.mapper.model.MapperServiceRepresentation;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class DeviceBootstrapService {

    @Autowired
    private InventoryFacade inventoryApi;

    @Autowired
    private IdentityFacade identityApi;

    @Value("${application.version}")
    private String version;

    public ManagedObjectRepresentation initializeMapperServiceRepresentation(String tenant, 
            IdentityResolver identityResolver) {
        ExternalIDRepresentation mapperServiceIdRepresentation = identityResolver.resolveExternalId2GlobalId(
                tenant, new ID(null, MapperServiceRepresentation.AGENT_ID), false);

        ManagedObjectRepresentation amo = new ManagedObjectRepresentation();

        if (mapperServiceIdRepresentation != null) {
            amo = inventoryApi.get(mapperServiceIdRepresentation.getManagedObject().getId(), false);
            log.info("{} - Agent with external ID [{}] already exists, sourceId: {}", tenant,
                    MapperServiceRepresentation.AGENT_ID, amo.getId().getValue());
        } else {
            amo = createMapperServiceAgent(tenant);
        }
        return amo;
    }

    private ManagedObjectRepresentation createMapperServiceAgent(String tenant) {
        ManagedObjectRepresentation amo = new ManagedObjectRepresentation();
        amo.setName(MapperServiceRepresentation.AGENT_NAME);
        amo.setType(MapperServiceRepresentation.AGENT_TYPE);
        amo.set(new Agent());
        
        HashMap<String, String> agentFragments = new HashMap<>();
        agentFragments.put("name", "Dynamic Mapper");
        agentFragments.put("version", version);
        agentFragments.put("url", "https://github.com/Cumulocity-IoT/cumulocity-dynamic-mapper");
        agentFragments.put("maintainer", "Open-Source");
        amo.set(agentFragments, "c8y_Agent");
        
        amo.setProperty(MapperServiceRepresentation.MAPPING_FRAGMENT, new ArrayList<>());
        amo = inventoryApi.create(amo, false);
        
        log.info("{} - Agent has been created with ID {}", tenant, amo.getId());
        
        ExternalIDRepresentation externalAgentId = identityApi.create(amo,
                new ID("c8y_Serial", MapperServiceRepresentation.AGENT_ID), false);
        log.debug("{} - ExternalId created: {}", tenant, externalAgentId.getExternalId());
        
        return amo;
    }

    public ManagedObjectRepresentation initializeDeviceToClientMapRepresentation(String tenant,
            IdentityResolver identityResolver) {
        ExternalIDRepresentation deviceToClientMapRepresentation = identityResolver.resolveExternalId2GlobalId(
                tenant, new ID(null, DeviceToClientMapRepresentation.DEVICE_TO_CLIENT_MAP_ID), false);

        ManagedObjectRepresentation amo = new ManagedObjectRepresentation();

        if (deviceToClientMapRepresentation != null) {
            amo = inventoryApi.get(deviceToClientMapRepresentation.getManagedObject().getId(), false);
            log.info("{} - Dynamic Mapper Device To Client Map with external ID [{}] already exists, sourceId: {}",
                    tenant, DeviceToClientMapRepresentation.DEVICE_TO_CLIENT_MAP_ID, amo.getId().getValue());
        } else {
            amo = createDeviceToClientMap(tenant);
        }
        return amo;
    }

    private ManagedObjectRepresentation createDeviceToClientMap(String tenant) {
        ManagedObjectRepresentation amo = new ManagedObjectRepresentation();
        amo.setName(DeviceToClientMapRepresentation.DEVICE_TO_CLIENT_MAP_NAME);
        amo.setType(DeviceToClientMapRepresentation.DEVICE_TO_CLIENT_MAP_TYPE);
        amo.setProperty(DeviceToClientMapRepresentation.DEVICE_TO_CLIENT_MAP_FRAGMENT, new HashMap<>());
        
        amo = inventoryApi.create(amo, false);
        log.info("{} - Dynamic Mapper Device To Client Map has been created with ID {}", tenant, amo.getId());
        
        ExternalIDRepresentation externalAgentId = identityApi.create(amo,
                new ID("c8y_Serial", DeviceToClientMapRepresentation.DEVICE_TO_CLIENT_MAP_ID), false);
        log.debug("{} - ExternalId created: {}", tenant, externalAgentId.getExternalId());
        
        return amo;
    }
}