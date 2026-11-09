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

import java.util.concurrent.ExecutorService;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.cumulocity.microservice.context.credentials.MicroserviceCredentials;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.fasterxml.jackson.databind.ObjectMapper;
import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.connector.core.registry.ConnectorRegistry;
import dynamic.mapper.model.device.DeviceToClientMapRepresentation;
import dynamic.mapper.model.MapperServiceRepresentation;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;

@Slf4j
@Component
@Order(TenantScoped.ORDER_REGISTRY)
public class ServiceRegistry implements TenantScoped {

    @Getter
    private final TenantRegistry tenantRegistry;
    private final DeviceToClientRegistry deviceToClientRegistry;

    @Getter
    private final GraalVMContextService graalVMContextService;

    @Getter
    private final C8YAgent c8yAgent;

    @Getter
    private final CacheManager cacheManager;

    @Value("${APP.mqttServiceUrl}")
    @Getter
    String mqttServiceUrl;

    @Value("${C8Y_BASEURL_PULSAR:}")
    @Getter
    String mqttServicePulsarUrl;

    @Getter
    private final ConnectorRegistry connectorRegistry;

    @Getter
    private final ObjectMapper objectMapper;

    @Getter
    private final ExecutorService virtualThreadPool;

    public ServiceRegistry(
            TenantRegistry tenantRegistry,
            DeviceToClientRegistry deviceToClientRegistry,
            GraalVMContextService graalVMContextService,
            ConnectorRegistry connectorRegistry,
            ObjectMapper objectMapper,
            @Qualifier("virtualThreadPool") ExecutorService virtualThreadPool,
            C8YAgent c8yAgent,
            CacheManager cacheManager) {
        this.tenantRegistry = tenantRegistry;
        this.deviceToClientRegistry = deviceToClientRegistry;
        this.graalVMContextService = graalVMContextService;
        this.connectorRegistry = connectorRegistry;
        this.objectMapper = objectMapper;
        this.virtualThreadPool = virtualThreadPool;
        this.c8yAgent = c8yAgent;
        this.cacheManager = cacheManager;
    }

    public boolean isPulsarAvailable(String tenant) {
        if (mqttServicePulsarUrl == null || mqttServicePulsarUrl.trim().isEmpty()) {
            log.warn("{} - C8Y_BASEURL_PULSAR is not configured for Pulsar connector. Disabling MQTT Service.", tenant);
            return false;
        }
        return true;
    }

    @Override
    public void initializeTenant(TenantInitContext ctx) {
        String tenant = ctx.tenant();
        addMicroserviceCredentials(tenant, ctx.credentials());
        addServiceConfiguration(tenant, ctx.serviceConfiguration());
        storeMapperServiceRepresentation(tenant, ctx.mapperServiceMo());
        if (ctx.deviceToClientMo() != null) {
            storeDeviceToClientMapRepresentation(tenant, ctx.deviceToClientMo());
        }
    }

    public MapperServiceRepresentation storeMapperServiceRepresentation(String tenant,
            ManagedObjectRepresentation mor) {
        MapperServiceRepresentation mapperServiceRepresentation = objectMapper
                .convertValue(mor, MapperServiceRepresentation.class);
        addMapperServiceRepresentation(tenant, mapperServiceRepresentation);
        return mapperServiceRepresentation;
    }

    public DeviceToClientMapRepresentation storeDeviceToClientMapRepresentation(String tenant,
            ManagedObjectRepresentation mor) {
        DeviceToClientMapRepresentation deviceToClientMapRepresentation = objectMapper
                .convertValue(mor, DeviceToClientMapRepresentation.class);
        deviceToClientRegistry.initializeDeviceToClientMap(tenant, deviceToClientMapRepresentation);
        return deviceToClientMapRepresentation;
    }

    public MicroserviceCredentials getMicroserviceCredential(String tenant) {
        return tenantRegistry.getMicroserviceCredential(tenant);
    }

    public ServiceConfiguration getServiceConfiguration(String tenant) {
        return tenantRegistry.getServiceConfiguration(tenant);
    }

    public void addServiceConfiguration(String tenant, ServiceConfiguration configuration) {
        tenantRegistry.addServiceConfiguration(tenant, configuration);
    }

    /** Last step: credentials and configuration may still be read while the other beans are removed. */
    @Override
    public void removeTenant(String tenant) {
        TenantScoped.removeAll(tenant, this,
                () -> clearExternalIdCache(tenant), // keyed "tenant|…"; would grow across tenant churn
                () -> removeDeviceToClientState(tenant),
                () -> removeMapperServiceRepresentation(tenant),
                () -> removeServiceConfiguration(tenant),
                () -> removeMicroserviceCredentials(tenant));
    }

    public void removeDeviceToClientState(String tenant) {
        deviceToClientRegistry.removeDeviceToClientState(tenant);
    }

    public void removeServiceConfiguration(String tenant) {
        tenantRegistry.removeServiceConfiguration(tenant);
    }

    public void addMapperServiceRepresentation(String tenant,
            MapperServiceRepresentation mapperServiceRepresentation) {
        tenantRegistry.addMapperServiceRepresentation(tenant, mapperServiceRepresentation);
    }

    public MapperServiceRepresentation getMapperServiceRepresentation(String tenant) {
        return tenantRegistry.getMapperServiceRepresentation(tenant);
    }

    public void removeMapperServiceRepresentation(String tenant) {
        tenantRegistry.removeMapperServiceRepresentation(tenant);
    }

    public String getDeviceToClientMapId(String tenant) {
        return deviceToClientRegistry.getDeviceToClientMapId(tenant);
    }

    public void addMicroserviceCredentials(String tenant, MicroserviceCredentials credentials) {
        tenantRegistry.addMicroserviceCredentials(tenant, credentials);
    }

    public void removeMicroserviceCredentials(String tenant) {
        tenantRegistry.removeMicroserviceCredentials(tenant);
    }

    /**
     * Clear the external ID cache. Useful for testing or tenant cleanup.
     *
     * @param tenant the tenant identifier (optional; if null, clears all cache)
     */
    public void clearExternalIdCache(String tenant) {
        tenantRegistry.clearExternalIdCache(tenant);
    }

}
