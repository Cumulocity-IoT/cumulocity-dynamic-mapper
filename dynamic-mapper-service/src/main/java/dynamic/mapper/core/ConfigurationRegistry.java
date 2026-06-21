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

package dynamic.mapper.core;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import org.apache.camel.CamelContext;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Source;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import com.cumulocity.microservice.context.credentials.MicroserviceCredentials;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.fasterxml.jackson.databind.ObjectMapper;
import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.connector.core.client.AConnectorClient;
import dynamic.mapper.connector.core.registry.ConnectorRegistry;
import dynamic.mapper.model.DeviceToClientMapRepresentation;
import dynamic.mapper.model.Direction;
import dynamic.mapper.model.MapperServiceRepresentation;
import dynamic.mapper.notification.NotificationSubscriber;
import dynamic.mapper.processor.outbound.CamelDispatcherOutbound;
import dynamic.mapper.service.ConnectorConfigurationService;
import dynamic.mapper.service.MappingService;
import dynamic.mapper.service.ServiceConfigurationService;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class ConfigurationRegistry implements IMapperConfiguration {

    @Getter
    private final TenantRegistry tenantRegistry;

    @Getter
    private final GraalVMContextService graalVMContextService;

    @Getter
    private C8YAgent c8yAgent;

    @Value("${APP.mqttServiceUrl}")
    @Getter
    String mqttServiceUrl;

    @Value("${C8Y_BASEURL_PULSAR:}")
    @Getter
    String mqttServicePulsarUrl;

    @Autowired
    public void setC8yAgent(@Lazy C8YAgent c8yAgent) {
        this.c8yAgent = c8yAgent;
    }

    @Getter
    private final ConnectorRegistry connectorRegistry;

    @Getter
    private NotificationSubscriber notificationSubscriber;

    @Autowired
    public void setNotificationSubscriber(NotificationSubscriber notificationSubscriber) {
        this.notificationSubscriber = notificationSubscriber;
    }

    @Getter
    private ObjectMapper objectMapper;

    @Autowired
    public void setObjectMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Getter
    private MappingService mappingService;

    @Autowired
    public void setMappingComponent(@Lazy MappingService mappingService) {
        this.mappingService = mappingService;
    }

    @Getter
    private ConnectorConfigurationService connectorConfigurationService;

    @Autowired
    public void setConnectorConfigurationService(
            @Lazy ConnectorConfigurationService connectorConfigurationService) {
        this.connectorConfigurationService = connectorConfigurationService;
    }

    @Getter
    public ServiceConfigurationService serviceConfigurationService;

    @Autowired
    public void setServiceConfigurationService(@Lazy ServiceConfigurationService serviceConfigurationService) {
        this.serviceConfigurationService = serviceConfigurationService;
    }

    @Getter
    @Setter
    private ExecutorService virtualThreadPool;

    // @Lazy breaks the ConfigurationRegistry <-> camelContext circular dependency: the Camel
    // context pulls in the RouteBuilder beans during its own creation, and those routes depend
    // (transitively, via their processors) back on ConfigurationRegistry. Injecting a lazy proxy
    // lets ConfigurationRegistry be constructed without forcing camelContext creation; the real
    // context is resolved on first use (when connector dispatchers are built at runtime). Without
    // this the cycle surfaces under lazy bean initialization (e.g. the test profile).
    private final CamelContext camelContext;

    public ConfigurationRegistry(
            TenantRegistry tenantRegistry,
            GraalVMContextService graalVMContextService,
            ConnectorRegistry connectorRegistry,
            @Qualifier("virtualThreadPool") ExecutorService virtualThreadPool,
            @Lazy CamelContext camelContext) {
        this.tenantRegistry = tenantRegistry;
        this.graalVMContextService = graalVMContextService;
        this.connectorRegistry = connectorRegistry;
        this.virtualThreadPool = virtualThreadPool;
        this.camelContext = camelContext;
    }

    public boolean isPulsarAvailable(String tenant) {
        if (mqttServicePulsarUrl == null || mqttServicePulsarUrl.trim().isEmpty()) {
            log.warn("{} - C8Y_BASEURL_PULSAR is not configured for Pulsar connector. Disabling MQTT Service.", tenant);
            return false;
        }
        return true;
    }

    public void initializeResources(String tenant) {
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
        tenantRegistry.initializeDeviceToClientMap(tenant, deviceToClientMapRepresentation);
        return deviceToClientMapRepresentation;
    }

    public MicroserviceCredentials getMicroserviceCredential(String tenant) {
        return tenantRegistry.getMicroserviceCredential(tenant);
    }

    public void createGraalsResources(String tenant, ServiceConfiguration serviceConfiguration) {
        graalVMContextService.createGraalsResources(tenant, serviceConfiguration);
    }

    public Engine getGraalEngine(String tenant) {
        return graalVMContextService.getGraalEngine(tenant);
    }

    public void updateGraalsSourceShared(String tenant, String code) {
        graalVMContextService.updateGraalsSourceShared(tenant, code);
    }

    public Source getGraalsSourceShared(String tenant) {
        return graalVMContextService.getGraalsSourceShared(tenant);
    }

    public void updateGraalsSourceSystem(String tenant, String code) {
        graalVMContextService.updateGraalsSourceSystem(tenant, code);
    }

    public Source getGraalsSourceSystem(String tenant) {
        return graalVMContextService.getGraalsSourceSystem(tenant);
    }

    /**
     * Pre-compiles mapping-specific JavaScript into the Engine's Source cache.
     * Call this after mappings are loaded so the first test for each existing
     * mapping hits the cache instead of paying the full parse+compile cost.
     *
     * @param tenant      the tenant identifier
     * @param sourceCodes map of source name (e.g. "onMessage_<id>.js") →
     *                    decoded+adapted JS code
     */
    public void warmupMappingCodes(String tenant, Map<String, String> sourceCodes) {
        graalVMContextService.warmupMappingCodes(tenant, sourceCodes);
    }

    public void removeGraalsResources(String tenant) {
        graalVMContextService.removeGraalsResources(tenant);
    }

    public ServiceConfiguration getServiceConfiguration(String tenant) {
        return tenantRegistry.getServiceConfiguration(tenant);
    }

    public void addServiceConfiguration(String tenant, ServiceConfiguration configuration) {
        tenantRegistry.addServiceConfiguration(tenant, configuration);
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
        return tenantRegistry.getDeviceToClientMapId(tenant);
    }

    public void addMicroserviceCredentials(String tenant, MicroserviceCredentials credentials) {
        tenantRegistry.addMicroserviceCredentials(tenant, credentials);
    }

    public void removeMicroserviceCredentials(String tenant) {
        tenantRegistry.removeMicroserviceCredentials(tenant);
    }

    // In ConfigurationRegistry
    public CamelContext getCamelContext() {
        return this.camelContext; // Assuming you have it stored
    }

    public void initializeOutboundMapping(String tenant, ServiceConfiguration serviceConfiguration,
            AConnectorClient connectorClient) {
        if (serviceConfiguration.getOutboundMappingEnabled()
                && connectorClient.supportedDirections().contains(Direction.OUTBOUND)) {
            CamelDispatcherOutbound dispatcherOutbound = new CamelDispatcherOutbound(
                    this, connectorClient);
            // Always register the dispatcher so a Notification 2.0 WebSocket is established
            // regardless of connector enabled state. This allows the Message Explorer to
            // capture outbound notifications even when connectors are disabled.
            // processNotification() guards actual mapping execution via isConnected().
            getNotificationSubscriber().addConnector(tenant,
                    connectorClient.getConnectorIdentifier(),
                    dispatcherOutbound);
        }
    }

    public HostAccess getHostAccess() {
        return graalVMContextService.getHostAccess();
    }

    public void addOrUpdateClientRelation(String tenant, String clientId, String deviceId) {
        tenantRegistry.addOrUpdateClientRelation(tenant, clientId, deviceId);
    }

    public void addOrUpdateClientRelations(String tenant, String clientId, List<String> deviceIds) {
        tenantRegistry.addOrUpdateClientRelations(tenant, clientId, deviceIds);
    }

    public void removeClientRelation(String tenant, String deviceId) {
        tenantRegistry.removeClientRelation(tenant, deviceId);
    }

    public void removeClientById(String tenant, String clientId) {
        tenantRegistry.removeClientById(tenant, clientId);
    }

    public void clearCacheDeviceToClient(String tenant) {
        tenantRegistry.clearCacheDeviceToClient(tenant);
    }

    public String resolveDeviceToClient(String tenant, String deviceId) {
        return tenantRegistry.resolveDeviceToClient(tenant, deviceId);
    }

    public Map<String, String> getAllClientRelations(String tenant) {
        return tenantRegistry.getAllClientRelations(tenant);
    }

    public List<String> getDevicesForClient(String tenant, String clientId) {
        return tenantRegistry.getDevicesForClient(tenant, clientId);
    }

    public List<String> getAllClients(String tenant) {
        return tenantRegistry.getAllClients(tenant);
    }

    public int getClientRelationCount(String tenant) {
        return tenantRegistry.getClientRelationCount(tenant);
    }

    public boolean hasClientRelation(String tenant, String deviceId) {
        return tenantRegistry.hasClientRelation(tenant, deviceId);
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
