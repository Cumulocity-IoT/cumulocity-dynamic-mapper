/*
 * Copyright (c) 2025 Cumulocity GmbH
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
 * @authors Christof Strack
 */

package dynamic.mapper.logs;

import org.springframework.stereotype.Component;

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.configuration.ServiceLogsConfiguration;
import dynamic.mapper.configuration.ServiceLogsConfiguration.Settings;
import dynamic.mapper.core.TenantRegistry;

/**
 * Reads the settings from the tenant's service configuration, which is kept in memory per tenant and replaced when the
 * tenant saves it: a change on the Service configuration page applies to the next log line, with no restart. Anything
 * not set falls back to the defaults in {@link ServiceLogsConfiguration}, and out-of-range values are clamped there.
 */
@Component
public class TenantServiceLogSettings implements ServiceLogSettingsProvider {

    private static final Settings DEFAULTS = ServiceLogsConfiguration.defaults();

    private final TenantRegistry tenantRegistry;

    public TenantServiceLogSettings(TenantRegistry tenantRegistry) {
        this.tenantRegistry = tenantRegistry;
    }

    @Override
    public Settings forTenant(String tenant) {
        ServiceConfiguration configuration = tenantRegistry.getServiceConfiguration(tenant);
        ServiceLogsConfiguration logs = configuration == null ? null : configuration.getServiceLogs();
        return logs == null ? DEFAULTS : logs.effective();
    }
}
