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
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Service;

import com.cumulocity.model.idtype.GId;
import com.cumulocity.rest.representation.event.EventRepresentation;
import com.cumulocity.sdk.client.PagedCollectionResource;
import com.cumulocity.sdk.client.QueryParam;
import com.cumulocity.sdk.client.event.EventApi;
import com.cumulocity.sdk.client.event.EventFilter;
import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;


/**
 * Reads a tenant's {@code d11r_serviceLog} events with that tenant's own service user, so the caller's
 * platform permissions do not matter — the service endpoint in front of it is what enforces who may read.
 */
@Service
public class EventServiceLogEvents implements ServiceLogReader.Events {

    private final EventApi eventApi;
    private final MicroserviceSubscriptionsService subscriptionsService;
    private final ServiceLogSourceLocator sourceLocator;

    public EventServiceLogEvents(EventApi eventApi, MicroserviceSubscriptionsService subscriptionsService,
            ServiceLogSourceLocator sourceLocator) {
        this.eventApi = eventApi;
        this.subscriptionsService = subscriptionsService;
        this.sourceLocator = sourceLocator;
    }

    @Override
    public ServiceLogReader.Batch list(String tenant, Instant from, Instant to, boolean oldestFirst,
            int page, int pageSize) {
        AtomicReference<ServiceLogReader.Batch> result = new AtomicReference<>(ServiceLogReader.Batch.of(List.of()));
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        subscriptionsService.runForTenant(tenant, () -> {
            try {
                GId sourceId = sourceLocator.find(tenant).orElse(null);
                if (sourceId == null) {
                    return; // nothing was ever collected for this tenant
                }
                EventFilter filter = new EventFilter().byType(EventServiceLogSink.EVENT_TYPE)
                        .bySource(sourceId)
                        .byDate(new Date(from == null ? 0 : from.toEpochMilli()),
                                new Date(to == null ? System.currentTimeMillis() + 86_400_000L : to.toEpochMilli()));
                List<QueryParam> params = new ArrayList<>();
                params.add(new QueryParam(() -> PagedCollectionResource.PAGE_NUMBER_KEY, String.valueOf(page)));
                if (oldestFirst) {
                    params.add(new QueryParam(() -> "revert", "true"));
                }
                List<ServiceLogReader.StoredChunk> chunks = new ArrayList<>();
                List<EventRepresentation> raw = eventApi.getEventsByFilter(filter)
                        .get(pageSize, params.toArray(new QueryParam[0])).getEvents();
                for (EventRepresentation event : raw) {
                    ServiceLogReader.StoredChunk chunk = toChunk(event);
                    if (chunk != null) {
                        chunks.add(chunk);
                    }
                }
                result.set(new ServiceLogReader.Batch(chunks, raw.size()));
            } catch (RuntimeException e) {
                failure.set(e);
            }
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return result.get();
    }

    /**
     * The stored chunk of an event, or {@code null} if it is not one. Anyone who may create events in the tenant can
     * create one of this type with arbitrary content, so a malformed one is skipped instead of failing every read.
     */
    static ServiceLogReader.StoredChunk toChunk(EventRepresentation event) {
        Object fragment = event.getProperty(EventServiceLogSink.FRAGMENT);
        if (!(fragment instanceof Map<?, ?> m) || m.get("dateTo") == null || m.get("dateFrom") == null) {
            return null;
        }
        try {
            return new ServiceLogReader.StoredChunk(Instant.parse(m.get("dateFrom").toString()),
                    Instant.parse(m.get("dateTo").toString()),
                    m.get("instance") == null ? null : m.get("instance").toString(), number(m.get("lines")),
                    number(m.get("omitted")), m.get("logs") == null ? "" : m.get("logs").toString());
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    private static int number(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }
}
