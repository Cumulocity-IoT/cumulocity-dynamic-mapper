/*
 * Copyright (c) 2025 Cumulocity GmbH.
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

import static com.cumulocity.rest.representation.alarm.AlarmMediaType.ALARM;
import static com.cumulocity.rest.representation.event.EventMediaType.EVENT;
import static com.cumulocity.rest.representation.measurement.MeasurementMediaType.MEASUREMENT_COLLECTION;

import java.util.List;
import java.util.concurrent.Semaphore;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.client.RestTemplate;

import com.cumulocity.microservice.api.CumulocityClientProperties;
import com.cumulocity.microservice.context.ContextService;
import com.cumulocity.microservice.context.credentials.MicroserviceCredentials;
import com.cumulocity.rest.representation.alarm.AlarmRepresentation;
import com.cumulocity.rest.representation.event.EventRepresentation;
import com.cumulocity.rest.representation.measurement.MeasurementCollectionRepresentation;
import com.cumulocity.rest.representation.measurement.MeasurementRepresentation;
import com.cumulocity.rest.representation.operation.OperationRepresentation;
import com.cumulocity.sdk.client.ProcessingMode;
import com.cumulocity.sdk.client.alarm.AlarmApi;
import com.cumulocity.sdk.client.devicecontrol.DeviceControlApi;
import com.cumulocity.sdk.client.event.EventApi;
import com.cumulocity.sdk.client.measurement.MeasurementApi;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dynamic.mapper.model.DynamicMapperRequest;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.runtime.ProcessingContext;
import dynamic.mapper.processor.runtime.ProcessingResultWrapper;
import lombok.extern.slf4j.Slf4j;

/**
 * Sends the requests a mapping produced to Cumulocity: an event, alarm, measurement, operation, or a call to a
 * custom route.
 * <p>
 * Every call takes a permit of the request semaphore of {@link C8YAgent} first, and checks for a cancellation of
 * the processing after it got the permit and before the HTTP call, so a message that already timed out does not
 * send anything. In TRANSIENT processing mode events, alarms, and measurements go through the transient connector
 * instead of the persistent APIs. Errors of the platform are not handled here: the caller decides what an
 * {@link com.cumulocity.sdk.client.SDKException} means for the message.
 * <p>
 * Owned by {@link C8YAgent}, which shares its semaphore and its API clients.
 */
@Slf4j
final class ApiRequestSender {

    /** Work that needs a permit. */
    @FunctionalInterface
    private interface Call<T> {
        T run() throws Exception;
    }

    /** What to do with a created event before its creation is logged, for example upload its attachment. */
    @FunctionalInterface
    interface AfterEventCreated {
        void accept(EventRepresentation event) throws Exception;
    }

    private final Semaphore c8ySemaphore;
    private final EventApi eventApi;
    private final AlarmApi alarmApi;
    private final MeasurementApi measurementApi;
    private final DeviceControlApi deviceControlApi;
    private final ProcessingModeService processingModeService;
    private final ObjectMapper objectMapper;
    private final ContextService<MicroserviceCredentials> contextService;
    private final CumulocityClientProperties clientProperties;

    ApiRequestSender(Semaphore c8ySemaphore, EventApi eventApi, AlarmApi alarmApi, MeasurementApi measurementApi,
            DeviceControlApi deviceControlApi, ProcessingModeService processingModeService,
            ObjectMapper objectMapper, ContextService<MicroserviceCredentials> contextService,
            CumulocityClientProperties clientProperties) {
        this.c8ySemaphore = c8ySemaphore;
        this.eventApi = eventApi;
        this.alarmApi = alarmApi;
        this.measurementApi = measurementApi;
        this.deviceControlApi = deviceControlApi;
        this.processingModeService = processingModeService;
        this.objectMapper = objectMapper;
        this.contextService = contextService;
        this.clientProperties = clientProperties;
    }

