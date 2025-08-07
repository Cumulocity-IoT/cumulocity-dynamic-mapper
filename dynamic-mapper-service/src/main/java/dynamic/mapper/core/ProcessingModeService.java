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

import com.cumulocity.microservice.context.ContextService;
import com.cumulocity.microservice.context.credentials.MicroserviceCredentials;
import com.cumulocity.microservice.subscription.model.core.PlatformProperties;
import com.cumulocity.model.authentication.CumulocityCredentials;
import com.cumulocity.model.authentication.CumulocityCredentialsFactory;
import com.cumulocity.sdk.client.*;
import com.cumulocity.sdk.client.interceptor.HttpClientInterceptor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import jakarta.ws.rs.client.Invocation;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProcessingModeService {

    private final ContextService<MicroserviceCredentials> contextService;
    private final PlatformProperties platformProperties;
    
    // Cache connectors per tenant to avoid recreating expensive Client instances
    private final ConcurrentMap<String, RestConnector> connectorCache = new ConcurrentHashMap<>();

    public <T> T callWithProcessingMode(String processingMode, ConnectorFunction<T> function) throws Exception {
        final String tenant = contextService.getContext().getTenant();
        final RestConnector connector = getOrCreateConnector(tenant);
        final HttpClientInterceptor interceptor = new ProcessingModeHttpClientInterceptor(processingMode);
        
        try {
            // Thread-safe: registering interceptors is synchronized in PlatformParameters
            connector.getPlatformParameters().registerInterceptor(interceptor);
            log.debug("Registered {} processing mode interceptor for tenant {}", processingMode, tenant);
            
            // Each thread gets its own function execution with the same connector
            return function.apply(connector);
        } finally {
            connector.getPlatformParameters().unregisterInterceptor(interceptor);
            log.debug("Unregistered {} processing mode interceptor for tenant {}", processingMode, tenant);
        }
    }

    /**
     * Get or create a cached connector for the tenant.
     * Thread-safe and reuses the expensive Client instance.
     */
    private RestConnector getOrCreateConnector(String tenant) {
        return connectorCache.computeIfAbsent(tenant, t -> {
            log.debug("Creating new RestConnector for tenant: {}", t);
            return createRestConnector();
        });
    }

    /**
     * Create a new connector - each connector gets its own Client instance
     * but we cache them per tenant to avoid recreation
     */
    public RestConnector createRestConnector() {
        final PlatformParameters params = createPlatformParameters();
        return new RestConnector(params, new ResponseParser());
    }

    /**
     * For operations that need a dedicated connector with processing mode.
     * This creates a separate connector to avoid interfering with the cached one.
     */
    public RestConnector createConnectorWithProcessingMode(String processingMode) {
        final RestConnector connector = createRestConnector();
        final HttpClientInterceptor interceptor = new ProcessingModeHttpClientInterceptor(processingMode);
        
        connector.getPlatformParameters().registerInterceptor(interceptor);
        log.debug("Created dedicated connector with {} processing mode", processingMode);
        
        return connector;
    }

    /**
     * Alternative method that temporarily applies processing mode to existing connector.
     * Use this for single operations.
     */
    public <T> T executeWithProcessingMode(String processingMode, String tenant, ConnectorFunction<T> function) throws Exception {
        final RestConnector connector = getOrCreateConnector(tenant);
        final HttpClientInterceptor interceptor = new ProcessingModeHttpClientInterceptor(processingMode);
        
        try {
            connector.getPlatformParameters().registerInterceptor(interceptor);
            log.debug("Temporarily applied {} processing mode for tenant {}", processingMode, tenant);
            return function.apply(connector);
        } finally {
            connector.getPlatformParameters().unregisterInterceptor(interceptor);
            log.debug("Removed temporary {} processing mode for tenant {}", processingMode, tenant);
        }
    }

    private PlatformParameters createPlatformParameters() {
        final MicroserviceCredentials context = contextService.getContext();
        
        final CumulocityCredentials credentials = new CumulocityCredentialsFactory()
                .withUsername(context.getUsername())
                .withTenant(context.getTenant())
                .withPassword(context.getPassword())
                .withOAuthAccessToken(context.getOAuthAccessToken())
                .withXsrfToken(context.getXsrfToken())
                .withApplicationKey(context.getAppKey())
                .getCredentials();
        
        final PlatformParameters params = new PlatformParameters(
            platformProperties.getUrl().get(), 
            credentials, 
            new ClientConfiguration()
        );
        
        params.setForceInitialHost(platformProperties.getForceInitialHost());
        params.setTfaToken(context.getTfaToken());
        
        return params;
    }

    /**
     * Clear cached connector for a specific tenant (useful for credential updates)
     */
    public void clearConnectorCache(String tenant) {
        RestConnector removed = connectorCache.remove(tenant);
        if (removed != null) {
            log.debug("Cleared connector cache for tenant: {}", tenant);
        }
    }

    /**
     * Clear all cached connectors
     */
    public void clearAllConnectorCaches() {
        connectorCache.clear();
        log.debug("Cleared all connector caches");
    }

    /**
     * Get the cached connector for a tenant (without processing mode)
     */
    public RestConnector getConnectorForTenant(String tenant) {
        return getOrCreateConnector(tenant);
    }

    /**
     * Properly close all clients when the service is destroyed
     */
    @PreDestroy
    public void cleanup() {
        log.info("Closing {} cached connectors", connectorCache.size());
        connectorCache.values().parallelStream().forEach(connector -> {
            try {
                if (!connector.isClosed()) {
                    connector.close();
                    log.debug("Closed connector");
                }
            } catch (Exception e) {
                log.warn("Error closing connector: {}", e.getMessage());
            }
        });
        connectorCache.clear();
        log.info("Connector cleanup completed");
    }

    @RequiredArgsConstructor
    private static class ProcessingModeHttpClientInterceptor implements HttpClientInterceptor {
        private final String processingMode;

        @Override
        public Invocation.Builder apply(final Invocation.Builder builder) {
            return builder.header("X-Cumulocity-Processing-Mode", processingMode);
        }
    }
}