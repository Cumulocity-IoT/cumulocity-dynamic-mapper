/*
 * Copyright (c) 2022 Software AG, Darmstadt, Germany and/or Software AG USA Inc., Reston, VA, USA,
 * and/or its subsidiaries and/or its affiliates and/or their licensors.
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
 * @authors Christof Strack, Stefan Witschel
 */

package dynamic.mapping.core;

import static java.util.Map.entry;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLClassLoader;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import javax.ws.rs.core.MediaType;

import dynamic.mapping.App;
import dynamic.mapping.configuration.TrustedCertificateCollectionRepresentation;
import dynamic.mapping.configuration.TrustedCertificateRepresentation;
import dynamic.mapping.connector.core.client.AConnectorClient;
import dynamic.mapping.core.facade.IdentityFacade;
import dynamic.mapping.core.facade.InventoryFacade;
import dynamic.mapping.model.Extension;
import dynamic.mapping.model.ExtensionEntry;
import dynamic.mapping.model.MappingServiceRepresentation;
import dynamic.mapping.processor.ProcessingException;
import dynamic.mapping.processor.extension.ExtensibleProcessorInbound;
import dynamic.mapping.processor.extension.ExtensionsComponent;
import dynamic.mapping.processor.extension.ProcessorExtensionInbound;
import dynamic.mapping.processor.model.C8YRequest;
import dynamic.mapping.processor.model.ProcessingContext;
import org.apache.commons.io.IOUtils;
import org.joda.time.DateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.svenson.JSONParser;

import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;
import com.cumulocity.model.Agent;
import com.cumulocity.model.ID;
import com.cumulocity.model.JSONBase;
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
import com.cumulocity.sdk.client.SDKException;
import com.cumulocity.sdk.client.alarm.AlarmApi;
import com.cumulocity.sdk.client.devicecontrol.DeviceControlApi;
import com.cumulocity.sdk.client.event.EventApi;
import com.cumulocity.sdk.client.inventory.BinariesApi;
import com.cumulocity.sdk.client.measurement.MeasurementApi;
import com.fasterxml.jackson.core.JsonProcessingException;

import c8y.IsDevice;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import dynamic.mapping.model.API;

@Slf4j
@Component
public class C8YAgent implements ImportBeanDefinitionRegistrar {

    @Autowired
    private EventApi eventApi;

    @Autowired
    private InventoryFacade inventoryApi;

    @Autowired
    private BinariesApi binaryApi;

    @Autowired
    private IdentityFacade identityApi;

    @Autowired
    private MeasurementApi measurementApi;

    @Autowired
    private AlarmApi alarmApi;

    @Autowired
    private DeviceControlApi deviceControlApi;

    @Autowired
    private Platform platform;

    @Autowired
    private MicroserviceSubscriptionsService subscriptionsService;

    private ExtensionsComponent extensionsComponent;

    @Autowired
    public void setExtensionsComponent(ExtensionsComponent extensionsComponent) {
        this.extensionsComponent = extensionsComponent;
    }

    @Getter
    private ConfigurationRegistry configurationRegistry;

    @Autowired
    public void setConfigurationRegistry(@Lazy ConfigurationRegistry configurationRegistry) {
        this.configurationRegistry = configurationRegistry;
    }

    private JSONParser jsonParser = JSONBase.getJSONParser();

    public static final String MAPPING_FRAGMENT = "d11r_mapping";

    public static final String CONNECTOR_FRAGMENT = "d11r_connector";

    public static final String STATUS_SUBSCRIPTION_EVENT_TYPE = "d11r_subscriptionEvent";
    public static final String STATUS_CONNECTOR_EVENT_TYPE = "d11r_connectorStatusEvent";
    public static final String STATUS_NOTIFICATION_EVENT_TYPE = "d11r_notificationStatusEvent";

    private static final String EXTENSION_INTERNAL_FILE = "extension-internal.properties";
    private static final String EXTENSION_EXTERNAL_FILE = "extension-external.properties";

    private static final String C8Y_NOTIFICATION_CONNECTOR = "C8YNotificationConnector";

    private static final String PACKAGE_MAPPING_PROCESSOR_EXTENSION_EXTERNAL = "dynamic.mapping.processor.extension.external";

