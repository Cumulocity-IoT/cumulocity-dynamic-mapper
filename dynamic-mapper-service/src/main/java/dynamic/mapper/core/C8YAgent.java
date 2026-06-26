/*
 * Copyright (c) 2022-2025 Cumulocity GmbH.
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

import static java.util.Map.entry;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;

import com.cumulocity.rest.representation.user.UserRepresentation;
import com.cumulocity.sdk.client.user.UserApi;
import org.joda.time.DateTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.client.RestTemplate;
import com.cumulocity.microservice.api.CumulocityClientProperties;
import com.cumulocity.microservice.context.ContextService;
import com.cumulocity.microservice.context.credentials.MicroserviceCredentials;
import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;
import com.cumulocity.model.Agent;
import com.cumulocity.model.ID;
import com.cumulocity.model.idtype.GId;
import com.cumulocity.model.measurement.MeasurementValue;
import com.cumulocity.model.operation.OperationStatus;
import com.cumulocity.rest.representation.AbstractExtensibleRepresentation;
import com.cumulocity.rest.representation.alarm.AlarmRepresentation;
import com.cumulocity.rest.representation.event.EventRepresentation;
import com.cumulocity.rest.representation.identity.ExternalIDRepresentation;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.cumulocity.rest.representation.measurement.MeasurementCollectionRepresentation;
import com.cumulocity.rest.representation.measurement.MeasurementRepresentation;
import com.cumulocity.rest.representation.operation.OperationRepresentation;
import com.cumulocity.sdk.client.ProcessingMode;
import com.cumulocity.sdk.client.SDKException;
import com.cumulocity.sdk.client.alarm.AlarmApi;
import com.cumulocity.sdk.client.buffering.Future;
import com.cumulocity.sdk.client.devicecontrol.DeviceControlApi;
import com.cumulocity.sdk.client.event.EventApi;
import com.cumulocity.sdk.client.measurement.MeasurementApi;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import c8y.IsDevice;
import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.connector.core.client.Certificate;
import dynamic.mapper.core.cache.InboundExternalIdCache;
import dynamic.mapper.core.cache.InventoryCache;
import dynamic.mapper.core.facade.IdentityFacade;
import dynamic.mapper.core.facade.InventoryFacade;
import dynamic.mapper.model.API;
import dynamic.mapper.model.BinaryInfo;
import dynamic.mapper.model.ConnectorStatus;
import dynamic.mapper.model.LoggingEventType;
import dynamic.mapper.model.MapperServiceRepresentation;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.model.DynamicMapperRequest;
import dynamic.mapper.processor.model.ExternalId;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.service.ExtensionInboundRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import static com.cumulocity.rest.representation.measurement.MeasurementMediaType.MEASUREMENT_COLLECTION;
import static com.cumulocity.rest.representation.event.EventMediaType.EVENT;
import static com.cumulocity.rest.representation.alarm.AlarmMediaType.ALARM;;

@Slf4j
@Component
public class C8YAgent implements ImportBeanDefinitionRegistrar, InventoryEnrichmentClient, IdentityResolver {

    ConnectorStatus previousConnectorStatus = ConnectorStatus.UNKNOWN;

    private final EventApi eventApi;
    private final InventoryFacade inventoryApi;
    private final IdentityFacade identityApi;
    private final MeasurementApi measurementApi;
    private final AlarmApi alarmApi;
    private final UserApi userApi;
    private final DeviceControlApi deviceControlApi;
    private final ProcessingModeService processingModeService;
    private final MicroserviceSubscriptionsService subscriptionsService;
    private final ContextService<MicroserviceCredentials> contextService;
    private final IMapperConfiguration mapperConfiguration;

    @Getter
    private final ExtensionInboundRegistry extensionInboundRegistry;

    final CumulocityClientProperties clientProperties;
    private final ObjectMapper objectMapper;
    private final TenantRegistry tenantRegistry;
    private final ExtensionManager extensionManager;
    private final CacheManager cacheManager;
    private final CertificateService certificateService;
    private final BinaryAttachmentService binaryAttachmentService;
    private final DeviceBootstrapService deviceBootstrapService;
    private final InventoryCacheEnrichmentService inventoryCacheEnrichmentService;

    private final String version;
    private final Integer maxConnections;
    private final Semaphore c8ySemaphore;

    private static final String C8Y_NOTIFICATION_CONNECTOR = "C8YNotificationConnector";

    public static final String MEASUREMENT_COLLECTION_PATH = "/measurement/measurements";

    private Timer c8yRequestTimer = Timer.builder("dynmapper_c8y_request_processing_time")
            .description("C8Y Request Processing time").register(Metrics.globalRegistry);

    public C8YAgent(
            EventApi eventApi,
            InventoryFacade inventoryApi,
            IdentityFacade identityApi,
            MeasurementApi measurementApi,
            AlarmApi alarmApi,
            UserApi userApi,
            DeviceControlApi deviceControlApi,
            ProcessingModeService processingModeService,
            MicroserviceSubscriptionsService subscriptionsService,
            ContextService<MicroserviceCredentials> contextService,
            IMapperConfiguration mapperConfiguration,
            ExtensionInboundRegistry extensionInboundRegistry,
            CumulocityClientProperties clientProperties,
            ObjectMapper objectMapper,
            TenantRegistry tenantRegistry,
            ExtensionManager extensionManager,
            CacheManager cacheManager,
            CertificateService certificateService,
            BinaryAttachmentService binaryAttachmentService,
            DeviceBootstrapService deviceBootstrapService,
            InventoryCacheEnrichmentService inventoryCacheEnrichmentService,
            @Value("${application.version}") String version,
            @Value("#{new Integer('${C8Y.httpClient.pool.perHost}')}") Integer maxConnections) {
        this.eventApi = eventApi;
        this.inventoryApi = inventoryApi;
        this.identityApi = identityApi;
        this.measurementApi = measurementApi;
        this.alarmApi = alarmApi;
        this.userApi = userApi;
        this.deviceControlApi = deviceControlApi;
        this.processingModeService = processingModeService;
        this.subscriptionsService = subscriptionsService;
        this.contextService = contextService;
        this.mapperConfiguration = mapperConfiguration;
        this.extensionInboundRegistry = extensionInboundRegistry;
        this.clientProperties = clientProperties;
        this.objectMapper = objectMapper;
        this.tenantRegistry = tenantRegistry;
        this.extensionManager = extensionManager;
        this.cacheManager = cacheManager;
        this.certificateService = certificateService;
        this.binaryAttachmentService = binaryAttachmentService;
        this.deviceBootstrapService = deviceBootstrapService;
        this.inventoryCacheEnrichmentService = inventoryCacheEnrichmentService;
        this.version = version;
        this.maxConnections = maxConnections;
        this.c8ySemaphore = new Semaphore(maxConnections, false);
    }

    @PostConstruct
    private void init() {
        Gauge.builder("dynmapper_available_c8y_connections", this.c8ySemaphore, Semaphore::availablePermits)
                .register(Metrics.globalRegistry);
    }

    public Semaphore getC8ySemaphore() {
        return c8ySemaphore;
    }

    public void createExtensibleProcessor(String tenant) {
        extensionManager.createExtensibleProcessor(tenant, inventoryApi);
    }

    public ExternalIDRepresentation resolveExternalId2GlobalId(String tenant, ID identity,
            Boolean testing) {
        if (identity.getType() == null) {
            identity.setType("c8y_Serial");
        }
        ExternalIDRepresentation result = subscriptionsService.callForTenant(tenant, () -> {
            try {
                ExternalIDRepresentation resultInner = cacheManager.getInboundExternalIdCache(tenant)
                        .getIdByExternalId(identity);
                Counter.builder("dynmapper_inbound_identity_requests_total").tag("tenant", tenant)
                        .register(Metrics.globalRegistry).increment();
                if (resultInner == null) {
                    resultInner = identityApi.resolveExternalId2GlobalId(identity, testing, c8ySemaphore);
                    if (!testing) {
                        cacheManager.getInboundExternalIdCache(tenant).putIdForExternalId(identity,
                                resultInner);
                    }

                } else {
                    log.debug("{} - Cache hit for external ID {} -> {}", tenant, identity.getValue(),
                            resultInner.getManagedObject().getId().getValue());
                    Counter.builder("dynmapper_inbound_identity_cache_hits_total").tag("tenant", tenant)
                            .register(Metrics.globalRegistry).increment();
                }
                return resultInner;
            } catch (SDKException e) {
                log.warn("{} - External ID {} not found", tenant, identity.getValue());
            }
            return null;
        });
        return result;
    }

    public ExternalIDRepresentation resolveGlobalId2ExternalId(String tenant, GId gid, String idType,
            Boolean testing) {
        // TODO Use Cache
        if (idType == null) {
            idType = "c8y_Serial";
        }
        final String idt = idType;
        ExternalIDRepresentation result = subscriptionsService.callForTenant(tenant, () -> {
            try {
                return identityApi.resolveGlobalId2ExternalId(gid, idt, testing, c8ySemaphore);
            } catch (SDKException e) {
                log.warn("{} - External ID type {} for {} not found", tenant, idt, gid.getValue());
            }
            return null;
        });
        return result;
    }

    public MeasurementRepresentation createMeasurement(String name, String type, ManagedObjectRepresentation mor,
            DateTime dateTime, HashMap<String, MeasurementValue> mvMap, String tenant) {
        MeasurementRepresentation measurementRepresentation = new MeasurementRepresentation();
        subscriptionsService.runForTenant(tenant, () -> {
            MicroserviceCredentials context = removeAppKeyHeaderFromContext(contextService.getContext());
            contextService.runWithinContext(context, () -> {
                try {
                    measurementRepresentation.set(mvMap, name);
                    measurementRepresentation.setType(type);
                    measurementRepresentation.setSource(mor);
                    measurementRepresentation.setDateTime(dateTime);
                    log.debug("{} - Creating Measurement {}", tenant, measurementRepresentation);
                    MeasurementRepresentation mrn = null;
                    try {
                        c8ySemaphore.acquire();
                        mrn = measurementApi.create(measurementRepresentation);
                        measurementRepresentation.setId(mrn.getId());
                    } catch (InterruptedException e) {
                        log.error("{} - Failed to acquire semaphore for creating Measurement", tenant, e);
                    } finally {
                        c8ySemaphore.release();
                    }
                } catch (SDKException e) {
                    log.error("{} - Error creating Measurement", tenant, e);
                }
            });
        });
        return measurementRepresentation;
    }

    public AlarmRepresentation createAlarm(String severity, String message, String type, DateTime alarmTime,
            ManagedObjectRepresentation parentMor, String tenant) {
        AlarmRepresentation alarmRepresentation = subscriptionsService.callForTenant(tenant, () -> {
            MicroserviceCredentials context = removeAppKeyHeaderFromContext(contextService.getContext());
            return contextService.callWithinContext(context, () -> {
                AlarmRepresentation ar = new AlarmRepresentation();
                ar.setSeverity(severity);
                ar.setSource(parentMor);
                ar.setText(message);
                ar.setDateTime(alarmTime);
                ar.setStatus("ACTIVE");
                ar.setType(type);
                try {
                    c8ySemaphore.acquire();
                    ar = this.alarmApi.create(ar);
                } catch (InterruptedException e) {
                    log.error("{} - Failed to acquire semaphore for creating Alarm", tenant, e);
                } finally {
                    c8ySemaphore.release();
                }
                return ar;
            });
        });
        return alarmRepresentation;
    }

    public void createOperationEvent(String message, LoggingEventType loggingType, DateTime eventTime,
            String tenant, Map<String, String> properties) {
        MapperServiceRepresentation source = mapperConfiguration.getMapperServiceRepresentation(tenant);
        subscriptionsService.runForTenant(tenant, () -> {
            MicroserviceCredentials context = removeAppKeyHeaderFromContext(contextService.getContext());
            contextService.runWithinContext(context, () -> {
                EventRepresentation er = new EventRepresentation();
                ManagedObjectRepresentation mor = new ManagedObjectRepresentation();
                mor.setId(new GId(source.getId()));
                er.setSource(mor);
                er.setText(message);
                er.setDateTime(eventTime);
                er.setType(loggingType.type);
                if (properties != null) {
                    er.setProperty(loggingType.getComponent(), properties);
                }

                // Add metadata fragment for self-contained events
                Map<String, String> metadata = Map.of(
                    "component", loggingType.getComponent(),
                    "componentDisplayName", loggingType.getComponentDisplayName(),
                    "severity", loggingType.getSeverity(),
                    "description", loggingType.getDescription()
                );
                er.setProperty("d11r_metadata", metadata);

                try {
                    c8ySemaphore.acquire();
                    // this.initializeMapperServiceObject(tenant), add the new mo to the
                    // configuration registry and retry the API call
                    Future result = this.eventApi.createAsync(er);
                    // configurationRegistry.getVirtualThreadPool().submit(() -> {
                    // try {
                    // EventRepresentation oneEvent = (EventRepresentation) result.get();
                    // } catch (SDKException e) {
                    // log.error("{} - Failed to send event", tenant, e);
                    // if (e.getHttpStatus() == 404 || e.getHttpStatus() == 422) {
                    // log.warn("{} - Try to recreate the Agent with external ID", tenant);
                    // MapperServiceRepresentation sourceNew = configurationRegistry
                    // .initializeMapperServiceRepresentation(tenant);
                    // mor.setId(new GId(sourceNew.getId()));
                    // er.setSource(mor);
                    // er.setText(message);
                    // er.setDateTime(eventTime);
                    // er.setType(loggingType.type);
                    // this.eventApi.createAsync(er);
                    // }
                    // } catch (Exception e) {
                    // log.error("{} - Failed to send event", tenant, e);
                    // }

                    // });
                } catch (InterruptedException e) {
                    log.error("{} - Failed to acquire semaphore for creating Event", tenant, e);
                } finally {
                    c8ySemaphore.release();
                }
            });
        });
    }

    public Certificate loadCertificateByName(String certificateName, String fingerprint,
            String tenant, String connectorName) {
        return certificateService.loadCertificateByName(certificateName, fingerprint, tenant, connectorName);
    }

    public CompletableFuture<AbstractExtensibleRepresentation> createMEAOAsync(ProcessingContext<?> context,
            int requestIndex)
            throws ProcessingException {
        return CompletableFuture.supplyAsync(() -> {
            String tenant = context.getTenant();
            StringBuffer error = new StringBuffer("");
            DynamicMapperRequest currentRequest = context.getRequests().get(requestIndex);
            String payload = currentRequest.getRequest();
            // API is now always initialized when creating DynamicMapperRequest
            API targetAPI = currentRequest.getApi();
            AbstractExtensibleRepresentation result = subscriptionsService.callForTenant(tenant, () -> {
                MicroserviceCredentials contextCredentials = removeAppKeyHeaderFromContext(contextService.getContext());
                return contextService.callWithinContext(contextCredentials, () -> {
                    AbstractExtensibleRepresentation rt = null;
                    try {
                        if (targetAPI.equals(API.EVENT)) {
                            EventRepresentation eventRepresentation = objectMapper.readValue(
                                    payload,
                                    EventRepresentation.class);
                            // Set processing mode for events
                            if (context.getProcessingMode() != null &&
                                    ProcessingMode.TRANSIENT.equals(context.getProcessingMode())) {
                                rt = processingModeService.callWithProcessingMode("TRANSIENT", (connector) -> {
                                    if (targetAPI.equals(API.EVENT)) {
                                        // Now use the connector with the processing mode header
                                        return (EventRepresentation) connector.post("/event/events",
                                                EVENT,
                                                eventRepresentation);
                                    }
                                    return null;
                                });
                                log.info("{} - Using TRANSIENT processing mode for event", tenant);
                            } else {
                                rt = eventApi.create(eventRepresentation);
                                log.debug("{} - Using PERSISTENT processing mode for event", tenant);
                            }
                            log.info("{} - SEND: event posted with Id {}", tenant,
                                    ((EventRepresentation) rt).getId().getValue());
                        } else if (targetAPI.equals(API.ALARM)) {
                            AlarmRepresentation alarmRepresentation = objectMapper.readValue(
                                    payload,
                                    AlarmRepresentation.class);
                            // Set processing mode for alarms
                            if (context.getProcessingMode() != null &&
                                    ProcessingMode.TRANSIENT.equals(context.getProcessingMode())) {
                                rt = processingModeService.callWithProcessingMode("TRANSIENT", (connector) -> {
                                    if (targetAPI.equals(API.ALARM)) {
                                        // Now use the connector with the processing mode header
                                        return (AlarmRepresentation) connector.post("/alarm/alarms",
                                                ALARM,
                                                alarmRepresentation);
                                    }
                                    return null;
                                });
                                log.info("{} - Using TRANSIENT processing mode for alarm", tenant);
                            } else {
                                rt = alarmApi.create(alarmRepresentation);
                                log.debug("{} - Using PERSISTENT processing mode for alarm", tenant);
                            }
                            log.info("{} - SEND: alarm posted with Id {}", tenant,
                                    ((AlarmRepresentation) rt).getId().getValue());
                        } else if (targetAPI.equals(API.MEASUREMENT)) {
                            // Auto-detect payload format: { "measurements": [...] } = collection; anything else = single.
                            // Always POST via createBulk — single measurements are wrapped in a one-element collection.
                            MeasurementCollectionRepresentation collectionRepresentation;
                            if (objectMapper.readTree(payload).has("measurements")) {
                                collectionRepresentation = objectMapper
                                        .readValue(payload, MeasurementCollectionRepresentation.class);
                            } else {
                                MeasurementRepresentation mr = objectMapper
                                        .readValue(payload, MeasurementRepresentation.class);
                                collectionRepresentation = new MeasurementCollectionRepresentation();
                                collectionRepresentation.setMeasurements(List.of(mr));
                            }
                            if (context.getProcessingMode() != null &&
                                    ProcessingMode.TRANSIENT.equals(context.getProcessingMode())) {
                                final MeasurementCollectionRepresentation col = collectionRepresentation;
                                rt = processingModeService.callWithProcessingMode("TRANSIENT", (connector) ->
                                        (MeasurementCollectionRepresentation) connector.post(
                                                MEASUREMENT_COLLECTION_PATH, MEASUREMENT_COLLECTION, col));
                                log.info("{} - Using TRANSIENT processing mode for measurement(s)", tenant);
                            } else {
                                rt = measurementApi.createBulk(collectionRepresentation);
                                log.debug("{} - Using PERSISTENT processing mode for measurement(s)", tenant);
                            }
                            int count = collectionRepresentation.getMeasurements() != null
                                    ? collectionRepresentation.getMeasurements().size() : 0;
                            log.info("{} - SEND: measurement(s) posted: {} measurement(s)", tenant, count);
                        } else if (targetAPI.equals(API.OPERATION)) {
                            OperationRepresentation operationRepresentation = objectMapper
                                    .readValue(
                                            payload, OperationRepresentation.class);
                            rt = deviceControlApi.create(operationRepresentation);
                            log.info("{} - SEND: operation posted with Id {}", tenant,
                                    rt != null ? ((OperationRepresentation) rt).getId().getValue() : "null");
                        } else if (targetAPI.equals(API.CUSTOM)) {
                            String customPath = currentRequest.getPathCumulocity();
                            RequestMethod requestMethod = currentRequest.getMethod();
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
                            currentRequest.setResponse(customResponse.getBody());
                            log.info("{} - SEND: custom route called: path={}, method={}, status={}",
                                    tenant, customPath, requestMethod, customResponse.getStatusCode());
                        } else {
                            log.error("{} - Not existing API!", tenant);
                        }
                    } catch (JsonProcessingException e) {
                        log.error("{} - Could not map payload: {} {}", tenant, targetAPI, payload);
                        error.append("Could not map payload: " + targetAPI + "/" + payload);
                    } catch (SDKException s) {
                        log.error("{} - Could not sent payload to c8y: {} {}: ", tenant, targetAPI, payload, s);
                        error.append("Could not sent payload to c8y: " + targetAPI + "/" + payload + "/" + s);
                    }
                    return rt;
                });
            });
            if (!error.toString().equals("")) {
                throw new CompletionException(new ProcessingException(error.toString()));
            }
            return result;
        });

    }

    public AbstractExtensibleRepresentation createMEAO(ProcessingContext<?> context, int requestIndex)
            throws ProcessingException {
        // initializeTransientApis();
        // log.info("{} - C8Y Connections available: {}",
        // context.getTenant(),c8ySemaphore.availablePermits());
        String tenant = context.getTenant();
        // this.c8yRequestTimerMap.get(tenant);
        Timer.Sample timer = Timer.start(Metrics.globalRegistry);
        AtomicReference<ProcessingException> pe = new AtomicReference<>();
        DynamicMapperRequest currentRequest = context.getRequests().get(requestIndex);
        String payload = currentRequest.getRequest();
        ServiceConfiguration serviceConfiguration = tenantRegistry.getServiceConfiguration(tenant);
        // API is now always initialized when creating DynamicMapperRequest
        API targetAPI = currentRequest.getApi();

        // Check for cancellation BEFORE starting any C8Y API calls
        // This prevents unnecessary HTTP requests when the processing has already timed out
        dynamic.mapper.processor.model.ProcessingResultWrapper<?> wrapper = context.getProcessingResultWrapper();
        if (wrapper != null && wrapper.getCancellationRequested().get()) {
            log.info("{} - Cancellation detected in createMEAO before API call, aborting C8Y request for API: {}",
                    tenant, targetAPI);
            throw new ProcessingException("Processing cancelled before C8Y API call");
        }

        AbstractExtensibleRepresentation result = subscriptionsService.callForTenant(tenant, () -> {
            MicroserviceCredentials contextCredentials = removeAppKeyHeaderFromContext(contextService.getContext());
            return contextService.callWithinContext(contextCredentials, () -> {
                AbstractExtensibleRepresentation rt = null;
                try {
                    if (targetAPI.equals(API.EVENT)) {
                        EventRepresentation eventRepresentation = objectMapper.readValue(
                                payload,
                                EventRepresentation.class);
                        try {
                            c8ySemaphore.acquire();

                            // Check cancellation before making HTTP call
                            if (wrapper != null && wrapper.getCancellationRequested().get()) {
                                log.info("{} - Cancellation detected before EVENT API call, aborting", tenant);
                                throw new ProcessingException("Processing cancelled before EVENT API call");
                            }

                            // Set processing mode for events
                            if (context.getProcessingMode() != null &&
                                    ProcessingMode.TRANSIENT.equals(context.getProcessingMode())) {
                                rt = processingModeService.callWithProcessingMode("TRANSIENT", (connector) -> {
                                    if (targetAPI.equals(API.EVENT)) {
                                        // Now use the connector with the processing mode header
                                        return (EventRepresentation) connector.post("/event/events",
                                                EVENT,
                                                eventRepresentation);
                                    }
                                    return null;
                                });
                                log.info("{} - Using TRANSIENT processing mode for event", tenant);
                            } else {
                                rt = eventApi.create(eventRepresentation);
                                log.debug("{} - Using PERSISTENT processing mode for event", tenant);
                            }
                        } catch (InterruptedException e) {
                            log.error("{} - Failed to acquire semaphore for creating event", tenant, e);
                        } finally {
                            c8ySemaphore.release();
                        }
                        GId eventId = ((EventRepresentation) rt).getId();
                        if (context.getMapping().getEventWithAttachment()) {
                            BinaryInfo binaryInfo = context.getBinaryInfo();
                            uploadEventAttachment(binaryInfo, eventId.getValue(), false);
                        }
                        if (serviceConfiguration.getLogPayload())
                            log.info("{} - SEND: event posted: {}", tenant, rt);
                        else
                            log.info("{} - SEND: event posted with Id {}", tenant,
                                    ((EventRepresentation) rt).getId().getValue());

                    } else if (targetAPI.equals(API.ALARM)) {
                        AlarmRepresentation alarmRepresentation = objectMapper.readValue(
                                payload,
                                AlarmRepresentation.class);
                        try {
                            c8ySemaphore.acquire();

                            // Check cancellation before making HTTP call
                            if (wrapper != null && wrapper.getCancellationRequested().get()) {
                                log.info("{} - Cancellation detected before ALARM API call, aborting", tenant);
                                throw new ProcessingException("Processing cancelled before ALARM API call");
                            }

                            // Set processing mode for alarms
                            if (context.getProcessingMode() != null &&
                                    ProcessingMode.TRANSIENT.equals(context.getProcessingMode())) {
                                rt = processingModeService.callWithProcessingMode("TRANSIENT", (connector) -> {
                                    if (targetAPI.equals(API.ALARM)) {
                                        // Now use the connector with the processing mode header
                                        return (AlarmRepresentation) connector.post("/alarm/alarms",
                                                ALARM,
                                                alarmRepresentation);
                                    }
                                    return null;
                                });
                                log.info("{} - Using TRANSIENT processing mode for alarm", tenant);
                            } else {
                                rt = alarmApi.create(alarmRepresentation);
                                log.debug("{} - Using PERSISTENT processing mode for alarm", tenant);
                            }
                        } catch (InterruptedException e) {
                            log.error("{} - Failed to acquire semaphore for creating alarm", tenant, e);
                        } finally {
                            c8ySemaphore.release();
                        }
                        if (serviceConfiguration.getLogPayload())
                            log.info("{} - SEND: alarm posted: {}", tenant, rt);
                        else
                            log.info("{} - SEND: alarm posted with Id {}", tenant,
                                    ((AlarmRepresentation) rt).getId().getValue());
                    } else if (targetAPI.equals(API.MEASUREMENT)) {
                        // Auto-detect payload format: { "measurements": [...] } = collection; anything else = single.
                        // Always POST via createBulk — single measurements are wrapped in a one-element collection.
                        MeasurementCollectionRepresentation collectionRepresentation;
                        if (objectMapper.readTree(payload).has("measurements")) {
                            collectionRepresentation = objectMapper
                                    .readValue(payload, MeasurementCollectionRepresentation.class);
                        } else {
                            MeasurementRepresentation mr = objectMapper
                                    .readValue(payload, MeasurementRepresentation.class);
                            collectionRepresentation = new MeasurementCollectionRepresentation();
                            collectionRepresentation.setMeasurements(List.of(mr));
                        }
                        try {
                            c8ySemaphore.acquire();

                            // Check cancellation before making HTTP call
                            if (wrapper != null && wrapper.getCancellationRequested().get()) {
                                log.info("{} - Cancellation detected before MEASUREMENT API call, aborting", tenant);
                                throw new ProcessingException("Processing cancelled before MEASUREMENT API call");
                            }

                            if (context.getProcessingMode() != null &&
                                    ProcessingMode.TRANSIENT.equals(context.getProcessingMode())) {
                                final MeasurementCollectionRepresentation col = collectionRepresentation;
                                rt = processingModeService.callWithProcessingMode("TRANSIENT", (connector) ->
                                        (MeasurementCollectionRepresentation) connector.post(
                                                MEASUREMENT_COLLECTION_PATH, MEASUREMENT_COLLECTION, col));
                                log.info("{} - Using TRANSIENT processing mode for measurement(s)", tenant);
                            } else {
                                rt = measurementApi.createBulk(collectionRepresentation);
                                log.debug("{} - Using PERSISTENT processing mode for measurement(s)", tenant);
                            }
                        } catch (InterruptedException e) {
                            log.error("{} - Failed to acquire semaphore for creating measurement(s)", tenant, e);
                        } finally {
                            c8ySemaphore.release();
                        }
                        int count = collectionRepresentation.getMeasurements() != null
                                ? collectionRepresentation.getMeasurements().size() : 0;
                        if (serviceConfiguration.getLogPayload())
                            log.info("{} - SEND: measurement(s) posted: {}", tenant, rt);
                        else
                            log.info("{} - SEND: measurement(s) posted: {} measurement(s)", tenant, count);
                    } else if (targetAPI.equals(API.OPERATION)) {
                        // C8Y DeviceControl API rejects 'status' on create (HTTP 422 "status is not allowed here")
                        JsonNode opNode = objectMapper.readTree(payload);
                        if (opNode.isObject()) {
                            ((ObjectNode) opNode).remove("status");
                        }
                        OperationRepresentation operationRepresentation = objectMapper
                                .treeToValue(opNode, OperationRepresentation.class);
                        try {
                            c8ySemaphore.acquire();

                            // Check cancellation before making HTTP call
                            if (wrapper != null && wrapper.getCancellationRequested().get()) {
                                log.info("{} - Cancellation detected before OPERATION API call, aborting", tenant);
                                throw new ProcessingException("Processing cancelled before OPERATION API call");
                            }

                            rt = deviceControlApi.create(operationRepresentation);
                        } catch (InterruptedException e) {
                            log.error("{} - Failed to acquire semaphore for creating Operation", tenant, e);
                        } finally {
                            c8ySemaphore.release();
                        }
                        if (serviceConfiguration.getLogPayload())
                            log.info("{} - SEND: operation posted: {}", tenant, rt);
                        else
                            log.info("{} - SEND: operation posted with Id {}", tenant,
                                    rt != null ? ((OperationRepresentation) rt).getId().getValue() : "null");
                    } else if (targetAPI.equals(API.CUSTOM)) {
                        // Check cancellation before making HTTP call
                        if (wrapper != null && wrapper.getCancellationRequested().get()) {
                            log.info("{} - Cancellation detected before CUSTOM API call, aborting", tenant);
                            throw new ProcessingException("Processing cancelled before CUSTOM API call");
                        }

                        String customPath = currentRequest.getPathCumulocity();
                        RequestMethod requestMethod = currentRequest.getMethod();
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
                        currentRequest.setResponse(customResponse.getBody());
                        log.info("{} - SEND: custom route called: path={}, method={}, status={}",
                                tenant, customPath, requestMethod, customResponse.getStatusCode());
                    } else {
                        log.error("{} - Not existing API!", tenant);
                    }
                } catch (JsonProcessingException e) {
                    log.error("{} - Could not map payload: {} {}", tenant, targetAPI, payload, e.getMessage());
                    pe.set(new ProcessingException("Could not map payload: " + targetAPI + "/" + payload, e));
                    // error.append("Could not map payload: " + targetAPI + "/" + payload);
                } catch (SDKException s) {
                    log.error("{} - Could not sent payload to c8y: {} {}: ", tenant, targetAPI, payload,
                            s.getMessage());
                    pe.set(new ProcessingException("Could not sent payload to c8y: " + targetAPI + "/" + payload, s));

                    // Remove device from Cache
                    if (s.getHttpStatus() == 422) {
                        ID identity = new ID(currentRequest.getExternalId(), currentRequest.getExternalId());
                        this.removeDeviceFromInboundExternalIdCache(tenant, identity);
                    }
                }
                return rt;
            });
        });
        if (pe.get() != null) {
            throw pe.get();
        }
        timer.stop(this.c8yRequestTimer);
        return result;
    }

    public static final String MAPPING_TEST_DEVICE_TYPE = "d11r_testDevice";

    /**
     * Creates a managed object and binds its external ID using the optimistic
     * single-request approach where the platform supports it.
     *
     * <p>Cumulocity platforms &ge; May 2026 allow external IDs to be bound
     * atomically during MO creation by including an {@code externalIds}
     * property in the request body. On older platforms (e.g. Edge), this
     * implementation falls back to creating the managed object first and then
     * binding the external ID via a separate Identity API call.</p>
     *
     * <p>The caller must hold {@link #c8ySemaphore} before invoking this method.</p>
     *
     * @param mor      the managed object to create
     * @param identity the external ID to bind
     * @param testing  routing flag forwarded to the inventory/identity facades
     * @return the created managed object representation
     */
    private ManagedObjectRepresentation createWithExternalIdBinding(
            ManagedObjectRepresentation mor, ID identity, Boolean testing, boolean supportsExternalIdBinding) {
        if (Boolean.TRUE.equals(testing)) {
            // Mock / test path – use the simple two-step approach
            ManagedObjectRepresentation created = inventoryApi.create(mor, true);
            identityApi.create(created, identity, true);
            return created;
        }

        if (supportsExternalIdBinding) {
            // New API (platforms >= May 2026): bind the external ID atomically in the MO
            // creation body. The platform consumes the 'externalIds' directive and does
            // not persist it as a fragment on the created object.
            String identityType = identity.getType();
            String idType = identityType == null || identityType.isBlank() ? "c8y_Serial" : identityType;
            mor.setProperty("externalIds",
                    List.of(Map.of("type", idType, "externalId", identity.getValue())));
            log.debug("Creating MO with atomic externalIds binding");
            return inventoryApi.create(mor, false);
        }

        // Legacy path (platforms < May 2026, e.g. Cumulocity Edge): create MO first,
        // then bind the external ID via a separate Identity API call.
        log.debug("Creating MO + external ID binding via two separate calls (legacy platform path)");
        ManagedObjectRepresentation created = inventoryApi.create(mor, false);
        identityApi.create(created, identity, false);
        return created;
    }

    /**
     * Creates a real managed object in C8Y inventory tagged with {@code d11r_testDevice} so it
     * is visible in the Test Devices grid and can be cleaned up after testing.
     *
     * @return the internal C8Y ID of the created managed object, or {@code null} on failure
     */
    public String createTestDevice(String tenant, String deviceName, String externalId, String externalIdType) {
        String resolvedType = (externalIdType != null && !externalIdType.isEmpty()) ? externalIdType : "c8y_Serial";
        // Upsert: if the externalId is already registered, return the existing device ID
        ID id = new ID();
        id.setType(resolvedType);
        id.setValue(externalId);
        ExternalIDRepresentation existing = resolveExternalId2GlobalId(tenant, id, false);
        if (existing != null) {
            String existingId = existing.getManagedObject().getId().getValue();
            log.info("{} - Test device with externalId={} already exists: id={}, reusing it", tenant, externalId,
                    existingId);
            return existingId;
        }

        boolean supportsExternalIdBinding = Boolean.TRUE.equals(
                tenantRegistry.getServiceConfiguration(tenant).getExternalIdBinding());
        return subscriptionsService.callForTenant(tenant, () -> {
            MicroserviceCredentials contextCredentials = removeAppKeyHeaderFromContext(contextService.getContext());
            return contextService.callWithinContext(contextCredentials, () -> {
                ManagedObjectRepresentation mor = new ManagedObjectRepresentation();
                mor.setName(deviceName);
                mor.set(new IsDevice());
                mor.set(new HashMap<String, String>(), MAPPING_TEST_DEVICE_TYPE);
                try {
                    c8ySemaphore.acquire();
                    mor = createWithExternalIdBinding(mor, id, false, supportsExternalIdBinding);
                    log.info("{} - Test device created: id={}, name={}", tenant, mor.getId().getValue(), deviceName);
                    return mor.getId().getValue();
                } catch (InterruptedException e) {
                    log.error("{} - Failed to acquire semaphore for creating test device", tenant, e);
                    Thread.currentThread().interrupt();
                    return null;
                } finally {
                    c8ySemaphore.release();
                }
            });
        });
    }

    public ManagedObjectRepresentation upsertDevice(String tenant, ID identity, ProcessingContext<?> context,
            int requestIndex)
            throws ProcessingException {
        // StringBuffer error = new StringBuffer("");
        DynamicMapperRequest currentRequest = context.getRequests().get(requestIndex);
        Boolean testing = context.getTesting();
        ServiceConfiguration serviceConfiguration = tenantRegistry.getServiceConfiguration(tenant);
        AtomicReference<ProcessingException> pe = new AtomicReference<>();
        // API is now always initialized when creating DynamicMapperRequest
        API targetAPI = currentRequest.getApi();
        String clientId = context.getClientId();
        ManagedObjectRepresentation device = subscriptionsService.callForTenant(tenant, () -> {
            MicroserviceCredentials contextCredentials = removeAppKeyHeaderFromContext(contextService.getContext());
            return contextService.callWithinContext(contextCredentials, () -> {
                ManagedObjectRepresentation mor = objectMapper.readValue(
                        currentRequest.getRequest(),
                        ManagedObjectRepresentation.class);
                try {
                    // ExternalIDRepresentation extId = resolveExternalId2GlobalId(tenant, identity,
                    // context);
                    if (currentRequest.getSourceId() == null) {
                        // Device does not exist
                        // append external id to name
                        mor.setName(mor.getName());
                        mor.set(new Agent());
                        if(clientId != null && !clientId.isEmpty() && userExists(tenant, "device_"+clientId))
                            mor.setOwner("device_"+clientId);
                        HashMap<String, String> agentFragments = new HashMap<>();
                        agentFragments.put("name", "Dynamic Mapper");
                        agentFragments.put("version", version);
                        agentFragments.put("url", "https://github.com/Cumulocity-IoT/cumulocity-dynamic-mapper");
                        agentFragments.put("maintainer", "Open-Source");
                        mor.set(agentFragments, "c8y_Agent");
                        mor.set(new IsDevice());
                        // remove id only if not testing
                        if (!testing) {
                            mor.setId(null);
                        } else {
                            // when creating a mock inventory object for testing set a predefined source id
                            mor.setId(new GId(context.getSourceId()));
                        }
                        try {
                            c8ySemaphore.acquire();
                            mor = createWithExternalIdBinding(mor, identity, testing,
                                    Boolean.TRUE.equals(serviceConfiguration.getExternalIdBinding()));
                            // TODO Add/Update new managed object to IdentityCache
                            if (serviceConfiguration.getLogPayload())
                                log.info("{} - New device created: {}", tenant, mor);
                            else
                                log.info("{} - New device created with Id {}", tenant, mor.getId().getValue());
                        } catch (InterruptedException e) {
                            log.error("{} - Failed to acquire semaphore for creating Device", tenant, e);
                        } finally {
                            c8ySemaphore.release();
                        }
                    } else {
                        // Device exists - update needed
                        mor.setId(new GId(currentRequest.getSourceId()));
                        try {
                            c8ySemaphore.acquire();
                            mor = inventoryApi.update(mor, testing);
                        } catch (InterruptedException e) {
                            log.error("{} - Failed to acquire semaphore for updating Device", tenant, e);
                        } finally {
                            c8ySemaphore.release();
                        }
                        if (serviceConfiguration.getLogPayload())
                            log.info("{} - Device updated: {}", tenant, mor);
                        else
                            log.info("{} - Device {} updated.", tenant, mor.getId().getValue());
                    }
                } catch (SDKException s) {
                    log.error("{} - Could not sent payload to c8y: {}: ", tenant, currentRequest.getRequest(),
                            s);
                    pe.set(new ProcessingException(
                            "Could not sent payload to c8y: " + targetAPI + "/" + currentRequest.getRequest(), s));
                    // error.append("Could not sent payload to c8y: " + currentRequest.getRequest()
                    // + " " + s);
                }
                return mor;
            });
        });
        if (pe.get() != null) {
            throw pe.get();
        }
        // if (!error.toString().equals("")) {
        // throw new ProcessingException(error.toString());
        // }
        return device;
    }

    /**
     * Stores (or replaces) a single named fragment on an existing managed object.
     * <p>
     * This is used, for example, to persist the SparkPlug B NBIRTH payload
     * ({@code sparkPlugB_NBIRTH}) so that subsequent NDATA messages can resolve
     * metric aliases back to their original names.
     *
     * @param tenant        the tenant context
     * @param deviceId      the C8Y internal ID of the managed object to update
     * @param fragmentKey   the fragment / property key
     * @param fragmentValue the value to store (must be JSON-serialisable)
     * @param testing       when {@code true} the call is skipped (dry-run / test mode)
     */
    public void storeManagedObjectFragment(String tenant, String deviceId, String fragmentKey, Object fragmentValue,
            Boolean testing) {
        if (Boolean.TRUE.equals(testing)) {
            log.debug("{} - Skipping storeManagedObjectFragment '{}' on device {} in test mode",
                    tenant, fragmentKey, deviceId);
            return;
        }
        subscriptionsService.runForTenant(tenant, () -> {
            MicroserviceCredentials contextCredentials = removeAppKeyHeaderFromContext(contextService.getContext());
            contextService.runWithinContext(contextCredentials, () -> {
                ManagedObjectRepresentation mor = new ManagedObjectRepresentation();
                mor.setId(new GId(deviceId));
                mor.set(fragmentValue, fragmentKey);
                try {
                    c8ySemaphore.acquire();
                    inventoryApi.update(mor, false);
                    log.info("{} - Stored fragment '{}' on device {}", tenant, fragmentKey, deviceId);
                } catch (InterruptedException e) {
                    log.error("{} - Failed to acquire semaphore for storing fragment '{}' on device {}",
                            tenant, fragmentKey, deviceId, e);
                    Thread.currentThread().interrupt();
                } catch (SDKException e) {
                    log.warn("{} - Failed to store fragment '{}' on device {}: {}",
                            tenant, fragmentKey, deviceId, e.getMessage());
                } finally {
                    c8ySemaphore.release();
                }
            });
        });
    }

    /**
     * Assigns a newly created device to one or more named device groups.
     * Groups that do not exist yet are created automatically.
     * Errors for individual groups are logged as warnings and do not abort the others.
     *
     * @param tenant     the tenant context
     * @param deviceId   the C8Y internal ID of the device
     * @param groupNames list of group names to assign the device to
     * @param testing    flag indicating test mode (child asset assignment skipped in test mode)
     */
    public void assignDeviceToGroups(String tenant, String deviceId, List<String> groupNames, Boolean testing) {
        if (groupNames == null || groupNames.isEmpty()) {
            return;
        }
        subscriptionsService.callForTenant(tenant, () -> {
            MicroserviceCredentials credentials = removeAppKeyHeaderFromContext(contextService.getContext());
            return contextService.callWithinContext(credentials, () -> {
                for (String groupName : groupNames) {
                    try {
                        ManagedObjectRepresentation group = inventoryApi.findGroupByName(groupName, testing);
                        if (group == null) {
                            group = inventoryApi.createGroup(groupName, testing);
                            log.info("{} - Created device group '{}' with id {}", tenant, groupName,
                                    group.getId().getValue());
                        }
                        inventoryApi.addChildAsset(group.getId(), GId.asGId(deviceId), testing);
                        log.info("{} - Device {} assigned to group '{}' ({})", tenant, deviceId, groupName,
                                group.getId().getValue());
                    } catch (Exception e) {
                        log.warn("{} - Failed to assign device {} to group '{}': {}", tenant, deviceId, groupName,
                                e.getMessage());
                    }
                }
                return null;
            });
        });
    }

    public List<ManagedObjectRepresentation> getManagedObjectsByType(String tenant, String type, Boolean testing) {
        return deviceBootstrapService.getManagedObjectsByType(tenant, type, testing);
    }

    public ManagedObjectRepresentation getManagedObjectForId(String tenant, String deviceId, Boolean testing) {
        return deviceBootstrapService.getManagedObjectForId(tenant, deviceId, testing);
    }

    @Override
    public ManagedObjectRepresentation getManagedObjectForId(String tenant, String deviceId, Boolean testing, boolean withParents) {
        return deviceBootstrapService.getManagedObjectForId(tenant, deviceId, testing, withParents);
    }

    public void updateOperationStatus(String tenant, OperationRepresentation op, OperationStatus status,
            String failureReason) {
        subscriptionsService.runForTenant(tenant, () -> {
            MicroserviceCredentials contextCredentials = removeAppKeyHeaderFromContext(contextService.getContext());
            contextService.runWithinContext(contextCredentials, () -> {
                try {
                    op.setStatus(status.toString());
                    if (failureReason != null)
                        op.setFailureReason(failureReason);
                    deviceControlApi.update(op);
                } catch (SDKException exception) {
                    log.error("{} - Operation with id {} could not be updated: {}", tenant,
                            op.getDeviceId().getValue(),
                            exception.getLocalizedMessage());
                }
            });
        });
    }

    public ManagedObjectRepresentation initializeMapperServiceRepresentation(String tenant) {
        return deviceBootstrapService.initializeMapperServiceRepresentation(tenant, this);
    }

    public ManagedObjectRepresentation initializeDeviceToClientMapRepresentation(String tenant) {
        return deviceBootstrapService.initializeDeviceToClientMapRepresentation(tenant, this);
    }

    public void sendNotificationLifecycle(String tenant, ConnectorStatus connectorStatus, String message) {
        if (tenantRegistry.getServiceConfiguration(tenant).getSendNotificationLifecycle()
                && !(connectorStatus.equals(previousConnectorStatus))) {
            previousConnectorStatus = connectorStatus;
            DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            Date now = new Date();
            String date = dateFormat.format(now);
            Map<String, String> stMap = Map.ofEntries(
                    entry("status", connectorStatus.name()),
                    entry("message",
                            message == null ? C8Y_NOTIFICATION_CONNECTOR + ": " + connectorStatus.name() : message),
                    entry("connectorName", C8Y_NOTIFICATION_CONNECTOR),
                    entry("connectorIdentifier", "000000"),
                    entry("date", date));
            createOperationEvent("Connector status: " + connectorStatus.name(),
                    LoggingEventType.NOTIFICATION_EVENT_TYPE, DateTime.now(),
                    tenant,
                    stMap);
        }
    }

    public static MicroserviceCredentials removeAppKeyHeaderFromContext(MicroserviceCredentials context) {
        final MicroserviceCredentials clonedContext = new MicroserviceCredentials(
                context.getTenant(),
                context.getUsername(), context.getPassword(),
                context.getOAuthAccessToken(), context.getXsrfToken(),
                context.getTfaToken(), null);
        return clonedContext;
    }

    public void initializeInboundExternalIdCache(String tenant, int size) {
        cacheManager.initializeInboundExternalIdCache(tenant, size);
    }

    public void initializeInventoryCache(String tenant, int size) {
        cacheManager.initializeInventoryCache(tenant, size);
    }

    public InboundExternalIdCache removeInboundExternalIdCache(String tenant) {
        return cacheManager.removeInboundExternalIdCache(tenant);
    }

    public Integer getInboundExternalIdCacheSize(String tenant) {
        return cacheManager.getInboundExternalIdCacheSize(tenant);
    }

    public InventoryCache removeInventoryCache(String tenant) {
        return cacheManager.removeInventoryCache(tenant);
    }

    public InventoryCache getInventoryCache(String tenant) {
        return cacheManager.getInventoryCache(tenant);
    }

    public void clearInboundExternalIdCache(String tenant, boolean recreate, int inboundExternalIdCacheSize) {
        cacheManager.clearInboundExternalIdCache(tenant, recreate, inboundExternalIdCacheSize);
    }

    public void removeDeviceFromInboundExternalIdCache(String tenant, ID identity) {
        cacheManager.removeDeviceFromInboundExternalIdCache(tenant, identity);
    }

    public int getSizeInboundExternalIdCache(String tenant) {
        return cacheManager.getSizeInboundExternalIdCache(tenant);
    }

    public void clearInventoryCache(String tenant, boolean recreate, int inventoryCacheSize) {
        cacheManager.clearInventoryCache(tenant, recreate, inventoryCacheSize);
    }

    public int getSizeInventoryCache(String tenant) {
        return cacheManager.getSizeInventoryCache(tenant);
    }

    public Map<String, Object> getMOFromInventoryCacheByExternalId(String tenant, ExternalId externalId,
            Boolean testing) {

        return inventoryCacheEnrichmentService.getMOFromInventoryCacheByExternalId(tenant, externalId, testing, this);
    }

    public Map<String, Object> updateMOInInventoryCache(String tenant, String sourceId, Map<String, Object> updates,
            Boolean testing) {
        return inventoryCacheEnrichmentService.updateMOInInventoryCache(tenant, sourceId, updates, testing, this);
    }

    public Map<String, Object> getMOFromInventoryCache(String tenant, String sourceId, Boolean testing) {

        return inventoryCacheEnrichmentService.getMOFromInventoryCache(tenant, sourceId, testing, this);
    }

    /**
     * Uploads an attachment to an event.
     *
     * @param binaryInfo
     * @param eventId
     * @param overwrites
     * @return response status code
     */
    public int uploadEventAttachment(final BinaryInfo binaryInfo, final String eventId,
            boolean overwrites) throws ProcessingException {
        return binaryAttachmentService.uploadEventAttachment(binaryInfo, eventId, overwrites, c8ySemaphore);
    }

    public boolean userExists(String tenant, String username) {
        try {
            UserRepresentation user = userApi.getUser(tenant, username);
            if(user != null) {
                return true;
            } else {
                log.info("{} - User {} not found!", tenant, username);
                return false;
            }
        } catch (SDKException e) {
            if (e.getHttpStatus() == 404) {
                return false;
            } else {
                log.error("{} - Error while checking if user {} exists: {}", tenant, username, e.getMessage());
                return false;
            }
        }

    }

}