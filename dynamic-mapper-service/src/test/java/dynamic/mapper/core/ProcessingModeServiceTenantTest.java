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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.cumulocity.microservice.context.ContextService;
import com.cumulocity.microservice.context.credentials.MicroserviceCredentials;
import com.cumulocity.microservice.subscription.model.core.PlatformProperties;
import com.cumulocity.sdk.client.RestConnector;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The per-tenant {@code RestConnector} cache in {@link ProcessingModeService}.
 *
 * <p>Each cached connector carries a tenant's service-user credentials, so two things matter:
 * an entry must never be built from one tenant's context and stored under another's key, and it
 * must be dropped when the tenant unsubscribes.
 */
@ExtendWith(MockitoExtension.class)
class ProcessingModeServiceTenantTest {

    private static final String TENANT_A = "t1000";
    private static final String TENANT_B = "t2000";

    @Mock private ContextService<MicroserviceCredentials> contextService;
    @Mock private PlatformProperties platformProperties;

    private ProcessingModeService service;

    @BeforeEach
    void setUp() {
        service = new ProcessingModeService(contextService, platformProperties);
        lenient().when(platformProperties.getUrl()).thenReturn(() -> "http://localhost");
        lenient().when(platformProperties.getForceInitialHost()).thenReturn(true);
    }

    private void runningAs(String tenant) {
        MicroserviceCredentials credentials = new MicroserviceCredentials();
        credentials.setTenant(tenant);
        credentials.setUsername("service_" + tenant);
        credentials.setPassword("secret-" + tenant);
        when(contextService.getContext()).thenReturn(credentials);
    }

    private RestConnector connectorFor(String tenant) throws Exception {
        return service.callWithProcessingMode("TRANSIENT", connector -> connector);
    }

    @Test
    @DisplayName("each tenant gets its own connector, bound to its own credentials")
    void connectorsArePerTenant() throws Exception {
        runningAs(TENANT_A);
        RestConnector a = connectorFor(TENANT_A);
        runningAs(TENANT_B);
        RestConnector b = connectorFor(TENANT_B);

        assertNotSame(a, b, "tenants must not share a connector");
        assertEquals(TENANT_A, a.getPlatformParameters().getTenantId());
        assertEquals(TENANT_B, b.getPlatformParameters().getTenantId());
    }

    @Test
    @DisplayName("the connector is reused for repeated calls of the same tenant")
    void connectorIsCachedPerTenant() throws Exception {
        runningAs(TENANT_A);

        assertSame(connectorFor(TENANT_A), connectorFor(TENANT_A),
                "the expensive Client instance must be reused");
    }

    @Test
    @DisplayName("clearing a tenant's cache forces a fresh connector, and does not touch the other tenant")
    void clearingIsTenantScoped() throws Exception {
        runningAs(TENANT_A);
        RestConnector firstA = connectorFor(TENANT_A);
        runningAs(TENANT_B);
        RestConnector firstB = connectorFor(TENANT_B);

        // Happens when tenant A unsubscribes — its credentials must not stay resident.
        service.clearConnectorCache(TENANT_A);

        runningAs(TENANT_A);
        assertNotSame(firstA, connectorFor(TENANT_A),
                "a cleared tenant must get a freshly built connector");
        runningAs(TENANT_B);
        assertSame(firstB, connectorFor(TENANT_B),
                "clearing one tenant must not evict another");
    }

    @Test
    @DisplayName("an explicit tenant that disagrees with the ambient context does not poison the cache")
    void mismatchedTenantIsNotCached() throws Exception {
        // Ask for tenant B's connector while the thread runs under tenant A's context. The
        // credentials can only come from the ambient context, so caching the result under B
        // would make every later call for B execute as A.
        runningAs(TENANT_A);
        RestConnector mismatched = service.executeWithProcessingMode("TRANSIENT", TENANT_B,
                connector -> connector);
        assertEquals(TENANT_A, mismatched.getPlatformParameters().getTenantId(),
                "it is bound to the context it was built from");

        runningAs(TENANT_B);
        RestConnector properB = connectorFor(TENANT_B);

        assertNotSame(mismatched, properB, "tenant B must not inherit the connector built under A");
        assertEquals(TENANT_B, properB.getPlatformParameters().getTenantId());
    }
}
