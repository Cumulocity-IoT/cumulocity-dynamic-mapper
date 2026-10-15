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

package dynamic.mapper.util;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

import java.util.Map;

/**
 * Logback conversion word {@code %tenantId}: the tenant a line belongs to, or {@code -}.
 *
 * <p>Inside a Cumulocity SDK scope the SDK's own {@code tenant} key wins (it is the innermost
 * scope when both are set); otherwise the key set by {@link TenantLogContext}. Registered in
 * {@code logback-spring.xml}.
 */
public class TenantLogConverter extends ClassicConverter {

    @Override
    public String convert(ILoggingEvent event) {
        Map<String, String> mdc = event.getMDCPropertyMap();
        if (mdc != null) {
            String tenant = mdc.get(TenantLogContext.SDK_KEY);
            if (tenant == null || tenant.isEmpty()) {
                tenant = mdc.get(TenantLogContext.KEY);
            }
            if (tenant != null && !tenant.isEmpty()) {
                return tenant;
            }
        }
        return "-";
    }
}
