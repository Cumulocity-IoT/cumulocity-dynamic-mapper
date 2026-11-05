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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;

import com.cumulocity.rest.representation.user.UserRepresentation;
import com.cumulocity.sdk.client.user.UserApi;
import org.joda.time.DateTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
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
import com.cumulocity.sdk.client.SDKException;
import com.cumulocity.sdk.client.alarm.AlarmApi;
import com.cumulocity.sdk.client.buffering.Future;
import com.cumulocity.sdk.client.devicecontrol.DeviceControlApi;
import com.cumulocity.sdk.client.event.EventApi;
import com.cumulocity.sdk.client.measurement.MeasurementApi;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import c8y.IsDevice;
import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.connector.core.client.Certificate;
import dynamic.mapper.core.cache.InboundExternalIdCache;
import dynamic.mapper.core.cache.InventoryCache;
import dynamic.mapper.core.cache.OutboundExternalIdCache;
import dynamic.mapper.core.cache.OutboundIdKey;
import dynamic.mapper.core.facade.IdentityFacade;
import dynamic.mapper.core.facade.InventoryFacade;
import dynamic.mapper.model.API;
import dynamic.mapper.model.BinaryInfo;
import dynamic.mapper.model.status.LoggingEventType;
import dynamic.mapper.model.MapperServiceRepresentation;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.model.DynamicMapperRequest;
import dynamic.mapper.processor.model.ExternalId;
import dynamic.mapper.processor.runtime.ProcessingContext;
import dynamic.mapper.processor.runtime.ProcessingResultWrapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import static com.cumulocity.rest.representation.event.EventMediaType.EVENT;
import static com.cumulocity.rest.representation.alarm.AlarmMediaType.ALARM;

@Slf4j
@Component
public class C8YAgent implements ImportBeanDefinitionRegistrar, InventoryEnrichmentClient, IdentityResolver {

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
    private final DeviceToClientRegistry deviceToClientRegistry;
    private final ExtensionManager extensionManager;
    private final CacheManager cacheManager;
    private final CertificateService certificateService;
    private final BinaryAttachmentService binaryAttachmentService;
    private final DeviceBootstrapService deviceBootstrapService;
    private final InventoryCacheEnrichmentService inventoryCacheEnrichmentService;

    private final String version;
    private final Integer maxConnections;
    private final Semaphore c8ySemaphore;
    // Sends the requests of a mapping to Cumulocity; shares this agent's semaphore and API clients
    private final ApiRequestSender apiRequestSender;

    private static final String C8Y_NOTIFICATION_CONNECTOR = "C8YNotificationConnector";

    public static final String MEASUREMENT_COLLECTION_PATH = "/measurement/measurements";

