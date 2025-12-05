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
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;

import org.joda.time.DateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
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
import com.cumulocity.rest.representation.measurement.MeasurementRepresentation;
import com.cumulocity.rest.representation.operation.OperationRepresentation;
import com.cumulocity.sdk.client.Platform;
import com.cumulocity.sdk.client.ProcessingMode;
import com.cumulocity.sdk.client.SDKException;
import com.cumulocity.sdk.client.alarm.AlarmApi;
import com.cumulocity.sdk.client.buffering.Future;
import com.cumulocity.sdk.client.devicecontrol.DeviceControlApi;
import com.cumulocity.sdk.client.event.EventApi;
import com.cumulocity.sdk.client.measurement.MeasurementApi;
import com.fasterxml.jackson.core.JsonProcessingException;

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
import dynamic.mapper.processor.flow.ExternalId;
import dynamic.mapper.processor.model.DynamicMapperRequest;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.service.ExtensionInboundRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import static com.cumulocity.rest.representation.measurement.MeasurementMediaType.MEASUREMENT;
import static com.cumulocity.rest.representation.event.EventMediaType.EVENT;
import static com.cumulocity.rest.representation.alarm.AlarmMediaType.ALARM;;

@Slf4j
@Component
public class C8YAgent implements ImportBeanDefinitionRegistrar, InventoryEnrichmentClient, IdentityResolver {

    ConnectorStatus previousConnectorStatus = ConnectorStatus.UNKNOWN;

    @Autowired
    private EventApi eventApi;

    @Autowired
    private InventoryFacade inventoryApi;

    @Autowired
    private IdentityFacade identityApi;

    @Autowired
    private MeasurementApi measurementApi;

    @Autowired
    private AlarmApi alarmApi;

    @Autowired
    private DeviceControlApi deviceControlApi;

    @Autowired
    private ProcessingModeService processingModeService;

    @Autowired
    private MicroserviceSubscriptionsService subscriptionsService;

    @Autowired
    private ContextService<MicroserviceCredentials> contextService;

    @Getter
    private ConfigurationRegistry configurationRegistry;

    @Getter
    @Autowired
    private ExtensionInboundRegistry extensionInboundRegistry;

    @Autowired
    CumulocityClientProperties clientProperties;

    private Semaphore c8ySemaphore;

    @Autowired
    private ExtensionManager extensionManager;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private CertificateService certificateService;

    @Autowired
    private BinaryAttachmentService binaryAttachmentService;

    @Autowired
    private DeviceBootstrapService deviceBootstrapService;

    @Autowired
    private InventoryCacheEnrichmentService inventoryCacheEnrichmentService;

    @Autowired
    public void setConfigurationRegistry(@Lazy ConfigurationRegistry configurationRegistry) {
        this.configurationRegistry = configurationRegistry;
    }

    private static final String C8Y_NOTIFICATION_CONNECTOR = "C8YNotificationConnector";

    public static final String MEASUREMENT_COLLECTION_PATH = "/measurement/measurements";

    @Value("${application.version}")
    private String version;

    private Integer maxConnections = 100;

    private Timer c8yRequestTimer = Timer.builder("dynmapper_c8y_request_processing_time")
            .description("C8Y Request Processing time").register(Metrics.globalRegistry);

