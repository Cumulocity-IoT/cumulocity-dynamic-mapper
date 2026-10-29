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

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

import java.util.Map;

/**
 * Replaces logback's {@code %m} so a message does not repeat the tenant its line already carries
 * in the {@code [tenant]} field.
 *
 * <p>Most of the service's messages start with the tenant by hand ({@code "t123 - Connected"}).
 * With the tenant in its own field that is noise, so a leading {@code "<tenant> - "} (or
 * {@code "Tenant <tenant> - "}) is dropped — but <b>only when it equals the line's tenant</b>.
 * A line without a tenant field, or with a different one, keeps its text untouched. That keeps the
 * information on threads that have no tenant scope yet, and makes such gaps visible: a line that
 * still starts with a tenant while its field says {@code -} needs a {@link TenantLogContext}.
 */
public class TenantMessageConverter extends MessageConverter {

    @Override
    public String convert(ILoggingEvent event) {
        String message = event.getFormattedMessage();
        if (message == null) {
            return null;
        }
        Map<String, String> mdc = event.getMDCPropertyMap();
        if (mdc == null) {
            return message;
        }
        String tenant = mdc.get(TenantLogContext.SDK_KEY);
        if (tenant == null || tenant.isEmpty()) {
            tenant = mdc.get(TenantLogContext.KEY);
        }
        if (tenant == null || tenant.isEmpty()) {
            return message;
        }
        return stripPrefix(message, tenant);
    }

    static String stripPrefix(String message, String tenant) {
        String prefix = tenant + " - ";
        if (message.startsWith(prefix)) {
            return message.substring(prefix.length());
        }
        String capitalised = "Tenant " + prefix;
        if (message.startsWith(capitalised)) {
            return message.substring(capitalised.length());
        }
        return message;
    }
}