    EventRepresentation sendEvent(ProcessingContext<?> context, String payload, boolean logPayload,
            AfterEventCreated afterCreated) throws Exception {
        String tenant = context.getTenant();
        EventRepresentation event = objectMapper.readValue(payload, EventRepresentation.class);
        EventRepresentation created = withPermit(context, "EVENT", "event", () -> postEvent(context, tenant, event));
        afterCreated.accept(created);
        if (logPayload) {
            log.info("{} - SEND: event posted: {}", tenant, created);
        } else {
            log.info("{} - SEND: event posted with Id {}", tenant, created.getId().getValue());
        }
        return created;
    }

    AlarmRepresentation sendAlarm(ProcessingContext<?> context, String payload, boolean logPayload)
            throws Exception {
        String tenant = context.getTenant();
        AlarmRepresentation alarm = objectMapper.readValue(payload, AlarmRepresentation.class);
        AlarmRepresentation created = withPermit(context, "ALARM", "alarm", () -> postAlarm(context, tenant, alarm));
        if (logPayload) {
            log.info("{} - SEND: alarm posted: {}", tenant, created);
        } else {
            log.info("{} - SEND: alarm posted with Id {}", tenant, created.getId().getValue());
        }
        return created;
    }

    MeasurementCollectionRepresentation sendMeasurements(ProcessingContext<?> context, String payload,
            boolean logPayload) throws Exception {
        String tenant = context.getTenant();
        // Auto-detect payload format: { "measurements": [...] } = collection; anything else = single.
        // Always POST via createBulk: single measurements are wrapped in a one-element collection.
        MeasurementCollectionRepresentation measurements;
        if (objectMapper.readTree(payload).has("measurements")) {
            measurements = objectMapper.readValue(payload, MeasurementCollectionRepresentation.class);
        } else {
            MeasurementRepresentation single = objectMapper.readValue(payload, MeasurementRepresentation.class);
            measurements = new MeasurementCollectionRepresentation();
            measurements.setMeasurements(List.of(single));
        }
        MeasurementCollectionRepresentation created = withPermit(context, "MEASUREMENT", "measurement(s)",
                () -> postMeasurements(context, tenant, measurements));
        if (logPayload) {
            log.info("{} - SEND: measurement(s) posted: {}", tenant, created);
        } else {
            int count = measurements.getMeasurements() != null ? measurements.getMeasurements().size() : 0;
            log.info("{} - SEND: measurement(s) posted: {} measurement(s)", tenant, count);
        }
        return created;
    }

    OperationRepresentation sendOperation(ProcessingContext<?> context, String payload, boolean logPayload)
            throws Exception {
        String tenant = context.getTenant();
        // C8Y DeviceControl API rejects 'status' on create (HTTP 422 "status is not allowed here")
        JsonNode node = objectMapper.readTree(payload);
        if (node.isObject()) {
            ((ObjectNode) node).remove("status");
        }
        OperationRepresentation operation = objectMapper.treeToValue(node, OperationRepresentation.class);
        OperationRepresentation created = withPermit(context, "OPERATION", "Operation",
                () -> deviceControlApi.create(operation));
        if (logPayload) {
            log.info("{} - SEND: operation posted: {}", tenant, created);
        } else {
            log.info("{} - SEND: operation posted with Id {}", tenant,
                    created != null ? created.getId().getValue() : "null");
        }
        return created;
    }

    /** A custom route is called without a permit, but still not after the processing was cancelled. */
    void sendCustom(ProcessingContext<?> context, DynamicMapperRequest request) throws ProcessingException {
        throwIfCancelled(context, "CUSTOM");
        callCustomRoute(context.getTenant(), request, request.getRequest());
    }

    /**
     * Runs {@code call} while holding a permit of the request semaphore, after checking that the processing was not
     * cancelled in the meantime. The permit is released only if it was acquired.
     */
    private <T> T withPermit(ProcessingContext<?> context, String api, String what, Call<T> call)
            throws Exception {
        boolean acquired = false;
        try {
            c8ySemaphore.acquire();
            acquired = true;
            throwIfCancelled(context, api);
            return call.run();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("{} - Failed to acquire semaphore for creating {}", context.getTenant(), what, e);
            throw new ProcessingException("Interrupted while sending the " + what + " to Cumulocity", e);
        } finally {
            if (acquired) {
                c8ySemaphore.release();
            }
        }
    }