    public C8YAgent(@Value("#{new Integer('${C8Y.httpClient.pool.perHost}')}") Integer maxConnections) {
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
        MapperServiceRepresentation source = configurationRegistry.getMapperServiceRepresentation(tenant);
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
            API targetAPI = context.getMapping().getTargetAPI();
            AbstractExtensibleRepresentation result = subscriptionsService.callForTenant(tenant, () -> {
                MicroserviceCredentials contextCredentials = removeAppKeyHeaderFromContext(contextService.getContext());
                return contextService.callWithinContext(contextCredentials, () -> {
                    AbstractExtensibleRepresentation rt = null;
                    try {
                        if (targetAPI.equals(API.EVENT)) {
                            EventRepresentation eventRepresentation = configurationRegistry.getObjectMapper().readValue(
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
                            log.info("{} - SEND: event posted: {}", tenant, rt);
                        } else if (targetAPI.equals(API.ALARM)) {
                            AlarmRepresentation alarmRepresentation = configurationRegistry.getObjectMapper().readValue(
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
                            log.info("{} - SEND: alarm posted: {}", tenant, rt);
                        } else if (targetAPI.equals(API.MEASUREMENT)) {
                            MeasurementRepresentation measurementRepresentation = configurationRegistry
                                    .getObjectMapper().readValue(
                                            payload,
                                            MeasurementRepresentation.class);
                            // Set processing mode for measurements
                            if (context.getProcessingMode() != null &&
                                    ProcessingMode.TRANSIENT.equals(context.getProcessingMode())) {
                                // rt = measurementApiTransient.create(measurementRepresentation);
                                rt = processingModeService.callWithProcessingMode("TRANSIENT", (connector) -> {
                                    if (targetAPI.equals(API.MEASUREMENT)) {
                                        // Now use the connector with the processing mode header
                                        return (MeasurementRepresentation) connector.post("/measurement/measurements",
                                                MEASUREMENT,
                                                measurementRepresentation);
                                    }
                                    return null;
                                });
                                log.info("{} - Using TRANSIENT processing mode for measurement", tenant);
                            } else {
                                rt = measurementApi.create(measurementRepresentation);
                                log.debug("{} - Using PERSISTENT processing mode for measurement", tenant);
                            }
                            log.info("{} - SEND: measurement posted: {}", tenant, rt);
                        } else if (targetAPI.equals(API.OPERATION)) {
                            OperationRepresentation operationRepresentation = configurationRegistry.getObjectMapper()
                                    .readValue(
                                            payload, OperationRepresentation.class);
                            rt = deviceControlApi.create(operationRepresentation);
                            log.info("{} - SEND: operation posted: {}", tenant, rt);
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
        ServiceConfiguration serviceConfiguration = configurationRegistry.getServiceConfiguration(tenant);
        API targetAPI = context.getMapping().getTargetAPI();
        AbstractExtensibleRepresentation result = subscriptionsService.callForTenant(tenant, () -> {
            MicroserviceCredentials contextCredentials = removeAppKeyHeaderFromContext(contextService.getContext());
            return contextService.callWithinContext(contextCredentials, () -> {
                AbstractExtensibleRepresentation rt = null;
                try {
                    if (targetAPI.equals(API.EVENT)) {
                        EventRepresentation eventRepresentation = configurationRegistry.getObjectMapper().readValue(
                                payload,
                                EventRepresentation.class);
                        try {
                            c8ySemaphore.acquire();
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
                        AlarmRepresentation alarmRepresentation = configurationRegistry.getObjectMapper().readValue(
                                payload,
                                AlarmRepresentation.class);
                        try {
                            c8ySemaphore.acquire();
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
                        MeasurementRepresentation measurementRepresentation = configurationRegistry.getObjectMapper()
                                .readValue(
                                        payload, MeasurementRepresentation.class);
                        try {
                            c8ySemaphore.acquire();
                            if (context.getProcessingMode() != null &&
                                    ProcessingMode.TRANSIENT.equals(context.getProcessingMode())) {
                                // rt = measurementApiTransient.create(measurementRepresentation);
                                rt = processingModeService.callWithProcessingMode("TRANSIENT", (connector) -> {
                                    if (targetAPI.equals(API.MEASUREMENT)) {
                                        MeasurementRepresentation mr = configurationRegistry.getObjectMapper()
                                                .readValue(
                                                        payload, MeasurementRepresentation.class);

                                        // Now use the connector with the processing mode header
                                        return (MeasurementRepresentation) connector.post("/measurement/measurements",
                                                MEASUREMENT,
                                                mr);
                                    }
                                    return null;
                                });
                                log.info("{} - Using TRANSIENT processing mode for measurement", tenant);
                            } else {
                                rt = measurementApi.create(measurementRepresentation);
                                log.debug("{} - Using PERSISTENT processing mode for measurement", tenant);
                            }
                        } catch (InterruptedException e) {
                            log.error("{} - Failed to acquire semaphore for creating measurement", tenant, e);
                        } finally {
                            c8ySemaphore.release();
                        }
                        if (serviceConfiguration.getLogPayload())
                            log.info("{} - SEND: measurement posted: {}", tenant, rt);
                        else
                            log.info("{} - SEND: measurement posted with Id {}", tenant,
                                    ((MeasurementRepresentation) rt).getId().getValue());
                    } else if (targetAPI.equals(API.OPERATION)) {
                        OperationRepresentation operationRepresentation = configurationRegistry.getObjectMapper()
                                .readValue(
                                        payload, OperationRepresentation.class);
                        try {
                            c8ySemaphore.acquire();
                            rt = deviceControlApi.create(operationRepresentation);
                        } catch (InterruptedException e) {
                            log.error("{} - Failed to acquire semaphore for creating Alarm", tenant, e);
                        } finally {
                            c8ySemaphore.release();
                        }
                        log.info("{} - SEND: operation posted: {}", tenant, rt);
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

    public ManagedObjectRepresentation upsertDevice(String tenant, ID identity, ProcessingContext<?> context,
            int requestIndex)
            throws ProcessingException {
        // StringBuffer error = new StringBuffer("");
        DynamicMapperRequest currentRequest = context.getRequests().get(requestIndex);
        Boolean testing = context.getTesting();
        ServiceConfiguration serviceConfiguration = configurationRegistry.getServiceConfiguration(tenant);
        AtomicReference<ProcessingException> pe = new AtomicReference<>();
        API targetAPI = context.getMapping().getTargetAPI();
        ManagedObjectRepresentation device = subscriptionsService.callForTenant(tenant, () -> {
            MicroserviceCredentials contextCredentials = removeAppKeyHeaderFromContext(contextService.getContext());
            return contextService.callWithinContext(contextCredentials, () -> {
                ManagedObjectRepresentation mor = configurationRegistry.getObjectMapper().readValue(
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
                            mor = inventoryApi.create(mor, testing);
                            // TODO Add/Update new managed object to IdentityCache
                            if (serviceConfiguration.getLogPayload())
                                log.info("{} - New device created: {}", tenant, mor);
                            else
                                log.info("{} - New device created with Id {}", tenant, mor.getId().getValue());
                            identityApi.create(mor, identity, testing);
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

    public ManagedObjectRepresentation getManagedObjectForId(String tenant, String deviceId, Boolean testing) {
        ManagedObjectRepresentation device = subscriptionsService.callForTenant(tenant, () -> {
            try {
                return inventoryApi.get(GId.asGId(deviceId), testing);
            } catch (SDKException exception) {
                log.warn("{} - Device with id {} not found!", tenant, deviceId);
            }
            return null;
        });

        return device;
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
        if (configurationRegistry.getServiceConfiguration(tenant).getSendNotificationLifecycle()
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
                    LoggingEventType.STATUS_NOTIFICATION_EVENT_TYPE, DateTime.now(),
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
        cacheManager.initializeInventoryCache(tenant, size, configurationRegistry);
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
        cacheManager.clearInventoryCache(tenant, recreate, inventoryCacheSize, configurationRegistry);
    }

    public int getSizeInventoryCache(String tenant) {
        return cacheManager.getSizeInventoryCache(tenant);
    }

    public Map<String, Object> getMOFromInventoryCacheByExternalId(String tenant, ExternalId externalId,
            Boolean testing) {

        return inventoryCacheEnrichmentService.getMOFromInventoryCacheByExternalId(tenant, externalId, testing, this, configurationRegistry);
    }

    public Map<String, Object> updateMOInInventoryCache(String tenant, String sourceId, Map<String, Object> updates,
            Boolean testing) {
        return inventoryCacheEnrichmentService.updateMOInInventoryCache(tenant, sourceId, updates, testing, this, configurationRegistry);
    }

    public Map<String, Object> getMOFromInventoryCache(String tenant, String sourceId, Boolean testing) {

        return inventoryCacheEnrichmentService.getMOFromInventoryCache(tenant, sourceId, testing, this, configurationRegistry);
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

}