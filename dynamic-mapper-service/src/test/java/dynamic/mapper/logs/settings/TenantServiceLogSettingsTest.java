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

package dynamic.mapper.logs.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.configuration.ServiceLogsConfiguration;
import dynamic.mapper.core.TenantRegistry;

class TenantServiceLogSettingsTest {

    private final TenantRegistry registry = mock(TenantRegistry.class);
    private final TenantServiceLogSettings provider = new TenantServiceLogSettings(registry);

    @Test
    void aTenantWhoseConfigurationIsNotKnownYetGetsTheDefaults() {
        when(registry.getServiceConfiguration("tenantA")).thenReturn(null);

        assertEquals(ServiceLogsConfiguration.defaults(), provider.forTenant("tenantA"));
    }

    @Test
    void aConfigurationWithoutTheSettingsObjectGetsTheDefaults() {
        ServiceConfiguration configuration = new ServiceConfiguration();
        configuration.setServiceLogs(null);
        when(registry.getServiceConfiguration("tenantA")).thenReturn(configuration);

        assertEquals(ServiceLogsConfiguration.defaults(), provider.forTenant("tenantA"));
    }

    @Test
    void eachTenantGetsItsOwnValuesAndTheyAreClamped() {
        ServiceConfiguration a = new ServiceConfiguration();
        a.getServiceLogs().setMinLevel("WARN");
        ServiceConfiguration b = new ServiceConfiguration();
        b.getServiceLogs().setMinLevel("ERROR");
        b.getServiceLogs().setIntervalMs(1);
        when(registry.getServiceConfiguration("tenantA")).thenReturn(a);
        when(registry.getServiceConfiguration("tenantB")).thenReturn(b);

        assertEquals("WARN", provider.forTenant("tenantA").minLevel());
        assertEquals("ERROR", provider.forTenant("tenantB").minLevel());
        assertEquals(5_000, provider.forTenant("tenantB").intervalMs());
    }

    @Test
    void aChangeSavedLaterIsSeenByTheNextCallWithoutARestart() {
        ServiceConfiguration configuration = new ServiceConfiguration();
        when(registry.getServiceConfiguration("tenantA")).thenReturn(configuration);
        assertEquals("INFO", provider.forTenant("tenantA").minLevel());

        ServiceConfiguration saved = new ServiceConfiguration();
        saved.getServiceLogs().setMinLevel("WARN");
        when(registry.getServiceConfiguration("tenantA")).thenReturn(saved); // what addServiceConfiguration does

        assertEquals("WARN", provider.forTenant("tenantA").minLevel());
    }

    @Test
    void theSameConfigurationObjectIsResolvedOnlyOnce() {
        ServiceConfiguration configuration = new ServiceConfiguration();
        when(registry.getServiceConfiguration("tenantA")).thenReturn(configuration);

        assertSame(provider.forTenant("tenantA"), provider.forTenant("tenantA"));
    }
}
