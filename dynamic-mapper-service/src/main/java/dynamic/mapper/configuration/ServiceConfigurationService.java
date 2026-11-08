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

package dynamic.mapper.configuration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;
import com.cumulocity.model.option.OptionPK;
import com.cumulocity.rest.representation.tenant.OptionRepresentation;
import com.cumulocity.sdk.client.SDKException;
import com.cumulocity.sdk.client.option.TenantOptionApi;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import dynamic.mapper.util.Utils;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class ServiceConfigurationService {
    @Value("classpath:mappings/mappings-INBOUND.json")
    private Resource sampleMappingsInbound_01;

    public String getSampleMappingsInbound_01() {
        return validateAndConvert(sampleMappingsInbound_01);
    }

    @Value("classpath:mappings/mappings-OUTBOUND.json")
    private Resource sampleMappingsOutbound_01;

    public String getSampleMappingsOutbound_01() {
        return validateAndConvert(sampleMappingsOutbound_01);
    }

    private static final String OPTION_KEY_SERVICE_CONFIGURATION = "service.configuration";

    private final TenantOptionApi tenantOptionApi;

    private final MicroserviceSubscriptionsService subscriptionsService;

    private final ObjectMapper objectMapper;

    private final CodeTemplateLoader codeTemplateLoader;

    public ServiceConfigurationService(TenantOptionApi tenantOptionApi,
            MicroserviceSubscriptionsService subscriptionsService, ObjectMapper objectMapper,
            CodeTemplateLoader codeTemplateLoader) {
        this.tenantOptionApi = tenantOptionApi;
        this.subscriptionsService = subscriptionsService;
        this.objectMapper = objectMapper;
        this.codeTemplateLoader = codeTemplateLoader;
    }

    public String validateAndConvert(Resource resource) {
        try {
            return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Failed to read mapping file", e);
            return "{}";
        }
    }

    public void saveServiceConfiguration(String tenant, final ServiceConfiguration configuration)
            throws JsonProcessingException {
        if (configuration == null) {
            return;
        }

        final String configurationJson = objectMapper.writeValueAsString(configuration);
        final OptionRepresentation optionRepresentation = OptionRepresentation.asOptionRepresentation(
                Utils.OPTION_CATEGORY_CONFIGURATION, OPTION_KEY_SERVICE_CONFIGURATION, configurationJson);
        tenantOptionApi.save(optionRepresentation);
    }

    /**
     * Loads the service configuration of a tenant at startup and fills every setting that is still
     * missing: cache sizes and retentions, and the code templates. Saves the configuration only when
     * something changed.
     *
     * @param inboundExternalIdCacheSize  default size of the inbound external ID cache
     * @param outboundExternalIdCacheSize default size of the outbound external ID cache
     * @param inventoryCacheSize          default size of the inventory cache
     * @return the normalized configuration
     */
    public ServiceConfiguration ensureDefaults(String tenant, int inboundExternalIdCacheSize,
            int outboundExternalIdCacheSize, int inventoryCacheSize) {
        ServiceConfiguration serviceConfig = getServiceConfiguration(tenant);
        boolean requiresSave = false;

        if (serviceConfig.getInboundExternalIdCacheSize() == null
                || serviceConfig.getInboundExternalIdCacheSize() == 0) {
            serviceConfig.setInboundExternalIdCacheSize(inboundExternalIdCacheSize);
            requiresSave = true;
        }

        if (serviceConfig.getInboundExternalIdCacheRetention() == null) {
            serviceConfig.setInboundExternalIdCacheRetention(1);
            requiresSave = true;
        }

        if (serviceConfig.getOutboundExternalIdCacheSize() == null
                || serviceConfig.getOutboundExternalIdCacheSize() == 0) {
            serviceConfig.setOutboundExternalIdCacheSize(outboundExternalIdCacheSize);
            requiresSave = true;
        }

        if (serviceConfig.getOutboundExternalIdCacheRetention() == null) {
            serviceConfig.setOutboundExternalIdCacheRetention(1);
            requiresSave = true;
        }

        if (serviceConfig.getInventoryCacheSize() == null || serviceConfig.getInventoryCacheSize() == 0) {
            serviceConfig.setInventoryCacheSize(inventoryCacheSize);
            requiresSave = true;
        }

        Map<String, CodeTemplate> codeTemplates = serviceConfig.getCodeTemplates();
        if (codeTemplates == null || codeTemplates.isEmpty()) {
            // First start: load all templates from classpath
            codeTemplateLoader.initCodeTemplates(serviceConfig, false);
            requiresSave = true;
        } else {
            // Subsequent starts: add any newly deployed internal templates
            if (codeTemplateLoader.addMissingInternalTemplates(serviceConfig)) {
                requiresSave = true;
            }
            // ...and always re-load the framework-owned SYSTEM template. It is @internal/@readonly
            // and holds only the Java.type bindings the runtime needs, so the packaged copy is
            // authoritative. Customer code lives in SHARED, which is never touched here. The caller
            // initializes the tenant beans afterwards, so the refreshed code is what gets compiled.
            if (codeTemplateLoader.refreshSystemTemplate(serviceConfig)) {
                requiresSave = true;
            }
        }

        if (requiresSave) {
            try {
                saveServiceConfiguration(tenant, serviceConfig);
            } catch (JsonProcessingException e) {
                log.error("{} - Error saving service configuration: {}", tenant, e.getMessage(), e);
            }
        }

        return serviceConfig;
    }

    /**
     * Merges the given fields into the stored service configuration and saves it. Fields that are not
     * in {@code updates} keep their value.
     *
     * @return the merged, saved configuration
     */
    public ServiceConfiguration applyPartialUpdate(String tenant, Map<String, Object> updates)
            throws java.io.IOException {
        ServiceConfiguration existing = getServiceConfiguration(tenant);
        ServiceConfiguration merged = objectMapper.readerForUpdating(existing)
                .readValue(objectMapper.writeValueAsBytes(updates));
        saveServiceConfiguration(tenant, merged);
        return merged;
    }

    public ServiceConfiguration getServiceConfiguration(String tenant) {
        final OptionPK option = new OptionPK();
        option.setCategory(Utils.OPTION_CATEGORY_CONFIGURATION);
        option.setKey(OPTION_KEY_SERVICE_CONFIGURATION);
        ServiceConfiguration result = subscriptionsService.callForTenant(tenant, () -> {
            ServiceConfiguration rt = null;
            try {
                final OptionRepresentation optionRepresentation = tenantOptionApi.getOption(option);
                if (optionRepresentation.getValue() == null) {
                    rt = initialize(tenant);
                } else {
                    rt = objectMapper.readValue(optionRepresentation.getValue(),
                            ServiceConfiguration.class);
                    applyDefaults(rt);
                }
                log.debug("{} - Returning service configuration found: {}:", tenant, rt.getLogPayload());
                log.debug("{} - Found connection configuration: {}", tenant, rt);
            } catch (SDKException exception) {
                log.warn("{} - No configuration found, returning empty element!", tenant);
                rt = initialize(tenant);
            } catch (Exception e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                log.warn("Failed to convert service object. Error: {}", cause.getMessage());
                rt = initialize(tenant);
            }
            return rt;
        });
        return result;
    }

    private void applyDefaults(ServiceConfiguration config) {
        if (config.getEngineRotationThreshold() == null) config.setEngineRotationThreshold(1000);
    }

    public void deleteServiceConfigurations(String tenant) {
        OptionPK optionPK = new OptionPK(Utils.OPTION_CATEGORY_CONFIGURATION, OPTION_KEY_SERVICE_CONFIGURATION);
        tenantOptionApi.delete(optionPK);
    }

    public ServiceConfiguration initialize(String tenant) {
        ServiceConfiguration configuration = new ServiceConfiguration();
        try {
            saveServiceConfiguration(tenant, configuration);
        } catch (JsonProcessingException e) {
            log.warn("{} - failed to initialize ServiceConfiguration!", tenant, e);
        }
        return configuration;
    }

}