    public ExternalIDRepresentation resolveExternalId2GlobalId(String tenant, ID identity,
            ProcessingContext<?> context) {
        if (identity.getType() == null) {
            identity.setType("c8y_Serial");
        }
        ExternalIDRepresentation result = subscriptionsService.callForTenant(tenant, () -> {
            try {
                return identityApi.resolveExternalId2GlobalId(identity, context);
            } catch (SDKException e) {
                log.warn("Tenant {} - External ID {} not found", tenant, identity.getValue());
            }
            return null;
        });
        return result;
    }

    public ExternalIDRepresentation resolveGlobalId2ExternalId(String tenant, GId gid, String idType,
            ProcessingContext<?> context) {
        if (idType == null) {
            idType = "c8y_Serial";
        }
        final String idt = idType;
        ExternalIDRepresentation result = subscriptionsService.callForTenant(tenant, () -> {
            try {
                return identityApi.resolveGlobalId2ExternalId(gid, idt, context);
            } catch (SDKException e) {
                log.warn("Tenant {} - External ID type {} for {} not found", tenant, idt, gid.getValue());
            }
            return null;
        });
        return result;
    }

    public MeasurementRepresentation createMeasurement(String name, String type, ManagedObjectRepresentation mor,
            DateTime dateTime, HashMap<String, MeasurementValue> mvMap, String tenant) {
        MeasurementRepresentation measurementRepresentation = new MeasurementRepresentation();
        subscriptionsService.runForTenant(tenant, () -> {
            try {
                measurementRepresentation.set(mvMap, name);
                measurementRepresentation.setType(type);
                measurementRepresentation.setSource(mor);
                measurementRepresentation.setDateTime(dateTime);
                log.debug("Tenant {} - Creating Measurement {}", tenant, measurementRepresentation);
                MeasurementRepresentation mrn = measurementApi.create(measurementRepresentation);
                measurementRepresentation.setId(mrn.getId());
            } catch (SDKException e) {
                log.error("Tenant {} - Error creating Measurement", tenant, e);
            }
        });
        return measurementRepresentation;
    }

    public AlarmRepresentation createAlarm(String severity, String message, String type, DateTime alarmTime,
            ManagedObjectRepresentation parentMor, String tenant) {
        AlarmRepresentation alarmRepresentation = subscriptionsService.callForTenant(tenant, () -> {
            AlarmRepresentation ar = new AlarmRepresentation();
            ar.setSeverity(severity);
            ar.setSource(parentMor);
            ar.setText(message);
            ar.setDateTime(alarmTime);
            ar.setStatus("ACTIVE");
            ar.setType(type);

            return this.alarmApi.create(ar);
        });
        return alarmRepresentation;
    }

    public void createEvent(String message, String type, DateTime eventTime, MappingServiceRepresentation source,
            String tenant, Map<String, String> properties) {
        subscriptionsService.runForTenant(tenant, () -> {
            EventRepresentation er = new EventRepresentation();
            ManagedObjectRepresentation mor = new ManagedObjectRepresentation();
            mor.setId(new GId(source.getId()));
            er.setSource(mor);
            er.setText(message);
            er.setDateTime(eventTime);
            er.setType(type);
            if (properties != null) {
                er.setProperty(C8YAgent.CONNECTOR_FRAGMENT, properties);
            }
            this.eventApi.createAsync(er);
        });
    }

