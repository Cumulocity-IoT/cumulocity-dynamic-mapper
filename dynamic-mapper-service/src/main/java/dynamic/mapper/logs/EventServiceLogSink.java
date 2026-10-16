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

import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.joda.time.DateTime;
import org.springframework.stereotype.Service;

import com.cumulocity.microservice.context.ContextService;
import com.cumulocity.microservice.context.credentials.MicroserviceCredentials;
import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;
import com.cumulocity.model.idtype.GId;
import com.cumulocity.rest.representation.event.EventRepresentation;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.cumulocity.sdk.client.SDKException;
import com.cumulocity.sdk.client.event.EventApi;
import com.cumulocity.sdk.client.event.EventFilter;

import dynamic.mapper.core.C8YAgent;

/**
 * Stores a tenant's copy of the log as events on the tenant's service-log source object (see
 * {@link ServiceLogSourceLocator} for why not the agent), written with that tenant's own service user. One event per chunk, never per line.
 *
 * <p>Event shape ({@code type d11r_serviceLog}): {@code text} is the summary; the fragment
 * {@code d11r_serviceLog} holds {@code instance}, {@code dateFrom}, {@code dateTo}, {@code lines},
 * {@code levels}, {@code omitted} and {@code logs}.
 */
@Service
public class EventServiceLogSink implements ServiceLogSink {

    public static final String EVENT_TYPE = "d11r_serviceLog";
    public static final String FRAGMENT = "d11r_serviceLog";

    private final EventApi eventApi;
    private final MicroserviceSubscriptionsService subscriptionsService;
    private final ContextService<MicroserviceCredentials> contextService;
    private final ServiceLogSourceLocator sourceLocator;

    public EventServiceLogSink(EventApi eventApi, MicroserviceSubscriptionsService subscriptionsService,
            ContextService<MicroserviceCredentials> contextService, ServiceLogSourceLocator sourceLocator) {
        this.eventApi = eventApi;
        this.subscriptionsService = subscriptionsService;
        this.contextService = contextService;
        this.sourceLocator = sourceLocator;
    }

    @Override
    public void write(String tenant, String instance, ServiceLogChunk chunk) {
        inTenant(tenant, true, sourceId -> {
            EventRepresentation event = new EventRepresentation();
            ManagedObjectRepresentation source = new ManagedObjectRepresentation();
            source.setId(sourceId);
            event.setSource(source);
            event.setType(EVENT_TYPE);
            event.setText(chunk.summary());
            event.setDateTime(new DateTime(chunk.dateTo().toEpochMilli()));
            Map<String, Object> fragment = new LinkedHashMap<>();
            fragment.put("instance", instance);
            fragment.put("dateFrom", chunk.dateFrom().toString());
            fragment.put("dateTo", chunk.dateTo().toString());
            fragment.put("lines", chunk.lines());
            fragment.put("levels", chunk.levels());
            fragment.put("omitted", chunk.omitted());
            fragment.put("logs", chunk.logs());
            event.setProperty(FRAGMENT, fragment);
            try {
                eventApi.create(event);
            } catch (SDKException e) {
                if (e.getHttpStatus() == 404) {
                    sourceLocator.evict(tenant); // the source object is gone: look it up (or recreate it) next time
                }
                throw e;
            }
            return null;
        });
    }

    @Override
    public void purge(String tenant, Instant before) {
        inTenant(tenant, false, sourceId -> {
            if (sourceId == null) {
                return null;
            }
            eventApi.deleteEventsByFilter(new EventFilter().byType(EVENT_TYPE).bySource(sourceId)
                    .byDate(new Date(0), new Date(before.toEpochMilli())));
            return null;
        });
    }

    /**
     * Runs {@code action} as the tenant's service user with the log source as its argument. When
     * {@code create} is false and no source exists yet, the argument is {@code null}.
     */
    private <T> T inTenant(String tenant, boolean create, java.util.function.Function<GId, T> action) {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        subscriptionsService.runForTenant(tenant, () -> {
            MicroserviceCredentials context = C8YAgent.removeAppKeyHeaderFromContext(contextService.getContext());
            contextService.runWithinContext(context, () -> {
                try {
                    GId sourceId = create ? sourceLocator.getOrCreate(tenant) : sourceLocator.find(tenant).orElse(null);
                    result.set(action.apply(sourceId));
                } catch (RuntimeException e) {
                    failure.set(e);
                }
            });
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return result.get();
    }
}
