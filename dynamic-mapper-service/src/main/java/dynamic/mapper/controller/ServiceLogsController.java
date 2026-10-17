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

package dynamic.mapper.controller;

import dynamic.mapper.logs.collect.ServiceLogCollectorJob;
import dynamic.mapper.logs.read.ServiceLogReader;
import dynamic.mapper.util.MappingLogContext;

import java.time.Instant;
import java.time.format.DateTimeParseException;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.cumulocity.microservice.context.ContextService;
import com.cumulocity.microservice.context.credentials.UserCredentials;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * The collected service log of the calling tenant, for tenants that cannot read the native log of a
 * microservice they merely subscribe to (docs/feature/service-logs.md).
 *
 * <p>Restricted to {@code ROLE_DYNAMIC_MAPPER_ADMIN}: the log describes the tenant's devices and connectors.
 * Note the limit of that restriction — the underlying events are ordinary platform events, so a user who holds
 * the platform's event-read permission can still list them through the platform's own API; this endpoint
 * decides who may read them <em>through the mapper</em>.
 */
@RestController
@RequestMapping("/monitoring/service-logs")
@Tag(name = "Service Logs Controller", description = "The collected log of this microservice for the calling tenant")
public class ServiceLogsController {

    private final ContextService<UserCredentials> contextService;
    private final ServiceLogReader reader;
    private final ObjectProvider<ServiceLogCollectorJob> collector;

    public ServiceLogsController(ContextService<UserCredentials> contextService, ServiceLogReader.Events events,
            @Value("${service.logs.reader.max-chunks:10}") int maxChunks, ObjectProvider<ServiceLogCollectorJob> collector) {
        this.contextService = contextService;
        this.collector = collector;
        this.reader = new ServiceLogReader(events, maxChunks);
    }

    @Operation(summary = "Read the collected service log", description = """
            Returns a window of the calling tenant's collected log lines, in the paging shape of the platform's native log.
            No parameter: the latest chunks. `dateFrom` only: the chunks after it (next page; use 1970-01-01T00:00:00Z for the first). \
            `dateTo` only: the chunks before it (previous page). `mapping` (a mapping's identifier) restricts the result to the \
            lines written while that mapping processed a payload; lines collected before this was introduced carry no \
            mapping and never match. Requires `ROLE_DYNAMIC_MAPPER_ADMIN`.""")
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ServiceLogReader.Page> read(
            @RequestParam(required = false) String dateFrom, @RequestParam(required = false) String dateTo,
            @RequestParam(required = false) String mapping) {
        if (!Utils.userHasMappingAdminRole()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Reading the service log requires ROLE_DYNAMIC_MAPPER_ADMIN");
        }
        String tenant = contextService.getContext().getTenant();
        Instant from;
        Instant to;
        try {
            from = parse(dateFrom);
            to = parse(dateTo);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Dates must be ISO-8601, e.g. 2026-10-15T12:00:00Z");
        }
        String mappingFilter = mapping == null || mapping.isBlank() ? null : mapping;
        // The value becomes part of an event query: refuse anything that is not an identifier.
        if (mappingFilter != null && !MappingLogContext.isValid(mappingFilter)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "mapping must be a mapping identifier");
        }
        return ResponseEntity.ok(reader.read(tenant, from, to, mappingFilter));
    }

    @Operation(summary = "Store the calling tenant's waiting log lines now", description = """
            Stores the lines this service instance has captured for the calling tenant but not yet written, instead of \
            waiting for the collection interval, so a following read returns them. Only the instance that handles the \
            request is flushed. Requires `ROLE_DYNAMIC_MAPPER_ADMIN`.""")
    @PostMapping("/flush")
    public ResponseEntity<Void> flush() {
        if (!Utils.userHasMappingAdminRole()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Fetching the service log requires ROLE_DYNAMIC_MAPPER_ADMIN");
        }
        ServiceLogCollectorJob job = collector.getIfAvailable();
        if (job != null) { // absent when the operator switched collection off: nothing is waiting then
            job.flushTenant(contextService.getContext().getTenant());
        }
        return ResponseEntity.noContent().build();
    }

    private static Instant parse(String value) {
        return value == null || value.isBlank() ? null : Instant.parse(value);
    }
}