    public AConnectorClient.Certificate loadCertificateByName(String certificateName, String fingerprint,
            String tenant, String connectorName) {
        TrustedCertificateRepresentation result = subscriptionsService.callForTenant(tenant,
                () -> {
                    log.info("Tenant {} - Connector {} - Retrieving certificate {} ", tenant, connectorName,
                            certificateName);
                    TrustedCertificateRepresentation certResult = null;
                    try {
                        List<TrustedCertificateRepresentation> certificatesList = new ArrayList<>();
                        boolean next = true;
                        String nextUrl = String.format("/tenant/tenants/%s/trusted-certificates", tenant);
                        TrustedCertificateCollectionRepresentation certificatesResult;
                        while (next) {
                            certificatesResult = platform.rest().get(
                                    nextUrl,
                                    MediaType.APPLICATION_JSON_TYPE, TrustedCertificateCollectionRepresentation.class);
                            certificatesList.addAll(certificatesResult.getCertificates());
                            nextUrl = certificatesResult.getNext();
                            next = certificatesResult.getCertificates().size() > 0;
                            log.info("Tenant {} - Connector {} - Retrieved certificates {} - next {} - nextUrl {}",
                                    tenant,
                                    connectorName, certificatesList.size(), next, nextUrl);
                        }
                        for (int index = 0; index < certificatesList.size(); index++) {
                            TrustedCertificateRepresentation certificateIterate = certificatesList.get(index);
                            log.info("Tenant {} - Found certificate with fingerprint: {} with name: {}", tenant,
                                    certificateIterate.getFingerprint(),
                                    certificateIterate.getName());
                            if (certificateIterate.getName().equals(certificateName)
                                    && certificateIterate.getFingerprint().equals(fingerprint)) {
                                certResult = certificateIterate;
                                log.info("Tenant {} - Connector {} - Found certificate {} with fingerprint {} ", tenant,
                                        connectorName, certificateName, certificateIterate.getFingerprint());
                                break;
                            }
                        }
                    } catch (Exception e) {
                        log.error("Tenant {} - Connector {} - Exception when initializing connector: ", tenant,
                                connectorName, e);
                    }
                    return certResult;
                });
        if (result != null) {
            log.info("Tenant {} - Connector {} - Found certificate {} with fingerprint {} ", tenant,
                    connectorName, certificateName, result.getFingerprint());
            StringBuffer cert = new StringBuffer("-----BEGIN CERTIFICATE-----\n")
                    .append(result.getCertInPemFormat())
                    .append("\n").append("-----END CERTIFICATE-----");
            return new AConnectorClient.Certificate(result.getFingerprint(), cert.toString());
        } else {
            log.info("Tenant {} - Connector {} - No certificate found!", tenant, connectorName);
            return null;
        }
    }

    public AbstractExtensibleRepresentation createMEAO(ProcessingContext<?> context)
            throws ProcessingException {
        String tenant = context.getTenant();
        StringBuffer error = new StringBuffer("");
        C8YRequest currentRequest = context.getCurrentRequest();
        String payload = currentRequest.getRequest();
        API targetAPI = context.getMapping().getTargetAPI();
        AbstractExtensibleRepresentation result = subscriptionsService.callForTenant(tenant, () -> {
            AbstractExtensibleRepresentation rt = null;
            try {
                if (targetAPI.equals(API.EVENT)) {
                    EventRepresentation eventRepresentation = configurationRegistry.getObjectMapper().readValue(payload,
                            EventRepresentation.class);
                    rt = eventApi.create(eventRepresentation);
                    log.info("Tenant {} - New event posted: {}", tenant, rt);
                } else if (targetAPI.equals(API.ALARM)) {
                    AlarmRepresentation alarmRepresentation = configurationRegistry.getObjectMapper().readValue(payload,
                            AlarmRepresentation.class);
                    rt = alarmApi.create(alarmRepresentation);
                    log.info("Tenant {} - New alarm posted: {}", tenant, rt);
                } else if (targetAPI.equals(API.MEASUREMENT)) {
                    MeasurementRepresentation measurementRepresentation = jsonParser
                            .parse(MeasurementRepresentation.class, payload);
                    rt = measurementApi.create(measurementRepresentation);
                    log.info("Tenant {} - New measurement posted: {}", tenant, rt);
                } else if (targetAPI.equals(API.OPERATION)) {
                    OperationRepresentation operationRepresentation = jsonParser
                            .parse(OperationRepresentation.class, payload);
                    rt = deviceControlApi.create(operationRepresentation);
                    log.info("Tenant {} - New operation posted: {}", tenant, rt);
                } else {
                    log.error("Tenant {} - Not existing API!", tenant);
                }
            } catch (JsonProcessingException e) {
                log.error("Tenant {} - Could not map payload: {} {}", tenant, targetAPI, payload);
                error.append("Could not map payload: " + targetAPI + "/" + payload);
            } catch (SDKException s) {
                log.error("Tenant {} - Could not sent payload to c8y: {} {}: ", tenant, targetAPI, payload, s);
                error.append("Could not sent payload to c8y: " + targetAPI + "/" + payload + "/" + s);
            }
            return rt;
        });
        if (!error.toString().equals("")) {
            throw new ProcessingException(error.toString());
        }
        return result;
    }