    private static void throwIfCancelled(ProcessingContext<?> context, String api) throws ProcessingException {
        ProcessingResultWrapper<?> wrapper = context.getProcessingResultWrapper();
        if (wrapper != null && wrapper.getCancellationRequested().get()) {
            log.info("{} - Cancellation detected before {} API call, aborting", context.getTenant(), api);
            throw new ProcessingException("Processing cancelled before " + api + " API call");
        }
    }

    private static boolean isTransient(ProcessingContext<?> context) {
        return context.getProcessingMode() != null && ProcessingMode.TRANSIENT.equals(context.getProcessingMode());
    }

    /** Creates the event, through the transient connector when the context asks for TRANSIENT processing. */
    private EventRepresentation postEvent(ProcessingContext<?> context, String tenant, EventRepresentation event) throws Exception {
        if (isTransient(context)) {
            EventRepresentation created = processingModeService.callWithProcessingMode("TRANSIENT",
                    (connector) -> (EventRepresentation) connector.post("/event/events", EVENT, event));
            log.info("{} - Using TRANSIENT processing mode for event", tenant);
            return created;
        }
        log.debug("{} - Using PERSISTENT processing mode for event", tenant);
        return eventApi.create(event);
    }

    /** Creates the alarm, through the transient connector when the context asks for TRANSIENT processing. */
    private AlarmRepresentation postAlarm(ProcessingContext<?> context, String tenant, AlarmRepresentation alarm) throws Exception {
        if (isTransient(context)) {
            AlarmRepresentation created = processingModeService.callWithProcessingMode("TRANSIENT",
                    (connector) -> (AlarmRepresentation) connector.post("/alarm/alarms", ALARM, alarm));
            log.info("{} - Using TRANSIENT processing mode for alarm", tenant);
            return created;
        }
        log.debug("{} - Using PERSISTENT processing mode for alarm", tenant);
        return alarmApi.create(alarm);
    }

    /** Creates the measurements in bulk, through the transient connector when the context asks for TRANSIENT processing. */
    private MeasurementCollectionRepresentation postMeasurements(ProcessingContext<?> context, String tenant,
            MeasurementCollectionRepresentation measurements) throws Exception {
        if (isTransient(context)) {
            MeasurementCollectionRepresentation created = processingModeService.callWithProcessingMode("TRANSIENT",
                    (connector) -> (MeasurementCollectionRepresentation) connector.post(
                            C8YAgent.MEASUREMENT_COLLECTION_PATH, MEASUREMENT_COLLECTION, measurements));
            log.info("{} - Using TRANSIENT processing mode for measurement(s)", tenant);
            return created;
        }
        log.debug("{} - Using PERSISTENT processing mode for measurement(s)", tenant);
        return measurementApi.createBulk(measurements);
    }

    /** Calls a custom Cumulocity route with the request's method and payload; the body of the answer becomes the response. */
    private void callCustomRoute(String tenant, DynamicMapperRequest request, String payload) {
        String customPath = request.getPathCumulocity();
        RequestMethod requestMethod = request.getMethod();
        HttpMethod httpMethod;
        switch (requestMethod != null ? requestMethod : RequestMethod.POST) {
            case PUT: httpMethod = HttpMethod.PUT; break;
            case PATCH: httpMethod = HttpMethod.PATCH; break;
            case DELETE: httpMethod = HttpMethod.DELETE; break;
            case GET: httpMethod = HttpMethod.GET; break;
            default: httpMethod = HttpMethod.POST; break;
        }
        HttpHeaders customHeaders = new HttpHeaders();
        customHeaders.set("Authorization",
                contextService.getContext().toCumulocityCredentials().getAuthenticationString());
        customHeaders.setContentType(MediaType.APPLICATION_JSON);
        String customUrl = clientProperties.getBaseURL() + customPath;
        HttpEntity<String> customEntity = new HttpEntity<>(payload, customHeaders);
        ResponseEntity<String> customResponse = new RestTemplate().exchange(
                customUrl, httpMethod, customEntity, String.class);
        request.setResponse(customResponse.getBody());
        log.info("{} - SEND: custom route called: path={}, method={}, status={}",
                tenant, customPath, requestMethod, customResponse.getStatusCode());
    }
}
