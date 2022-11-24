/*
 * Copyright (c) 2022 Software AG, Darmstadt, Germany and/or Software AG USA Inc., Reston, VA, USA,
 * and/or its subsidiaries and/or its affiliates and/or their licensors.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * @authors Christof Strack, Stefan Witschel
 */

package mqtt.mapping.configuration;


import com.cumulocity.microservice.context.credentials.MicroserviceCredentials;
import com.cumulocity.model.option.OptionPK;
import com.cumulocity.rest.representation.tenant.OptionRepresentation;
import com.cumulocity.sdk.client.Platform;
import com.cumulocity.sdk.client.SDKException;
import com.cumulocity.sdk.client.option.TenantOptionApi;
import com.cumulocity.rest.representation.tenant.auth.TrustedCertificateCollectionRepresentation;
import com.cumulocity.rest.representation.tenant.auth.TrustedCertificateRepresentation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import javax.ws.rs.core.MediaType;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;


@Slf4j
@Component
public class ServiceConfigurationComponent {
	private static final String OPTION_CATEGORY_CONFIGURATION = "mqtt.dynamic.service";

    private static final String OPTION_KEY_CONNECTION_CONFIGURATION = "credentials.connection.configuration";
    private static final String OPTION_KEY_SERVICE_CONFIGURATION = "service.configuration";

    private final TenantOptionApi tenantOptionApi;

    private final Platform platform;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    public ServiceConfigurationComponent(TenantOptionApi tenantOptionApi, Platform platform) {
        this.tenantOptionApi = tenantOptionApi;
        this.platform = platform;
    }

    public TrustedCertificateRepresentation loadCertificateByName(String certificateName, MicroserviceCredentials credentials) {
        TrustedCertificateRepresentation[] results = { new TrustedCertificateRepresentation() };
        TrustedCertificateCollectionRepresentation certificates = platform.rest().get(String.format("/tenant/tenants/%s/trusted-certificates", credentials.getTenant()), MediaType.APPLICATION_JSON_TYPE, TrustedCertificateCollectionRepresentation.class);        
        certificates.forEach(cert -> {
            if ( cert.getName().equals(certificateName)) {
                results[0] = cert;
            log.debug("Found certificate with fingerprint: {} with name: {}", cert.getFingerprint(), cert.getName() );
            }
        });
        return results[0];
    }

    public void saveServiceConfiguration(final ServiceConfiguration configuration) throws JsonProcessingException {
        if (configuration == null) {
            return;
        }

        final String configurationJson = objectMapper.writeValueAsString(configuration);
        final OptionRepresentation optionRepresentation = new OptionRepresentation();
        optionRepresentation.setCategory(OPTION_CATEGORY_CONFIGURATION);
        optionRepresentation.setKey(OPTION_KEY_SERVICE_CONFIGURATION);
        optionRepresentation.setValue(configurationJson);

        tenantOptionApi.save(optionRepresentation);
    }

    public ServiceConfiguration loadServiceConfiguration() {
        final OptionPK option = new OptionPK();
        option.setCategory(OPTION_CATEGORY_CONFIGURATION);
        option.setKey(OPTION_KEY_SERVICE_CONFIGURATION);
        try {
            final OptionRepresentation optionRepresentation = tenantOptionApi.getOption(option);
            final ServiceConfiguration configuration = new ObjectMapper().readValue(optionRepresentation.getValue(), ServiceConfiguration.class);
            log.debug("Returning service configuration found: {}:", configuration.logPayload );
            return configuration;
        } catch (SDKException exception) {
            log.warn("No configuration found, returning empty element!");
            return new ServiceConfiguration();
            //exception.printStackTrace();
        } catch (JsonMappingException e) {
            e.printStackTrace();
        } catch (JsonProcessingException e) {
            e.printStackTrace();
        }
        return null;
    }

    public void deleteAllConfiguration() {
        final OptionPK optionPK = new OptionPK();
        optionPK.setCategory(OPTION_CATEGORY_CONFIGURATION);
        optionPK.setKey(OPTION_KEY_CONNECTION_CONFIGURATION);
        tenantOptionApi.delete(optionPK);
        optionPK.setKey(OPTION_KEY_SERVICE_CONFIGURATION);
        tenantOptionApi.delete(optionPK);
    }
}