    public ManagedObjectRepresentation upsertDevice(String tenant, ID identity, ProcessingContext<?> context)
            throws ProcessingException {
        StringBuffer error = new StringBuffer("");
        C8YRequest currentRequest = context.getCurrentRequest();
        ManagedObjectRepresentation device = subscriptionsService.callForTenant(tenant, () -> {
            ManagedObjectRepresentation mor = configurationRegistry.getObjectMapper().readValue(
                    currentRequest.getRequest(),
                    ManagedObjectRepresentation.class);
            try {
                ExternalIDRepresentation extId = resolveExternalId2GlobalId(tenant, identity, context);
                if (extId == null) {
                    // Device does not exist
                    // append external id to name
                    mor.setName(mor.getName());
                    mor.set(new IsDevice());
                    // remove id
                    mor.setId(null);

                    mor = inventoryApi.create(mor, context);
                    log.info("Tenant {} - New device created: {}", tenant, mor);
                    identityApi.create(mor, identity, context);
                } else {
                    // Device exists - update needed
                    mor.setId(extId.getManagedObject().getId());
                    mor = inventoryApi.update(mor, context);

                    log.info("Tenant {} - Device updated: {}", tenant, mor);
                }
            } catch (SDKException s) {
                log.error("Tenant {} - Could not sent payload to c8y: {}: ", tenant, currentRequest.getRequest(), s);
                error.append("Could not sent payload to c8y: " + currentRequest.getRequest() + " " + s);
            }
            return mor;
        });
        if (!error.toString().equals("")) {
            throw new ProcessingException(error.toString());
        }
        return device;
    }

    public void loadProcessorExtensions(String tenant) {
        ClassLoader internalClassloader = C8YAgent.class.getClassLoader();
        ClassLoader externalClassLoader = null;

        for (ManagedObjectRepresentation extension : extensionsComponent.get()) {
            Map<?, ?> props = (Map<?, ?>) (extension.get(ExtensionsComponent.PROCESSOR_EXTENSION_TYPE));
            String extName = props.get("name").toString();
            boolean external = (Boolean) props.get("external");
            log.info("Tenant {} - Trying to load extension id: {}, name: {}", tenant, extension.getId().getValue(),
                    extName);
            try {
                if (external) {
                    // step 1 download extension for binary repository
                    InputStream downloadInputStream = binaryApi.downloadFile(extension.getId());

                    // step 2 create temporary file,because classloader needs a url resource
                    File tempFile = File.createTempFile(extName, "jar");
                    tempFile.deleteOnExit();
                    String canonicalPath = tempFile.getCanonicalPath();
                    String path = tempFile.getPath();
                    String pathWithProtocol = "file://".concat(tempFile.getPath());
                    log.debug("Tenant {} - CanonicalPath: {}, Path: {}, PathWithProtocol: {}", tenant, canonicalPath,
                            path,
                            pathWithProtocol);
                    FileOutputStream outputStream = new FileOutputStream(tempFile);
                    IOUtils.copy(downloadInputStream, outputStream);

                    // step 3 parse list of extensions
                    URL[] urls = { tempFile.toURI().toURL() };
                    externalClassLoader = new URLClassLoader(urls, App.class.getClassLoader());
                    registerExtensionInProcessor(tenant, extension.getId().getValue(), extName, externalClassLoader,
                            external);
                } else {
                    registerExtensionInProcessor(tenant, extension.getId().getValue(), extName, internalClassloader,
                            external);
                }
            } catch (IOException e) {
                log.error("Tenant {} - Exception occurred, When loading extension, starting without extensions: ", tenant,
                        e);
            }
        }
    }