    private Timer c8yRequestTimer = Timer.builder("dynmapper_c8y_request_processing_time")
            .description("C8Y Request Processing time")
            .publishPercentileHistogram()
            .register(Metrics.globalRegistry);

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
            DeviceToClientRegistry deviceToClientRegistry,
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
        this.deviceToClientRegistry = deviceToClientRegistry;
        this.extensionManager = extensionManager;
        this.cacheManager = cacheManager;
        this.certificateService = certificateService;
        this.binaryAttachmentService = binaryAttachmentService;
        this.deviceBootstrapService = deviceBootstrapService;
        this.inventoryCacheEnrichmentService = inventoryCacheEnrichmentService;
        this.version = version;
        this.maxConnections = maxConnections;
        this.c8ySemaphore = new Semaphore(maxConnections, false);
        this.apiRequestSender = new ApiRequestSender(c8ySemaphore, eventApi, alarmApi, measurementApi,
                deviceControlApi, processingModeService, objectMapper, contextService, clientProperties);
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
                InboundExternalIdCache inboundCache = cacheManager.getInboundExternalIdCache(tenant);
                ExternalIDRepresentation resultInner = null;
                if (inboundCache != null && !Boolean.TRUE.equals(testing)) {
                    resultInner = inboundCache.getIdByExternalId(identity);
                }
                if (!Boolean.TRUE.equals(testing)) { // a dry run's mock lookups are not identity requests
                    Counter.builder("dynmapper_inbound_identity_requests_total").tag("tenant", tenant)
                            .register(Metrics.globalRegistry).increment();
                }
                if (resultInner == null) {
                    resultInner = identityApi.resolveExternalId2GlobalId(identity, testing, c8ySemaphore);
                    if (inboundCache != null && !Boolean.TRUE.equals(testing)) {
                        inboundCache.putIdForExternalId(identity,
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

    public void clearMockIdentityCache(String tenant) {
        identityApi.clearMockIdentityCache(tenant);
    }

    public void clearMockInventoryCache(String tenant) {
        inventoryApi.clearInventoryCache(tenant);
    }

    public ExternalIDRepresentation resolveGlobalId2ExternalId(String tenant, GId gid, String idType,
            Boolean testing) {
        if (idType == null) {
            idType = "c8y_Serial";
        }
        final String idt = idType;

        OutboundExternalIdCache outboundCache = cacheManager.getOutboundExternalIdCache(tenant);
        OutboundIdKey cacheKey = new OutboundIdKey(gid.getValue(), idt);
        if (outboundCache != null && !Boolean.TRUE.equals(testing)) {
            ExternalIDRepresentation cached = outboundCache.getExternalIdForSource(cacheKey);
            if (cached != null) {
                Counter.builder("dynmapper_outbound_identity_cache_hits_total").tag("tenant", tenant)
                        .register(Metrics.globalRegistry).increment();
                return cached;
            }
        }

        ExternalIDRepresentation result = subscriptionsService.callForTenant(tenant, () -> {
            try {
                return identityApi.resolveGlobalId2ExternalId(gid, idt, testing, c8ySemaphore);
            } catch (SDKException e) {
                log.warn("{} - External ID type {} for {} not found", tenant, idt, gid.getValue());
            }
            return null;
        });

        if (result != null && outboundCache != null && !Boolean.TRUE.equals(testing)) {
            outboundCache.putExternalIdForSource(cacheKey, result);
        }
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

    public void createLoggingEvent(String message, LoggingEventType loggingType, DateTime eventTime,
            String tenant, Map<String, String> properties) {
        createLoggingEvent(message, loggingType, null, eventTime, tenant, properties);
    }

    /**
     * @param severityOverride overrides {@code loggingType.getSeverity()} in the emitted
     *                         {@code d11r_metadata} fragment, e.g. so callers can derive severity
     *                         from a concrete status value instead of a fixed per-type default.
     *                         Pass {@code null} to use {@code loggingType.getSeverity()} unchanged.
     */
    public void createLoggingEvent(String message, LoggingEventType loggingType, String severityOverride,
            DateTime eventTime, String tenant, Map<String, String> properties) {
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
                    "severity", severityOverride != null ? severityOverride : loggingType.getSeverity(),
                    "description", loggingType.getDescription()
                );
                er.setProperty("d11r_metadata", metadata);

                try {
                    c8ySemaphore.acquire();
                    Future result = this.eventApi.createAsync(er);
                    // Non-blocking check so a failed event-creation call is at least logged
                    // instead of silently disappearing (the SDK's Future has no callback API).
                    Thread.ofVirtual().name("logging-event-result-" + tenant).start(() -> {
                        try {
                            result.get();
                        } catch (SDKException e) {
                            log.warn("{} - Failed to create logging event (type={}): {}",
                                    tenant, loggingType.type, e.getMessage());
                        } catch (Exception e) {
                            log.warn("{} - Failed to create logging event (type={}): {}",
                                    tenant, loggingType.type, e.getMessage());
                        }
                    });
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

    public AbstractExtensibleRepresentation createMEAO(ProcessingContext<?> context, int requestIndex)
            throws ProcessingException {
        String tenant = context.getTenant();
        Timer.Sample timer = Timer.start(Metrics.globalRegistry);
        AtomicReference<ProcessingException> pe = new AtomicReference<>();
        DynamicMapperRequest currentRequest = context.getRequests().get(requestIndex);
        String payload = currentRequest.getRequest();
        boolean logPayload = tenantRegistry.getServiceConfiguration(tenant).getLogPayload();
        // API is now always initialized when creating DynamicMapperRequest
        API targetAPI = currentRequest.getApi();

        // Check for cancellation BEFORE starting any C8Y API calls
        // This prevents unnecessary HTTP requests when the processing has already timed out
        ProcessingResultWrapper<?> wrapper = context.getProcessingResultWrapper();
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
                    switch (targetAPI) {
                        case EVENT -> rt = apiRequestSender.sendEvent(context, payload, logPayload, event -> {
                            if (context.getMapping().getEventWithAttachment()) {
                                uploadEventAttachment(context.getBinaryInfo(), event.getId().getValue(), false);
                            }
                        });
                        case ALARM -> rt = apiRequestSender.sendAlarm(context, payload, logPayload);
                        case MEASUREMENT -> rt = apiRequestSender.sendMeasurements(context, payload, logPayload);
                        case OPERATION -> rt = apiRequestSender.sendOperation(context, payload, logPayload);
                        case CUSTOM -> apiRequestSender.sendCustom(context, currentRequest);
                        default -> log.error("{} - Not existing API!", tenant);
                    }
                } catch (JsonProcessingException e) {
                    log.error("{} - Could not map payload: {} {}", tenant, targetAPI, payload, e);
                    pe.set(new ProcessingException("Could not map payload: " + targetAPI + "/" + payload, e));
                } catch (SDKException s) {
                    logSendFailure(tenant, targetAPI + " " + payload, s);
                    pe.set(new ProcessingException(sendFailureText(targetAPI, payload, s), s));

                    // Remove device from Cache
                    if (s.getHttpStatus() == 422) {
                        ID identity = new ID(currentRequest.getExternalIdType(), currentRequest.getExternalId());
                        cacheManager.removeDeviceFromInboundExternalIdCache(tenant, identity);
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
    private static final String EXTERNAL_ID_BINDING_HINT = "Device creation failed: the option "
            + "'External ID binding during MO creation' is enabled in the service configuration, but this "
            + "Cumulocity tenant/platform does not support it (platform release May 2026 or later required; "
            + "e.g. Cumulocity Edge does not). Disable this option under Configuration > Service configuration >"
            + " General and retry.";

    /** True if the platform rejected the atomic {@code externalIds} directive in the MO creation body. */
    private static boolean isExternalIdBindingRejected(SDKException e) {
        return e.getHttpStatus() == 422 && e.getMessage() != null
                && e.getMessage().contains("Cannot add externalIds through Inventory API");
    }

    /** Another creator bound the external ID first (HTTP 409); resolve the winner once the semaphore is released. */
    private static final class ExternalIdConflictException extends RuntimeException {
        ExternalIdConflictException(SDKException cause) {
            super(cause);
        }

        SDKException sdkException() {
            return (SDKException) getCause();
        }
    }

    private static boolean isExternalIdDuplicate(SDKException e) {
        return e.getHttpStatus() == 409;
    }

    /**
     * Must be called holding {@code c8ySemaphore}. Throws {@link ExternalIdConflictException} on a lost
     * race; the caller resolves the winner via {@link #resolveExistingDevice} after releasing the semaphore
     * (the lookup acquires it again).
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
            try {
                return inventoryApi.create(mor, false);
            } catch (SDKException e) {
                if (isExternalIdDuplicate(e)) {
                    throw new ExternalIdConflictException(e);
                }
                throw e;
            }
        }

        // Legacy path (platforms < May 2026, e.g. Cumulocity Edge): create MO first,
        // then bind the external ID via a separate Identity API call.
        log.debug("Creating MO + external ID binding via two separate calls (legacy platform path)");
        ManagedObjectRepresentation created = inventoryApi.create(mor, false);
        try {
            identityApi.create(created, identity, false);
        } catch (SDKException e) {
            if (!isExternalIdDuplicate(e)) {
                throw e;
            }
            // Lost a race: drop our unbound MO (otherwise it stays behind as a device without external ID).
            try {
                inventoryApi.delete(created.getId(), false);
            } catch (Exception de) {
                log.warn("Could not remove orphaned device {}", created.getId().getValue(), de);
            }
            throw new ExternalIdConflictException(e);
        }
        return created;
    }

    /** Looks up the device that won the external-ID race; rethrows the original 409 if it cannot be found. */
    private ManagedObjectRepresentation resolveExistingDevice(String tenant, ID identity,
            ExternalIdConflictException conflict) {
        evictExternalIdFromCaches(tenant, identity);
        ExternalIDRepresentation existing = resolveExternalId2GlobalId(tenant, identity, false);
        if (existing == null) {
            throw conflict.sdkException();
        }
        log.warn("{} - External ID {} was bound concurrently, using existing device {}", tenant,
                identity.getValue(), existing.getManagedObject().getId().getValue());
        return existing.getManagedObject();
    }

    /**
     * Creates a real managed object in C8Y inventory tagged with {@code d11r_testDevice} so it
     * is visible in the Test Devices grid and can be cleaned up after testing.
     *
     * @return the internal C8Y ID of the created managed object, or {@code null} on failure
     */
    public String createTestDevice(String tenant, String deviceName, String externalId, String externalIdType) {
        TestDevice device = ensureTestDevice(tenant, deviceName, externalId, externalIdType);
        return device == null ? null : device.id();
    }

    /**
     * Outcome of {@link #ensureTestDevice}: the device's internal ID, and whether this call created
     * it. A device that already existed under that external ID is reused as is — it is not re-tagged. Only a device already
     * tagged {@code d11r_testDevice} is reused; any other object owning the external ID is refused
     * (the result is {@code null}), so a live test never writes to a real device.
     */
    public record TestDevice(String id, boolean created) {
    }

    /**
     * Drops an external ID from the inbound external-ID cache and the implicit-device-creation cache,
     * so the next lookup asks the platform. Used when a cached device ID is known or suspected to be
     * stale (the managed object was deleted).
     */
    public void evictExternalIdFromCaches(String tenant, ID identity) {
        cacheManager.removeDeviceFromInboundExternalIdCache(tenant, identity);
        tenantRegistry.removeFromExternalIdCache(tenant, identity);
    }

    /** True if the managed object carries the {@code d11r_testDevice} tag; false if not or if it cannot be read. */
    private boolean isMapperTestDevice(String tenant, String deviceId) {
        return Boolean.TRUE.equals(subscriptionsService.callForTenant(tenant, () -> {
            try {
                ManagedObjectRepresentation mor = inventoryApi.get(GId.asGId(deviceId), false);
                return mor != null && (MAPPING_TEST_DEVICE_TYPE.equals(mor.getType())
                        || mor.hasProperty(MAPPING_TEST_DEVICE_TYPE));
            } catch (Exception e) {
                log.warn("{} - Could not read device {} to verify it is a test device: {}", tenant, deviceId,
                        e.getMessage());
                return false;
            }
        }));
    }

    /**
     * Like {@link #createTestDevice} but reports whether the device was created or already existed.
     *
     * @return the device, or {@code null} on failure
     */
    public TestDevice ensureTestDevice(String tenant, String deviceName, String externalId, String externalIdType) {
        String resolvedType = (externalIdType != null && !externalIdType.isEmpty()) ? externalIdType : "c8y_Serial";
        // Upsert: if the externalId is already registered, return the existing device ID
        ID id = new ID();
        id.setType(resolvedType);
        id.setValue(externalId);
        // A test device is deleted by hand from time to time, and nothing evicts a deleted test device
        // from the external-ID caches (it is not subscribed for inventory updates). Never trust a cached
        // answer here: ask the platform, and let the lookup below repopulate the caches.
        evictExternalIdFromCaches(tenant, id);
        ExternalIDRepresentation existing = resolveExternalId2GlobalId(tenant, id, false);
        if (existing != null) {
            String existingId = existing.getManagedObject().getId().getValue();
            if (!isMapperTestDevice(tenant, existingId)) {
                // Never write live-test data to a device the mapper did not create for testing.
                log.error("{} - Object {} owns external ID {} but is not a mapper test device, refusing to reuse it",
                        tenant, existingId, externalId);
                return null;
            }
            log.info("{} - Test device with externalId={} already exists: id={}, reusing it", tenant, externalId,
                    existingId);
            return new TestDevice(existingId, false);
        }

        boolean supportsExternalIdBinding = Boolean.TRUE.equals(
                tenantRegistry.getServiceConfiguration(tenant).getExternalIdBinding());
        return subscriptionsService.callForTenant(tenant, () -> {
            MicroserviceCredentials contextCredentials = removeAppKeyHeaderFromContext(contextService.getContext());
            return contextService.callWithinContext(contextCredentials, () -> {
                ManagedObjectRepresentation mor = new ManagedObjectRepresentation();
                mor.setName(deviceName);
                mor.set(new IsDevice());
                // Both the fragment (what the Test Devices grid queries) and the object type, so the device
                // is recognisable as a test device in the inventory as well.
                mor.setType(MAPPING_TEST_DEVICE_TYPE);
                mor.set(new HashMap<String, String>(), MAPPING_TEST_DEVICE_TYPE);
                ExternalIdConflictException conflict;
                try {
                    c8ySemaphore.acquire();
                    mor = createWithExternalIdBinding(mor, id, false, supportsExternalIdBinding);
                    log.info("{} - Test device created: id={}, name={}", tenant, mor.getId().getValue(), deviceName);
                    return new TestDevice(mor.getId().getValue(), true);
                } catch (InterruptedException e) {
                    log.error("{} - Failed to acquire semaphore for creating test device", tenant, e);
                    Thread.currentThread().interrupt();
                    return null;
                } catch (ExternalIdConflictException e) {
                    conflict = e;
                } finally {
                    c8ySemaphore.release();
                }
                // Semaphore released: the lookup acquires it again.
                return new TestDevice(resolveExistingDevice(tenant, id, conflict).getId().getValue(), false);
            });
        });
    }

    public ManagedObjectRepresentation upsertDevice(String tenant, ID identity, ProcessingContext<?> context,
            int requestIndex)
            throws ProcessingException {
        // StringBuffer error = new StringBuffer("");
        DynamicMapperRequest currentRequest = context.getRequests().get(requestIndex);
        Boolean testing = context.isTesting();
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
                        ExternalIdConflictException conflict = null;
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
                        } catch (ExternalIdConflictException e) {
                            conflict = e;
                        } finally {
                            c8ySemaphore.release();
                        }
                        if (conflict != null) {
                            // Semaphore released: the lookup acquires it again.
                            mor = resolveExistingDevice(tenant, identity, conflict);
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
                    if (isExternalIdBindingRejected(s)) {
                        log.error("{} - {}", tenant, EXTERNAL_ID_BINDING_HINT);
                        pe.set(new ProcessingException(
                                EXTERNAL_ID_BINDING_HINT + " Payload: " + targetAPI + "/"
                                        + currentRequest.getRequest(),
                                s));
                        return mor;
                    }
                    logSendFailure(tenant, currentRequest.getRequest(), s);
                    pe.set(new ProcessingException(
                            sendFailureText(targetAPI, currentRequest.getRequest(), s), s));
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

    public void forEachManagedObjectByType(String tenant, String type, Boolean testing,
            java.util.function.Consumer<ManagedObjectRepresentation> consumer) {
        deviceBootstrapService.forEachManagedObjectByType(tenant, type, testing, consumer);
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

    public static MicroserviceCredentials removeAppKeyHeaderFromContext(MicroserviceCredentials context) {
        final MicroserviceCredentials clonedContext = new MicroserviceCredentials(
                context.getTenant(),
                context.getUsername(), context.getPassword(),
                context.getOAuthAccessToken(), context.getXsrfToken(),
                context.getTfaToken(), null);
        return clonedContext;
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
     * Evicts a deleted managed object from the inventory cache and from the external-ID
     * resolution cache. Called when a Notification 2.0 DELETE event for a managed object is
     * received, so a stale sourceId does not keep being reused/refetched after the device has
     * been removed from inventory (see {@code attic/fix/inconsistant-cache/ISSUE.md}).
     */
    public void evictDeletedManagedObjectFromCaches(String tenant, String sourceId) {
        InventoryCache inventoryCache = cacheManager.getInventoryCache(tenant);
        if (inventoryCache != null) {
            inventoryCache.removeMO(sourceId);
        }
        tenantRegistry.removeFromExternalIdCacheByInternalId(tenant, sourceId);
        // Device isolation: a deleted device must not stay registered to a client
        deviceToClientRegistry.removeClientRelation(tenant, sourceId);
        cacheManager.removeSourceIdFromExternalIdCaches(tenant, sourceId);
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
                log.error("{} - Error while checking if user {} exists: {}", tenant, username, e.getMessage(), e);
                return false;
            }
        }

    }


    /**
     * Logs a failed send to Cumulocity. A 422 is a data-level rejection that the caller handles —
     * most commonly a cached device id whose device was deleted, which the inbound dispatcher
     * recovers from by evicting the cache entry and resending — so it is logged as a one-line
     * WARN. The failure still reaches the processing result and the mapping's error statistics.
     * Anything else keeps the ERROR with its stack trace.
     */
    private void logSendFailure(String tenant, Object request, SDKException s) {
        if (s.getHttpStatus() == 422) {
            log.warn("{} - Cumulocity rejected payload (HTTP 422): {} - {}", tenant, s.getMessage(), request);
        } else {
            log.error("{} - Could not send payload to c8y: {}: HTTP {} - {}", tenant, request,
                    s.getHttpStatus(), s.getMessage(), s);
        }
    }

    /**
     * The text of a failed send as the statistics page and the processing result show it. The reason comes first: the
     * payload that follows can be long and is cut where the text is displayed, which used to hide why the send failed.
     */
    static String sendFailureText(Object targetAPI, Object payload, SDKException failure) {
        return "Could not send payload to c8y (HTTP " + failure.getHttpStatus() + " - " + failure.getMessage() + "): "
                + targetAPI + "/" + payload;
    }
}
