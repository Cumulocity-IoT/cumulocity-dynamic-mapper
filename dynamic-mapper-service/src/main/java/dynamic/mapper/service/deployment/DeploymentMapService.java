package dynamic.mapper.service.deployment;

import com.cumulocity.model.idtype.GId;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.cumulocity.sdk.client.inventory.InventoryApi;
import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.model.MapperServiceRepresentation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages deployment mappings between mappings and connectors
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeploymentMapService {

    private final InventoryApi inventoryApi;
    private final ConfigurationRegistry configurationRegistry;
    private final MicroserviceSubscriptionsService subscriptionsService;

    // Structure: <Tenant, <MappingIdentifier, List<ConnectorIdentifier>>>
    private final Map<String, Map<String, List<String>>> deploymentMaps = new ConcurrentHashMap<>();

    /**
     * Initializes deployment map for a tenant
     */
    public void initializeTenantDeploymentMap(String tenant, boolean reset) {
        MapperServiceRepresentation serviceRep = configurationRegistry.getMapperServiceRepresentation(tenant);

        if (serviceRep.getDeploymentMap() != null && !reset) {
            log.debug("{} - Initializing deployment map with {} entries", 
                tenant, serviceRep.getDeploymentMap().size());
            deploymentMaps.put(tenant, new ConcurrentHashMap<>(serviceRep.getDeploymentMap()));
        } else {
            deploymentMaps.put(tenant, new ConcurrentHashMap<>());
        }
        persistDeploymentMap(tenant);
        log.info("{} - Deployment map initialized", tenant);
    }

    /**
     * Removes deployment map for a tenant
     */
    public void removeTenantDeploymentMap(String tenant) {
        deploymentMaps.remove(tenant);
        log.debug("{} - Deployment map removed", tenant);
    }

    /**
     * Gets the entire deployment map for a tenant
     */
    public Map<String, List<String>> getDeploymentMap(String tenant) {
        return new ConcurrentHashMap<>(getOrCreateDeploymentMap(tenant));
    }

    /**
     * Gets connectors deployed for a specific mapping
     */
    public List<String> getDeployedConnectors(String tenant, String mappingIdentifier) {
        return new ArrayList<>(
            getOrCreateDeploymentMap(tenant)
                .computeIfAbsent(mappingIdentifier, k -> new ArrayList<>())
        );
    }

    /**
     * Updates the deployment entry for a mapping
     */
    public void updateDeployment(String tenant, String mappingIdentifier, @Valid List<String> connectors) {
        Map<String, List<String>> tenantMap = getOrCreateDeploymentMap(tenant);
        tenantMap.put(mappingIdentifier, new ArrayList<>(connectors));
        
        log.debug("{} - Updated deployment for mapping {}: {} connectors", 
            tenant, mappingIdentifier, connectors.size());
        
        persistDeploymentMap(tenant);
    }

    /**
     * Removes a connector from all mappings
     * 
     * @return true if any changes were made
     */
    public boolean removeConnectorFromAllMappings(String tenant, String connectorIdentifier) {
        Map<String, List<String>> tenantMap = getOrCreateDeploymentMap(tenant);
        boolean modified = false;

        for (Map.Entry<String, List<String>> entry : tenantMap.entrySet()) {
            List<String> connectors = entry.getValue();
            if (connectors.remove(connectorIdentifier)) {
                modified = true;
                log.debug("{} - Removed connector {} from mapping {}", 
                    tenant, connectorIdentifier, entry.getKey());
            }
        }

        if (modified) {
            persistDeploymentMap(tenant);
        }

        return modified;
    }

    /**
     * Removes a mapping from the deployment map
     * 
     * @return true if the mapping was found and removed
     */
    public boolean removeMappingDeployment(String tenant, String mappingIdentifier) {
        Map<String, List<String>> tenantMap = getOrCreateDeploymentMap(tenant);
        List<String> removed = tenantMap.remove(mappingIdentifier);
        
        boolean wasRemoved = removed != null && !removed.isEmpty();
        
        if (wasRemoved) {
            log.debug("{} - Removed deployment for mapping {}", tenant, mappingIdentifier);
            persistDeploymentMap(tenant);
        }

        return wasRemoved;
    }

    /**
     * Adds a connector to a mapping's deployment
     */
    public void addConnectorToMapping(String tenant, String mappingIdentifier, String connectorIdentifier) {
        List<String> connectors = getOrCreateDeploymentMap(tenant)
            .computeIfAbsent(mappingIdentifier, k -> new ArrayList<>());
        
        if (!connectors.contains(connectorIdentifier)) {
            connectors.add(connectorIdentifier);
            log.debug("{} - Added connector {} to mapping {}", 
                tenant, connectorIdentifier, mappingIdentifier);
            persistDeploymentMap(tenant);
        }
    }

    /**
     * Removes a connector from a specific mapping
     */
    public boolean removeConnectorFromMapping(String tenant, String mappingIdentifier, String connectorIdentifier) {
        List<String> connectors = getOrCreateDeploymentMap(tenant).get(mappingIdentifier);
        
        if (connectors != null && connectors.remove(connectorIdentifier)) {
            log.debug("{} - Removed connector {} from mapping {}", 
                tenant, connectorIdentifier, mappingIdentifier);
            persistDeploymentMap(tenant);
            return true;
        }
        
        return false;
    }

    /**
     * Checks if a connector is deployed for a mapping
     */
    public boolean isConnectorDeployed(String tenant, String mappingIdentifier, String connectorIdentifier) {
        List<String> connectors = getOrCreateDeploymentMap(tenant).get(mappingIdentifier);
        return connectors != null && connectors.contains(connectorIdentifier);
    }

    // ========== Private Helper Methods ==========

    private Map<String, List<String>> getOrCreateDeploymentMap(String tenant) {
        return deploymentMaps.computeIfAbsent(tenant, k -> new ConcurrentHashMap<>());
    }

    private void persistDeploymentMap(String tenant) {
        subscriptionsService.runForTenant(tenant, () -> {
            MapperServiceRepresentation serviceRep = configurationRegistry.getMapperServiceRepresentation(tenant);
            
            Map<String, List<String>> deploymentMap = getOrCreateDeploymentMap(tenant);
            
            log.info("{} - Persisting deployment map with {} entries", tenant, deploymentMap.size());

            Map<String, Object> fragment = new ConcurrentHashMap<>();
            fragment.put(MapperServiceRepresentation.DEPLOYMENT_MAP_FRAGMENT, deploymentMap);

            ManagedObjectRepresentation updateMor = new ManagedObjectRepresentation();
            updateMor.setId(GId.asGId(serviceRep.getId()));
            updateMor.setAttrs(fragment);

            inventoryApi.update(updateMor);
        });
    }
}