    private void registerExtensionInProcessor(String tenant, String id, String extensionName, ClassLoader dynamicLoader,
            boolean external)
            throws IOException {
        ExtensibleProcessorInbound extensibleProcessor = configurationRegistry.getExtensibleProcessors().get(tenant);
        extensibleProcessor.addExtension(tenant, new Extension(id, extensionName, external));
        String resource = external ? EXTENSION_EXTERNAL_FILE : EXTENSION_INTERNAL_FILE;
        InputStream resourceAsStream = dynamicLoader.getResourceAsStream(resource);
        InputStreamReader in = null;
        try {
            in = new InputStreamReader(resourceAsStream);
        } catch (Exception e) {
            log.error("Tenant {} - Registration file: {} missing, ignoring to load extensions from: {}", tenant,
                    resource,
                    (external ? "EXTERNAL" : "INTERNAL"));
            throw new IOException("Registration file: " + resource + " missing, ignoring to load extensions from:"
                    + (external ? "EXTERNAL" : "INTERNAL"));
        }
        BufferedReader buffered = new BufferedReader(in);
        Properties newExtensions = new Properties();

        if (buffered != null)
            newExtensions.load(buffered);
        log.info("Tenant {} - Preparing to load extensions:" + newExtensions.toString(), tenant);

        Enumeration<?> extensions = newExtensions.propertyNames();
        while (extensions.hasMoreElements()) {
            String key = (String) extensions.nextElement();
            Class<?> clazz;
            ExtensionEntry extensionEntry = new ExtensionEntry(key, newExtensions.getProperty(key),
                    null, true, "OK");
            extensibleProcessor.addExtensionEntry(tenant, extensionName, extensionEntry);

            try {
                clazz = dynamicLoader.loadClass(newExtensions.getProperty(key));
                if (external && !clazz.getPackageName().startsWith(PACKAGE_MAPPING_PROCESSOR_EXTENSION_EXTERNAL)) {
                    extensionEntry.setMessage(
                            "Implementation must be in package: 'dynamic.mapping.processor.extension.external' instead of: "
                                    + clazz.getPackageName());
                    extensionEntry.setLoaded(false);
                } else {
                    Object object = clazz.getDeclaredConstructor().newInstance();
                    if (!(object instanceof ProcessorExtensionInbound)) {
                        String msg = String.format(
                                "Extension: %s=%s is not instance of ProcessorExtension, ignoring this entry!", key,
                                newExtensions.getProperty(key));
                        log.warn(msg);
                        extensionEntry.setLoaded(false);
                    } else {
                        ProcessorExtensionInbound<?> extensionImpl = (ProcessorExtensionInbound<?>) clazz
                                .getDeclaredConstructor()
                                .newInstance();
                        // springUtil.registerBean(key, clazz);
                        extensionEntry.setExtensionImplementation(extensionImpl);
                        log.info("Tenant {} - Successfully registered bean: {} for key: {}", tenant,
                                newExtensions.getProperty(key),
                                key);
                    }
                }
            } catch (Exception e) {
                String msg = String.format("Could not load extension: %s:%s, ignoring loading!", key,
                        newExtensions.getProperty(key));
                log.warn(msg);
                e.printStackTrace();
                extensionEntry.setLoaded(false);
            }
        }
        extensibleProcessor.updateStatusExtension(extensionName);
    }

    public Map<String, Extension> getProcessorExtensions(String tenant) {
        ExtensibleProcessorInbound extensibleProcessor = configurationRegistry.getExtensibleProcessors().get(tenant);
        return extensibleProcessor.getExtensions();
    }

    public Extension getProcessorExtension(String tenant, String extension) {
        ExtensibleProcessorInbound extensibleProcessor = configurationRegistry.getExtensibleProcessors().get(tenant);
        return extensibleProcessor.getExtension(extension);
    }

    public Extension deleteProcessorExtension(String tenant, String extensionName) {
        ExtensibleProcessorInbound extensibleProcessor = configurationRegistry.getExtensibleProcessors().get(tenant);
        for (ManagedObjectRepresentation extensionRepresentation : extensionsComponent.get()) {
            if (extensionName.equals(extensionRepresentation.getName())) {
                binaryApi.deleteFile(extensionRepresentation.getId());
                log.info("Tenant {} - Deleted extension: {} permanently!", tenant, extensionName);
            }
        }
        return extensibleProcessor.deleteExtension(extensionName);
    }

    public void reloadExtensions(String tenant) {
        ExtensibleProcessorInbound extensibleProcessor = configurationRegistry.getExtensibleProcessors().get(tenant);
        extensibleProcessor.deleteExtensions();
        loadProcessorExtensions(tenant);
    }

    public ManagedObjectRepresentation getManagedObjectForId(String tenant, String deviceId) {
        ManagedObjectRepresentation device = subscriptionsService.callForTenant(tenant, () -> {
            try {
                return inventoryApi.get(GId.asGId(deviceId));
            } catch (SDKException exception) {
                log.warn("Tenant {} - Device with id {} not found!", tenant, deviceId);
            }
            return null;
        });

        return device;
    }

