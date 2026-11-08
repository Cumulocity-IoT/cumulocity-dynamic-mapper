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

package dynamic.mapper.core;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import dynamic.mapper.model.extension.Extension;
import dynamic.mapper.model.extension.ExtensionEntry;
import dynamic.mapper.model.extension.ExtensionStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;

@Service
@Slf4j
@Order(TenantScoped.ORDER_EXTENSIONS)
public class ExtensionInboundRegistry implements TenantScoped {

    private final Map<String, Map<String, Extension>> tenantExtensionProcessors = new ConcurrentHashMap<>();

    /**
     * Get or create extension processor for tenant
     */
    public Map<String, Extension> getExtensions(String tenant) {
        return tenantExtensionProcessors.computeIfAbsent(tenant, t -> {
            log.info("Creating ExtensibleProcessorInbound for tenant: {}", t);
            return new ConcurrentHashMap<>();
        });
    }

    /**
     * Get or create extension processor for tenant
     */
    public Extension getExtension(String tenant, String extensionName) {
        return getExtensions(tenant).get(extensionName);
    }

    /**
     * Initialize extension processor for tenant (called during bootstrap)
     */
    @Override
    public void initializeTenant(TenantInitContext ctx) {
        initializeTenant(ctx.tenant());
    }

    public Map<String, Extension> initializeTenant(String tenant) {
        Map<String, Extension> extensions = new ConcurrentHashMap<>();
        tenantExtensionProcessors.put(tenant, extensions);
        log.info("{} - Initialized Extensions", tenant);
        return extensions;
    }

    /**
     * Add extension to tenant's processor
     */
    public void addExtension(String tenant, Extension extension) {
        Map<String, Extension> extensions = getExtensions(tenant);
        if (extensions.containsKey(extension.getName())) {
            log.warn("{} - Extension with this name {} already exits, override existing extension!", tenant,
                    extension.getName());
        }
        extensions.put(extension.getName(), extension);
    }

    /**
     * Add extension entry to tenant's processor
     */
    public void addExtensionEntry(String tenant, String extensionName, ExtensionEntry entry) {
        Map<String, Extension> extensions = getExtensions(tenant);
        if (!extensions.containsKey(extensionName)) {
            log.warn("{} - Cannot add Java Extension entry. Create first an extension!", tenant);
        } else {
            Map<String, ExtensionEntry> entries = extensions.get(extensionName).getExtensionEntries();
            if (entries.containsKey(entry.getEventName())) {
                log.warn("{} - Extension entry with this eventName {} already exists for extension {}, override existing entry!",
                        tenant, entry.getEventName(), extensionName);
            }
            entries.put(entry.getEventName(), entry);
        }
    }

    public Extension deleteExtension(String tenant, String extensionName) {
        Map<String, Extension> extensions = getExtensions(tenant);
        Extension result = extensions.remove(extensionName);
        return result;
    }

    public void removeTenant(String tenant) {
        // remove (not re-create): getExtensions() lazily re-creates an entry on demand
        tenantExtensionProcessors.remove(tenant);
    }

    public void updateStatusExtension(String tenant, String extName) {
        Map<String, Extension> extensions = getExtensions(tenant);
        Extension ext = extensions.get(extName);
        if (ext == null) {
            log.warn("{} - Cannot update status: extension {} not found in registry", tenant, extName);
            return;
        }
        ext.setLoaded(computeStatus(ext));
    }

    /**
     * Derive the loading status of an extension from the status of its entries:
     * COMPLETE if all entries loaded, PARTIALLY if only some did, NOT_LOADED if none did.
     */
    public static ExtensionStatus computeStatus(Extension ext) {
        long countDefined = ext.getExtensionEntries().size();
        long countLoaded = ext.getExtensionEntries().values().stream()
                .filter(entry -> Boolean.TRUE.equals(entry.getLoaded())).count();
        if (countLoaded == 0) {
            return ExtensionStatus.NOT_LOADED;
        } else if (countLoaded < countDefined) {
            return ExtensionStatus.PARTIALLY;
        }
        return ExtensionStatus.COMPLETE;
    }

    /**
     * Atomically replace all extensions of a tenant. Readers see either the previous or the new
     * set, never an empty registry in between.
     */
    public void replaceExtensions(String tenant, Map<String, Extension> extensions) {
        tenantExtensionProcessors.put(tenant, new ConcurrentHashMap<>(extensions));
    }

    /**
     * Get all registered tenants
     */
    public Set<String> getRegisteredTenants() {
        return tenantExtensionProcessors.keySet();
    }
}
