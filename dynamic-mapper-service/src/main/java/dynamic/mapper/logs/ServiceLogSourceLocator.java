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

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import com.cumulocity.microservice.subscription.model.MicroserviceSubscriptionRemovedEvent;
import com.cumulocity.model.ID;
import com.cumulocity.model.idtype.GId;
import com.cumulocity.rest.representation.identity.ExternalIDRepresentation;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.cumulocity.sdk.client.SDKException;
import com.cumulocity.sdk.client.identity.IdentityApi;
import com.cumulocity.sdk.client.inventory.InventoryApi;

import dynamic.mapper.core.IMapperConfiguration;
import dynamic.mapper.model.MapperServiceRepresentation;
import lombok.extern.slf4j.Slf4j;

/**
 * Finds — and, for the writer, creates — the managed object the log events are attached to.
 *
 * <p>The events are deliberately <b>not</b> attached to the service agent: the "Service events" page lists the
 * agent's newest 1000 events with no type filter, so log events of up to 64 KB every few seconds would push the
 * real service events off that page and make it heavy. A separate source object (a child addition of the agent,
 * found by an external id like the agent itself) keeps the two apart.
 *
 * <p>Every method must run inside the tenant's own context (the caller does the {@code runForTenant}); the
 * tenant argument only keys the cache. The cache is dropped on unsubscribe, so a tenant that unsubscribes and
 * subscribes again never reuses a stale id.
 */
@Slf4j
@Service
public class ServiceLogSourceLocator {

    public static final String EXTERNAL_ID_TYPE = "c8y_Serial";
    public static final String EXTERNAL_ID = "d11r_serviceLogSource";
    public static final String TYPE = "d11r_serviceLogSource";
    public static final String NAME = "Dynamic Mapper service log";

    private final InventoryApi inventoryApi;
    private final IdentityApi identityApi;
    private final IMapperConfiguration mapperConfiguration;
    private final ConcurrentMap<String, GId> cache = new ConcurrentHashMap<>();

    public ServiceLogSourceLocator(InventoryApi inventoryApi, IdentityApi identityApi,
            IMapperConfiguration mapperConfiguration) {
        this.inventoryApi = inventoryApi;
        this.identityApi = identityApi;
        this.mapperConfiguration = mapperConfiguration;
    }

    /** The source object, or empty if nothing has ever been collected for this tenant. Never creates. */
    public Optional<GId> find(String tenant) {
        GId cached = cache.get(tenant);
        if (cached != null) {
            return Optional.of(cached);
        }
        try {
            ExternalIDRepresentation external = identityApi.getExternalId(new ID(EXTERNAL_ID_TYPE, EXTERNAL_ID));
            GId id = external.getManagedObject().getId();
            cache.put(tenant, id);
            return Optional.of(id);
        } catch (SDKException e) {
            if (e.getHttpStatus() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    /** The source object, created on first use. */
    public GId getOrCreate(String tenant) {
        Optional<GId> existing = find(tenant);
        if (existing.isPresent()) {
            return existing.get();
        }
        ManagedObjectRepresentation source = new ManagedObjectRepresentation();
        source.setName(NAME);
        source.setType(TYPE);
        source = inventoryApi.create(source);
        ExternalIDRepresentation external = new ExternalIDRepresentation();
        external.setType(EXTERNAL_ID_TYPE);
        external.setExternalId(EXTERNAL_ID);
        external.setManagedObject(source);
        try {
            identityApi.create(external);
        } catch (SDKException e) {
            // Another instance of the service created the source at the same moment and registered its external id first
            // (the id is unique, so exactly one wins). Use the winner and remove the object we just made, or it would stay
            // behind as an orphan in the tenant's inventory.
            cache.remove(tenant);
            Optional<GId> winner = find(tenant);
            if (winner.isPresent() && !winner.get().equals(source.getId())) {
                discard(tenant, source);
                return winner.get();
            }
            throw e;
        }
        link(tenant, source);
        log.info("{} - Created the service log source {}", tenant, source.getId().getValue());
        cache.put(tenant, source.getId());
        return source.getId();
    }

    private void discard(String tenant, ManagedObjectRepresentation orphan) {
        try {
            inventoryApi.delete(orphan.getId());
        } catch (RuntimeException e) {
            log.warn("{} - Could not remove the duplicate service log source {}: {}", tenant, orphan.getId().getValue(),
                    e.getMessage());
        }
    }

    /**
     * Forgets the remembered source. Called when a write finds it gone (the tenant deleted the object): without it the
     * stale id would be used, and fail, until the tenant unsubscribes.
     */
    public void evict(String tenant) {
        cache.remove(tenant);
    }

    /** Shows the source under the agent in the inventory. Cosmetic: a failure must not stop log collection. */
    private void link(String tenant, ManagedObjectRepresentation source) {
        try {
            MapperServiceRepresentation agent = mapperConfiguration.getMapperServiceRepresentation(tenant);
            if (agent != null) {
                inventoryApi.getManagedObjectApi(new GId(agent.getId())).addChildAddition(source);
            }
        } catch (RuntimeException e) {
            log.debug("{} - Could not link the service log source to the agent: {}", tenant, e.getMessage());
        }
    }

    @EventListener
    public void onUnsubscribed(MicroserviceSubscriptionRemovedEvent event) {
        cache.remove(event.getTenant());
    }
}