    public void updateOperationStatus(String tenant, OperationRepresentation op, OperationStatus status,
            String failureReason) {
        subscriptionsService.runForTenant(tenant, () -> {
            try {
                op.setStatus(status.toString());
                if (failureReason != null)
                    op.setFailureReason(failureReason);
                deviceControlApi.update(op);
            } catch (SDKException exception) {
                log.error("Tenant {} - Operation with id {} could not be updated: {}", tenant,
                        op.getDeviceId().getValue(),
                        exception.getLocalizedMessage());
            }
        });
    }

    public ManagedObjectRepresentation initializeMappingServiceObject(String tenant) {
        ExternalIDRepresentation mappingServiceIdRepresentation = resolveExternalId2GlobalId(tenant,
                new ID(null, MappingServiceRepresentation.AGENT_ID),
                null);
        ;
        ManagedObjectRepresentation amo = new ManagedObjectRepresentation();

        if (mappingServiceIdRepresentation != null) {
            amo = inventoryApi.get(mappingServiceIdRepresentation.getManagedObject().getId());
            log.info("Tenant {} - Agent with ID {} already exists {}", tenant,
                    MappingServiceRepresentation.AGENT_ID,
                    mappingServiceIdRepresentation, amo.getId());
            log.info("Tenant {} - Agent representation {}", tenant,
                    MappingServiceRepresentation.AGENT_ID,
                    mappingServiceIdRepresentation);
        } else {
            amo.setName(MappingServiceRepresentation.AGENT_NAME);
            amo.setType(MappingServiceRepresentation.AGENT_TYPE);
            amo.set(new Agent());
            amo.set(new IsDevice());
            amo.setProperty(C8YAgent.MAPPING_FRAGMENT,
                    new ArrayList<>());
            amo = inventoryApi.create(amo, null);
            log.info("Tenant {} - Agent has been created with ID {}", tenant, amo.getId());
            ExternalIDRepresentation externalAgentId = identityApi.create(amo,
                    new ID("c8y_Serial",
                            MappingServiceRepresentation.AGENT_ID),
                    null);
            log.debug("Tenant {} - ExternalId created: {}", tenant, externalAgentId.getExternalId());
        }
        return amo;
    }

    public void createExtensibleProcessor(String tenant) {
        ExtensibleProcessorInbound extensibleProcessor = new ExtensibleProcessorInbound(configurationRegistry);
        configurationRegistry.getExtensibleProcessors().put(tenant, extensibleProcessor);
        log.info("Tenant {} - create ExtensibleProcessor {}", tenant, extensibleProcessor);

        // check if managedObject for internal mapping extension exists
        List<ManagedObjectRepresentation> internalExtension = extensionsComponent.getInternal();
        ManagedObjectRepresentation ie = new ManagedObjectRepresentation();
        if (internalExtension == null || internalExtension.size() == 0) {
            Map<String, ?> props = Map.of("name",
                    ExtensionsComponent.PROCESSOR_EXTENSION_INTERNAL_NAME,
                    "external", false);
            ie.setProperty(ExtensionsComponent.PROCESSOR_EXTENSION_TYPE,
                    props);
            ie.setName(ExtensionsComponent.PROCESSOR_EXTENSION_INTERNAL_NAME);
            ie = inventoryApi.create(ie, null);
        } else {
            ie = internalExtension.get(0);
        }
        log.info("Tenant {} - Internal extension: {} registered: {}", tenant,
                ExtensionsComponent.PROCESSOR_EXTENSION_INTERNAL_NAME,
                ie.getId().getValue(), ie);
    }

    public void sendNotificationLifecycle(String tenant, ConnectorStatus connectorStatus, String message) {
        if (configurationRegistry.getServiceConfigurations().get(tenant).sendNotificationLifecycle) {
            DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            Date now = new Date();
            String date = dateFormat.format(now);
            Map<String, String> stMap = Map.ofEntries(
                    entry("status", connectorStatus.name()),
                    entry("message",
                            message == null ? C8Y_NOTIFICATION_CONNECTOR + ": " + connectorStatus.name() : message),
                    entry("connectorName", C8Y_NOTIFICATION_CONNECTOR),
                    entry("connectorIdent", "000000"),
                    entry("date", date));
            createEvent("Connector status:" + connectorStatus.name(),
                    C8YAgent.STATUS_NOTIFICATION_EVENT_TYPE,
                    DateTime.now(), configurationRegistry.getMappingServiceRepresentations().get(tenant), tenant,
                    stMap);
        }
    }

}